package com.hospital.alertas.infrastructure.adapters.persistence;

import com.hospital.alertas.application.ports.outbound.LabPersistencePort;
import com.hospital.alertas.domain.exception.LabValidationException;
import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.PatientId;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adaptador de persistencia que conecta el puerto {@link LabPersistencePort}
 * con Amazon DynamoDB usando el modelo Single-Table.
 *
 * <p>Usa el cliente síncrono estándar {@link DynamoDbClient} que es compatible
 * con Project Loom: los hilos virtuales se desmontan eficientemente durante
 * las operaciones de I/O de red, sin thread pinning.
 *
 * <p><strong>Esquema Single-Table (tabla: hc-alertas):</strong>
 * <ul>
 *   <li>PK: PATIENT#&lt;PatientId&gt;</li>
 *   <li>SK: LAB#&lt;LabId&gt;</li>
 *   <li>Version: numérico, obligatorio para OCC</li>
 * </ul>
 *
 * <p><strong>Nota sobre thread pinning:</strong> Este adaptador NO usa
 * {@code synchronized}. Las operaciones de DynamoDB son thread-safe por diseño
 * del cliente AWS SDK. Usamos {@code java.util.logging} en lugar de locks
 * para evitar pinning del carrier thread en hilos virtuales.
 */
@ApplicationScoped
public class DynamoDbPersistenceAdapter implements LabPersistencePort {

    private static final Logger LOG = Logger.getLogger(DynamoDbPersistenceAdapter.class.getName());

    // ─── Nombres de atributos DynamoDB ──────────────────────────────────
    static final String ATTR_PK = "PK";
    static final String ATTR_SK = "SK";
    static final String ATTR_TIPO_EXAMEN = "TipoExamen";
    static final String ATTR_VALORES_ANALITICOS = "ValoresAnaliticos";
    static final String ATTR_TIMESTAMP = "Timestamp";
    static final String ATTR_ES_ALERTA_CRITICA = "EsAlertaCritica";
    static final String ATTR_S3_PATH = "S3_Path_Completo";
    static final String ATTR_RESUMEN_CLINICO = "Resumen_Clinico";
    static final String ATTR_VERSION = "Version";

    private static final String SK_LAB_PREFIX = "LAB#";

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    /**
     * Constructor para inyección CDI.
     *
     * @param dynamoDbClient cliente síncrono de DynamoDB (inyectado por Quarkus Amazon Services)
     * @param tableName      nombre de la tabla DynamoDB (configurable vía application.properties)
     */
    public DynamoDbPersistenceAdapter(
            DynamoDbClient dynamoDbClient,
            @ConfigProperty(name = "app.dynamodb.table-name", defaultValue = "hc-alertas") String tableName
    ) {
        this.dynamoDbClient = Objects.requireNonNull(dynamoDbClient, "DynamoDbClient no puede ser nulo");
        this.tableName = Objects.requireNonNull(tableName, "tableName no puede ser nulo");
    }

    // ─── saveLab ────────────────────────────────────────────────────────

    @Override
    public void saveLab(LabResult lab) {
        Objects.requireNonNull(lab, "LabResult no puede ser nulo");

        Map<String, AttributeValue> item = toItemMap(lab);
        long newVersion = lab.version() + 1;
        item.put(ATTR_VERSION, AttributeValue.fromN(String.valueOf(newVersion)));

        PutItemRequest.Builder requestBuilder = PutItemRequest.builder()
                .tableName(tableName)
                .item(item);

        // ── Control de Concurrencia Optimista (OCC) ──
        if (lab.version() == 0) {
            // Ítem nuevo: no debe existir previamente
            requestBuilder.conditionExpression(
                    "attribute_not_exists(%s) AND attribute_not_exists(%s)".formatted(ATTR_PK, ATTR_SK));
        } else {
            // Actualización: verificar que la versión en DB coincida
            requestBuilder
                    .conditionExpression("%s = :expectedVersion".formatted(ATTR_VERSION))
                    .expressionAttributeValues(Map.of(
                            ":expectedVersion", AttributeValue.fromN(String.valueOf(lab.version()))
                    ));
        }

        try {
            dynamoDbClient.putItem(requestBuilder.build());
            LOG.fine(() -> "Lab %s persistido exitosamente (versión %d → %d)"
                    .formatted(lab.labId(), lab.version(), newVersion));

        } catch (ConditionalCheckFailedException e) {
            LOG.log(Level.WARNING, "Conflicto OCC al guardar lab {0} (versión esperada: {1})",
                    new Object[]{lab.labId(), lab.version()});
            throw new LabValidationException(
                    "Conflicto de concurrencia optimista: el laboratorio '%s' fue modificado por otro proceso. "
                            .formatted(lab.labId())
                            + "Versión esperada: %d. Reintente la operación con datos actualizados."
                            .formatted(lab.version()),
                    e);

        } catch (ProvisionedThroughputExceededException e) {
            LOG.log(Level.SEVERE, "Capacidad de DynamoDB excedida al guardar lab {0}", lab.labId());
            throw new RuntimeException(
                    "Capacidad de lectura/escritura de DynamoDB excedida. "
                            + "Considere habilitar Auto Scaling o aumentar las WCU provisionadas.",
                    e);
        }
    }

