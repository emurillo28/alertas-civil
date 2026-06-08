package com.hospital.alertas.infrastructure.adapters.persistence;

import com.hospital.alertas.application.ports.outbound.LabPersistencePort;
import com.hospital.alertas.domain.exception.DatabaseThrottledException;
import com.hospital.alertas.domain.exception.LabValidationException;
import com.hospital.alertas.domain.exception.OptimisticLockException;
import com.hospital.alertas.domain.model.EstadoVisibilidad;
import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.NotificacionEnviada;
import com.hospital.alertas.domain.model.NotificationChannel;
import com.hospital.alertas.domain.model.NotificationStatus;
import com.hospital.alertas.domain.model.PatientId;
import com.hospital.alertas.domain.model.PatientTimeline;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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
    static final String ATTR_ESTADO_VISIBILIDAD = "EstadoVisibilidad";
    static final String ATTR_NOTIFICACIONES_ENVIADAS = "NotificacionesEnviadas";

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

        long newVersion = lab.version() + 1;

        // Construir expresión SET para UpdateItem
        // PK y SK van en el Key, no en SET
        Map<String, AttributeValue> key = Map.of(
                ATTR_PK, AttributeValue.fromS(lab.patientId().toDynamoPartitionKey()),
                ATTR_SK, AttributeValue.fromS(SK_LAB_PREFIX + lab.labId())
        );

        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        Map<String, String> expressionAttributeNames = new HashMap<>();
        StringBuilder updateExpression = new StringBuilder("SET ");

        // Mapear cada campo usando nombres de atributo alias para evitar palabras reservadas
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#tipoExamen", ATTR_TIPO_EXAMEN, AttributeValue.fromS(lab.tipoExamen()));
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#timestamp", ATTR_TIMESTAMP, AttributeValue.fromS(lab.timestamp().toString()));
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#alertaCritica", ATTR_ES_ALERTA_CRITICA, AttributeValue.fromBool(lab.alertaCritica()));
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#s3Path", ATTR_S3_PATH, AttributeValue.fromS(lab.s3PathCompleto()));
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#version", ATTR_VERSION, AttributeValue.fromN(String.valueOf(newVersion)));

        // Valores analíticos (Map)
        Map<String, AttributeValue> analyticsMap = new HashMap<>();
        lab.valoresAnaliticos().forEach((k, v) -> analyticsMap.put(k, AttributeValue.fromS(v)));
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#valoresAnaliticos", ATTR_VALORES_ANALITICOS, AttributeValue.fromM(analyticsMap));

        // Resumen clínico (opcional)
        if (lab.resumenClinico() != null) {
            addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                    "#resumenClinico", ATTR_RESUMEN_CLINICO, AttributeValue.fromS(lab.resumenClinico()));
        }

        // Estado de visibilidad (siempre presente, default PENDIENTE)
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#estadoVisibilidad", ATTR_ESTADO_VISIBILIDAD, AttributeValue.fromS(lab.estadoVisibilidad().name()));

        // Notificaciones enviadas (lista, puede estar vacía)
        if (!lab.notificaciones().isEmpty()) {
            List<AttributeValue> notificacionesList = lab.notificaciones().stream()
                    .map(n -> {
                        Map<String, AttributeValue> nMap = new HashMap<>();
                        nMap.put("NotificacionId", AttributeValue.fromS(n.notificacionId()));
                        nMap.put("Destinatario", AttributeValue.fromS(n.destinatario()));
                        nMap.put("Canal", AttributeValue.fromS(n.canal().name()));
                        nMap.put("Estado", AttributeValue.fromS(n.estado().name()));
                        nMap.put("TimestampEnvio", AttributeValue.fromS(n.timestampEnvio().toString()));
                        if (n.timestampLectura() != null) {
                            nMap.put("TimestampLectura", AttributeValue.fromS(n.timestampLectura().toString()));
                        }
                        return AttributeValue.fromM(nMap);
                    })
                    .toList();
            addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                    "#notificaciones", ATTR_NOTIFICACIONES_ENVIADAS, AttributeValue.fromL(notificacionesList));
        }

        // Quitar la coma final
        if (updateExpression.charAt(updateExpression.length() - 1) == ',') {
            updateExpression.setLength(updateExpression.length() - 2);
        }

        UpdateItemRequest.Builder requestBuilder = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression(updateExpression.toString())
                .expressionAttributeNames(expressionAttributeNames)
                .expressionAttributeValues(expressionAttributeValues);

        // ── Control de Concurrencia Optimista (OCC) ──
        if (lab.version() == 0) {
            // Ítem nuevo: no debe existir previamente
            requestBuilder.conditionExpression(
                    "attribute_not_exists(#pk) AND attribute_not_exists(#sk)");
            expressionAttributeNames.put("#pk", ATTR_PK);
            expressionAttributeNames.put("#sk", ATTR_SK);
        } else {
            // Actualización: verificar que la versión en DB coincida
            requestBuilder
                    .conditionExpression("#version = :expectedVersion")
                    .expressionAttributeValues(Map.of(
                            ":expectedVersion", AttributeValue.fromN(String.valueOf(lab.version()))
                    ));
            expressionAttributeNames.put("#version", ATTR_VERSION);
        }

        try {
            dynamoDbClient.updateItem(requestBuilder.build());
            LOG.fine(() -> "Lab %s persistido exitosamente (versión %d → %d)"
                    .formatted(lab.labId(), lab.version(), newVersion));

        } catch (ConditionalCheckFailedException e) {
            LOG.log(Level.WARNING, "Conflicto OCC al guardar lab {0} (versión esperada: {1})",
                    new Object[]{lab.labId(), lab.version()});
            throw new OptimisticLockException(
                    lab.labId(),
                    lab.version(),
                    "Conflicto de concurrencia optimista: el laboratorio '%s' fue modificado por otro proceso. "
                            .formatted(lab.labId())
                            + "Versión esperada: %d. Reintente la operación con datos actualizados."
                            .formatted(lab.version()),
                    e);

        } catch (ProvisionedThroughputExceededException e) {
            LOG.log(Level.SEVERE, "Capacidad de DynamoDB excedida al guardar lab {0}", lab.labId());
            throw new DatabaseThrottledException(
                    "Capacidad de lectura/escritura de DynamoDB excedida. "
                            + "Considere habilitar Auto Scaling o aumentar las WCU provisionadas.",
                    e);
        }
    }

    /** Helper para agregar cláusula SET a la expresión de actualización. */
    private void addSetClause(StringBuilder updateExpression,
                              Map<String, String> expressionAttributeNames,
                              Map<String, AttributeValue> expressionAttributeValues,
                              String attrNameAlias, String attrName, AttributeValue value) {
        expressionAttributeNames.put(attrNameAlias, attrName);
        String valueAlias = attrNameAlias.substring(1) + "Val"; // e.g., #tipoExamen → tipoExamenVal
        expressionAttributeValues.put(":" + valueAlias, value);
        updateExpression.append(attrNameAlias).append(" = :").append(valueAlias).append(", ");
    }

    // ─── obtenerUltimoLaboratorio ────────────────────────────────────────

    @Override
    public Optional<LabResult> obtenerUltimoLaboratorio(PatientId patientId) {
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
            throw new DatabaseThrottledException(
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

    // ─── Mapeo DynamoDB → Domain ─────────────────────────────────────────

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

        // Estado de visibilidad (default PENDIENTE para compatibilidad con ítems legacy)
        EstadoVisibilidad estadoVisibilidad = EstadoVisibilidad.PENDIENTE;
        if (item.containsKey(ATTR_ESTADO_VISIBILIDAD)) {
            try {
                estadoVisibilidad = EstadoVisibilidad.valueOf(item.get(ATTR_ESTADO_VISIBILIDAD).s());
            } catch (IllegalArgumentException e) {
                LOG.warning(() -> "EstadoVisibilidad inválido en DynamoDB, usando PENDIENTE: " + item.get(ATTR_ESTADO_VISIBILIDAD).s());
            }
        }

        // Notificaciones enviadas (lista puede no existir en ítems legacy)
        List<NotificacionEnviada> notificaciones = List.of();
        if (item.containsKey(ATTR_NOTIFICACIONES_ENVIADAS)) {
            notificaciones = item.get(ATTR_NOTIFICACIONES_ENVIADAS).l().stream()
                    .map(av -> {
                        Map<String, AttributeValue> nMap = av.m();
                        String notificacionId = nMap.get("NotificacionId").s();
                        String destinatario = nMap.get("Destinatario").s();
                        NotificationChannel canal = NotificationChannel.valueOf(nMap.get("Canal").s());
                        NotificationStatus estado = NotificationStatus.valueOf(nMap.get("Estado").s());
                        Instant timestampEnvio = Instant.parse(nMap.get("TimestampEnvio").s());
                        Instant timestampLectura = nMap.containsKey("TimestampLectura")
                                ? Instant.parse(nMap.get("TimestampLectura").s())
                                : null;
                        return new NotificacionEnviada(notificacionId, destinatario, canal, estado, timestampEnvio, timestampLectura);
                    })
                    .toList();
        }

        return new LabResult(
                labId,
                new PatientId(patientIdValue),
                item.get(ATTR_TIPO_EXAMEN).s(),
                valoresAnaliticos,
                Instant.parse(item.get(ATTR_TIMESTAMP).s()),
                item.get(ATTR_ES_ALERTA_CRITICA).bool(),
                item.get(ATTR_S3_PATH).s(),
                resumenClinico,
                version,
                estadoVisibilidad,
                notificaciones
        );
    }

    // ─── obtenerExpedienteCompleto ────────────────────────────────────────

    @Override
    public Optional<PatientTimeline> obtenerExpedienteCompleto(PatientId patientId) {
        Objects.requireNonNull(patientId, "PatientId no puede ser nulo");

        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("%s = :pk".formatted(ATTR_PK))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(patientId.toDynamoPartitionKey())
                ))
                .build();

        try {
            QueryResponse response = dynamoDbClient.query(request);

            String nombre = null;
            Instant fechaNacimiento = null;
            String historialClinico = null;
            List<LabResult> laboratorios = new ArrayList<>();

            for (Map<String, AttributeValue> item : response.items()) {
                String sk = item.get(ATTR_SK).s();
                if ("METADATA".equals(sk)) {
                    // Ítem de metadatos del paciente
                    if (item.containsKey("Nombre")) nombre = item.get("Nombre").s();
                    if (item.containsKey("FechaNacimiento")) fechaNacimiento = Instant.parse(item.get("FechaNacimiento").s());
                    if (item.containsKey("HistorialClinico")) historialClinico = item.get("HistorialClinico").s();
                } else if (sk.startsWith(SK_LAB_PREFIX)) {
                    // Ítem de laboratorio
                    laboratorios.add(fromItemMap(item));
                }
            }

            if (nombre == null && laboratorios.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(new PatientTimeline(patientId, nombre, fechaNacimiento, historialClinico, laboratorios));

        } catch (ProvisionedThroughputExceededException e) {
            LOG.log(Level.SEVERE, "Capacidad de DynamoDB excedida al consultar expediente paciente {0}",
                    patientId.value());
            throw new DatabaseThrottledException(
                    "Capacidad de lectura de DynamoDB excedida al consultar expediente paciente '%s'."
                            .formatted(patientId.value()),
                    e);
        }
    }

    // ─── actualizarEstadoVisibilidad ──────────────────────────────────────

    @Override
    public void actualizarEstadoVisibilidad(PatientId patientId, String labId,
                                             EstadoVisibilidad nuevoEstado, long expectedVersion) {
        Objects.requireNonNull(patientId, "PatientId no puede ser nulo");
        Objects.requireNonNull(labId, "labId no puede ser nulo");
        Objects.requireNonNull(nuevoEstado, "nuevoEstado no puede ser nulo");

        Map<String, AttributeValue> key = Map.of(
                ATTR_PK, AttributeValue.fromS(patientId.toDynamoPartitionKey()),
                ATTR_SK, AttributeValue.fromS(SK_LAB_PREFIX + labId)
        );

        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        Map<String, String> expressionAttributeNames = new HashMap<>();

        expressionAttributeValues.put(":estado", AttributeValue.fromS(nuevoEstado.name()));
        expressionAttributeValues.put(":version", AttributeValue.fromN(String.valueOf(expectedVersion + 1)));
        expressionAttributeValues.put(":expectedVersion", AttributeValue.fromN(String.valueOf(expectedVersion)));

        expressionAttributeNames.put("#estadoVisibilidad", "EstadoVisibilidad");
        expressionAttributeNames.put("#version", ATTR_VERSION);

        String updateExpression = "SET #estadoVisibilidad = :estado, #version = :version";

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression(updateExpression)
                .expressionAttributeNames(expressionAttributeNames)
                .expressionAttributeValues(expressionAttributeValues)
                .conditionExpression("#version = :expectedVersion")
                .build();

        try {
            dynamoDbClient.updateItem(request);
            LOG.fine(() -> "Estado visibilidad actualizado para lab %s: %s (versión %d → %d)"
                    .formatted(labId, nuevoEstado, expectedVersion, expectedVersion + 1));

        } catch (ConditionalCheckFailedException e) {
            LOG.log(Level.WARNING, "Conflicto OCC al actualizar estado visibilidad lab {0} (versión esperada: {1})",
                    new Object[]{labId, expectedVersion});
            throw new OptimisticLockException(
                    labId,
                    expectedVersion,
                    "Conflicto de concurrencia optimista: el laboratorio '%s' fue modificado por otro proceso. "
                            .formatted(labId)
                            + "Versión esperada: %d. Reintente la operación con datos actualizados."
                            .formatted(expectedVersion),
                    e);

        } catch (ProvisionedThroughputExceededException e) {
            LOG.log(Level.SEVERE, "Capacidad de DynamoDB excedida al actualizar estado lab {0}", labId);
            throw new DatabaseThrottledException(
                    "Capacidad de escritura de DynamoDB excedida al actualizar estado.",
                    e);
        }
    }

    // ─── registrarNotificacion ────────────────────────────────────────────

    @Override
    public void registrarNotificacion(PatientId patientId, String labId,
                                       NotificacionEnviada notificacion,
                                       EstadoVisibilidad nuevoEstado, long expectedVersion) {
        Objects.requireNonNull(patientId, "PatientId no puede ser nulo");
        Objects.requireNonNull(labId, "labId no puede ser nulo");
        Objects.requireNonNull(notificacion, "notificacion no puede ser nulo");
        Objects.requireNonNull(nuevoEstado, "nuevoEstado no puede ser nulo");

        // Primero leer el laboratorio actual para obtener la lista de notificaciones
        Optional<LabResult> currentLabOpt = obtenerUltimoLaboratorio(patientId)
                .filter(lab -> lab.labId().equals(labId));

        if (currentLabOpt.isEmpty()) {
            throw new LabValidationException("Laboratorio no encontrado: " + labId);
        }

        LabResult currentLab = currentLabOpt.get();

        // Validar que la versión coincida
        if (currentLab.version() != expectedVersion) {
            throw new OptimisticLockException(
                    labId,
                    expectedVersion,
                    "Conflicto de concurrencia: versión esperada " + expectedVersion + ", actual " + currentLab.version());
        }

        // Agregar la notificación a la lista
        List<NotificacionEnviada> nuevasNotificaciones = new ArrayList<>(currentLab.notificaciones());
        nuevasNotificaciones.add(notificacion);

        Map<String, AttributeValue> key = Map.of(
                ATTR_PK, AttributeValue.fromS(patientId.toDynamoPartitionKey()),
                ATTR_SK, AttributeValue.fromS(SK_LAB_PREFIX + labId)
        );

        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        Map<String, String> expressionAttributeNames = new HashMap<>();

        // Serializar la lista de notificaciones
        List<AttributeValue> notificacionesList = nuevasNotificaciones.stream()
                .map(n -> {
                    Map<String, AttributeValue> nMap = new HashMap<>();
                    nMap.put("NotificacionId", AttributeValue.fromS(n.notificacionId()));
                    nMap.put("Destinatario", AttributeValue.fromS(n.destinatario()));
                    nMap.put("Canal", AttributeValue.fromS(n.canal().name()));
                    nMap.put("Estado", AttributeValue.fromS(n.estado().name()));
                    nMap.put("TimestampEnvio", AttributeValue.fromS(n.timestampEnvio().toString()));
                    if (n.timestampLectura() != null) {
                        nMap.put("TimestampLectura", AttributeValue.fromS(n.timestampLectura().toString()));
                    }
                    return AttributeValue.fromM(nMap);
                })
                .toList();

        expressionAttributeValues.put(":notificaciones", AttributeValue.fromL(notificacionesList));
        expressionAttributeValues.put(":estadoVisibilidad", AttributeValue.fromS(nuevoEstado.name()));
        expressionAttributeValues.put(":version", AttributeValue.fromN(String.valueOf(expectedVersion + 1)));
        expressionAttributeValues.put(":expectedVersion", AttributeValue.fromN(String.valueOf(expectedVersion)));

        expressionAttributeNames.put("#notificaciones", "NotificacionesEnviadas");
        expressionAttributeNames.put("#estadoVisibilidad", "EstadoVisibilidad");
        expressionAttributeNames.put("#version", ATTR_VERSION);

        String updateExpression = "SET #notificaciones = :notificaciones, #estadoVisibilidad = :estadoVisibilidad, #version = :version";

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression(updateExpression)
                .expressionAttributeNames(expressionAttributeNames)
                .expressionAttributeValues(expressionAttributeValues)
                .conditionExpression("#version = :expectedVersion")
                .build();

        try {
            dynamoDbClient.updateItem(request);
            LOG.fine(() -> "Notificación registrada y estado actualizado para lab %s: %s (versión %d → %d)"
                    .formatted(labId, nuevoEstado, expectedVersion, expectedVersion + 1));

        } catch (ConditionalCheckFailedException e) {
            LOG.log(Level.WARNING, "Conflicto OCC al registrar notificación lab {0} (versión esperada: {1})",
                    new Object[]{labId, expectedVersion});
            throw new OptimisticLockException(
                    labId,
                    expectedVersion,
                    "Conflicto de concurrencia optimista al registrar notificación: el laboratorio '%s' fue modificado por otro proceso. "
                            .formatted(labId)
                            + "Versión esperada: %d. Reintente la operación con datos actualizados."
                            .formatted(expectedVersion),
                    e);

        } catch (ProvisionedThroughputExceededException e) {
            LOG.log(Level.SEVERE, "Capacidad de DynamoDB excedida al registrar notificación lab {0}", labId);
            throw new DatabaseThrottledException(
                    "Capacidad de escritura de DynamoDB excedida al registrar notificación.",
                    e);
        }
    }
}

