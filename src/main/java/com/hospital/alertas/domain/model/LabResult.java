package com.hospital.alertas.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidad de dominio que representa un resultado de laboratorio clínico.
 *
 * <p>Mapea al patrón Single-Table de DynamoDB:
 * <ul>
 *   <li>PK: PATIENT#<PatientId></li>
 *   <li>SK: LAB#<labId></li>
 * </ul>
 *
 * <p>Modelada como Java Record para garantizar inmutabilidad.
 * Los valores analíticos se representan como un {@code Map<String, String>}
 * para soportar la naturaleza heterogénea de los exámenes clínicos
 * (ej. glucosa=120mg/dL, hemoglobina=14.5g/dL).
 *
 * <p>El campo {@code resumenClinico} se almacena inline (sub-recurso 1:1)
 * dentro del ítem de laboratorio para optimizar costos de lectura (1 RCU).
 *
 * <p>El campo {@code version} soporta Control de Concurrencia Optimista (OCC)
 * mediante ConditionExpression en DynamoDB.
 */
public record LabResult(
        String labId,
        PatientId patientId,
        String tipoExamen,
        Map<String, String> valoresAnaliticos,
        Instant timestamp,
        boolean alertaCritica,
        String s3PathCompleto,
        String resumenClinico,
        long version
) {

    /**
     * Constructor compacto con validaciones defensivas.
     * Garantiza que ningún campo obligatorio sea nulo o inválido.
     */
    public LabResult {
        Objects.requireNonNull(labId, "labId no puede ser nulo");
        if (labId.isBlank()) {
            throw new IllegalArgumentException("labId no puede estar vacío");
        }
        Objects.requireNonNull(patientId, "patientId no puede ser nulo");
        Objects.requireNonNull(tipoExamen, "tipoExamen no puede ser nulo");
        if (tipoExamen.isBlank()) {
            throw new IllegalArgumentException("tipoExamen no puede estar vacío");
        }
        Objects.requireNonNull(valoresAnaliticos, "valoresAnaliticos no puede ser nulo");
        if (valoresAnaliticos.isEmpty()) {
            throw new IllegalArgumentException("valoresAnaliticos no puede estar vacío: debe contener al menos un resultado analítico");
        }
        // Copia defensiva para garantizar inmutabilidad del mapa
        valoresAnaliticos = Map.copyOf(valoresAnaliticos);
        Objects.requireNonNull(timestamp, "timestamp no puede ser nulo");
        Objects.requireNonNull(s3PathCompleto, "s3PathCompleto no puede ser nulo");
        if (s3PathCompleto.isBlank()) {
            throw new IllegalArgumentException("s3PathCompleto no puede estar vacío");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version debe ser >= 0. Recibido: %d".formatted(version));
        }
        // resumenClinico puede ser nulo (aún no generado por el flujo de IA)
    }

    /**
     * Genera la clave de ordenamiento (SK) para el modelo Single-Table de DynamoDB.
     *
     * @return cadena con formato LAB#<labId>
     */
    public String toDynamoSortKey() {
        return "LAB#" + labId;
    }

    /**
     * Crea un nuevo LabResult con la versión incrementada para OCC.
     *
     * @return nueva instancia con version + 1
     */
    public LabResult withIncrementedVersion() {
        return new LabResult(
                labId, patientId, tipoExamen, valoresAnaliticos,
                timestamp, alertaCritica, s3PathCompleto, resumenClinico,
                version + 1
        );
    }

    /**
     * Crea un nuevo LabResult con el resumen clínico actualizado.
     *
     * @param nuevoResumen texto del resumen clínico generado
     * @return nueva instancia con el resumen clínico establecido
     */
    public LabResult withResumenClinico(String nuevoResumen) {
        Objects.requireNonNull(nuevoResumen, "El resumen clínico no puede ser nulo");
        return new LabResult(
                labId, patientId, tipoExamen, valoresAnaliticos,
                timestamp, alertaCritica, s3PathCompleto, nuevoResumen,
                version
        );
    }

    /**
     * Crea un nuevo LabResult con el indicador de alerta crítica actualizado.
     * Utilizado por el servicio de ingesta cuando la evaluación de umbrales
     * clínicos determina que el resultado requiere atención inmediata.
     *
     * @param esAlertaCritica true si el resultado supera umbrales de peligro
     * @return nueva instancia con el indicador de alerta actualizado
     */
    public LabResult withAlertaCritica(boolean esAlertaCritica) {
        return new LabResult(
                labId, patientId, tipoExamen, valoresAnaliticos,
                timestamp, esAlertaCritica, s3PathCompleto, resumenClinico,
                version
        );
    }

    /**
     * Método factory para crear un nuevo LabResult con un UUID generado automáticamente
     * y versión inicial 0.
     */
    public static LabResult create(
            PatientId patientId,
            String tipoExamen,
            Map<String, String> valoresAnaliticos,
            Instant timestamp,
            boolean alertaCritica,
            String s3PathCompleto
    ) {
        return new LabResult(
                UUID.randomUUID().toString(),
                patientId,
                tipoExamen,
                valoresAnaliticos,
                timestamp,
                alertaCritica,
                s3PathCompleto,
                null,   // resumenClinico se genera después por el flujo de IA
                0L      // versión inicial para OCC
        );
    }
}