    // ─── findLatestByPatient ────────────────────────────────────────────

    @Override
    public Optional<LabResult> findLatestByPatient(PatientId patientId) {
        Objects.requireNonNull(patientId, "PatientId no puede ser nulo");

        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("%s = :pk AND begins_with(%s, :skPrefix)"
                        .formatted(ATTR_PK, ATTR_SK))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(patientId.toDynamoPartitionKey()),
                        ":skPrefix", AttributeValue.fromS(SK_LAB_PREFIX)
                ))
                .build();

        try {
            QueryResponse response = dynamoDbClient.query(request);

            return response.items().stream()
                    .map(this::fromItemMap)
                    .max(Comparator.comparing(LabResult::timestamp));

        } catch (ProvisionedThroughputExceededException e) {
            LOG.log(Level.SEVERE, "Capacidad de DynamoDB excedida al consultar paciente {0}",
                    patientId.value());
            throw new RuntimeException(
                    "Capacidad de lectura de DynamoDB excedida al consultar paciente '%s'."
                            .formatted(patientId.value()),
                    e);
        }
    }

    // ─── Mapeo Domain → DynamoDB ────────────────────────────────────────

    /**
     * Convierte un {@link LabResult} al mapa de atributos de DynamoDB.
     * No incluye Version (se agrega externamente para OCC).
     */
    private Map<String, AttributeValue> toItemMap(LabResult lab) {
        Map<String, AttributeValue> item = new HashMap<>();

        item.put(ATTR_PK, AttributeValue.fromS(lab.patientId().toDynamoPartitionKey()));
        item.put(ATTR_SK, AttributeValue.fromS(SK_LAB_PREFIX + lab.labId()));
        item.put(ATTR_TIPO_EXAMEN, AttributeValue.fromS(lab.tipoExamen()));
        item.put(ATTR_TIMESTAMP, AttributeValue.fromS(lab.timestamp().toString()));
        item.put(ATTR_ES_ALERTA_CRITICA, AttributeValue.fromBool(lab.alertaCritica()));
        item.put(ATTR_S3_PATH, AttributeValue.fromS(lab.s3PathCompleto()));

        // Valores analíticos como Map<String, String> → Map<String, AttributeValue(S)>
        Map<String, AttributeValue> analyticsMap = new HashMap<>();
        lab.valoresAnaliticos().forEach((key, value) ->
                analyticsMap.put(key, AttributeValue.fromS(value)));
        item.put(ATTR_VALORES_ANALITICOS, AttributeValue.fromM(analyticsMap));

        // Resumen clínico es opcional (puede no haberse generado aún)
        if (lab.resumenClinico() != null) {
            item.put(ATTR_RESUMEN_CLINICO, AttributeValue.fromS(lab.resumenClinico()));
        }

        return item;
    }

    // ─── Mapeo DynamoDB → Domain ────────────────────────────────────────

    /**
     * Reconstruye un {@link LabResult} desde un ítem de DynamoDB.
     */
    private LabResult fromItemMap(Map<String, AttributeValue> item) {
        String pk = item.get(ATTR_PK).s();
        String patientIdValue = pk.replace("PATIENT#", "");

        String sk = item.get(ATTR_SK).s();
        String labId = sk.replace(SK_LAB_PREFIX, "");

        // Reconstruir mapa de valores analíticos
        Map<String, String> valoresAnaliticos = new HashMap<>();
        if (item.containsKey(ATTR_VALORES_ANALITICOS)) {
            item.get(ATTR_VALORES_ANALITICOS).m()
                    .forEach((key, value) -> valoresAnaliticos.put(key, value.s()));
        }

        // Resumen clínico puede no existir
        String resumenClinico = item.containsKey(ATTR_RESUMEN_CLINICO)
                ? item.get(ATTR_RESUMEN_CLINICO).s()
                : null;

        long version = Long.parseLong(item.get(ATTR_VERSION).n());

        return new LabResult(
                labId,
                new PatientId(patientIdValue),
                item.get(ATTR_TIPO_EXAMEN).s(),
                valoresAnaliticos,
                Instant.parse(item.get(ATTR_TIMESTAMP).s()),
                item.get(ATTR_ES_ALERTA_CRITICA).bool(),
                item.get(ATTR_S3_PATH).s(),
                resumenClinico,
                version
        );
    }
}
