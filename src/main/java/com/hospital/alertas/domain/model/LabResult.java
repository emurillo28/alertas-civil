package com.hospital.alertas.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidad de dominio que representa un resultado de laboratorio clínico.
 *
 * <p>Mapea al patrón Single-Table de DynamoDB:
 * <ul>
 *   <li>PK: PATIENT#&lt;PatientId&gt;</li>
 *   <li>SK: LAB#&lt;labId&gt;</li>
 * </ul>
 *
 * <p>Modelada como Java Record para garantizar inmutabilidad.
 *
 * <h2>Campos extendidos (v2)</h2>
 * <ul>
 *   <li>{@code estadoVisibilidad}: ciclo de vida de la notificación al médico.
 *       Default: {@link EstadoVisibilidad#PENDIENTE}.</li>
 *   <li>{@code notificaciones}: auditoría inline de todas las notificaciones enviadas.
 *       Se persiste en DynamoDB como lista dentro del mismo ítem (1 RCU de lectura).
 *       Default: lista vacía.</li>
 * </ul>
 *
 * <p>Los campos {@code estadoVisibilidad} y {@code notificaciones} admiten {@code null}
 * en el constructor compacto; se normalizan automáticamente a sus valores por defecto.
 * Esto garantiza compatibilidad con ítems DynamoDB legacy que no tengan estos campos.
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
        long version,
        EstadoVisibilidad estadoVisibilidad,
        List<NotificacionEnviada> notificaciones
) {

    /**
     * Constructor compacto con validaciones defensivas.
     * Normaliza nulls en campos opcionales a sus valores por defecto.
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
            throw new IllegalArgumentException(
                    "valoresAnaliticos no puede estar vacío: debe contener al menos un resultado analítico");
        }
        // Copia defensiva para garantizar inmutabilidad del mapa
        valoresAnaliticos = Map.copyOf(valoresAnaliticos);
        Objects.requireNonNull(timestamp, "timestamp no puede ser nulo");
        Objects.requireNonNull(s3PathCompleto, "s3PathCompleto no puede ser nulo");
        if (s3PathCompleto.isBlank()) {
            throw new IllegalArgumentException("s3PathCompleto no puede estar vacío");
        }
        if (version < 0) {
            throw new IllegalArgumentException(
                    "version debe ser >= 0. Recibido: %d".formatted(version));
        }

        // Normalización de campos opcionales con valores por defecto
        // Permite compatibilidad con ítems DynamoDB legacy sin estos campos
        if (estadoVisibilidad == null) estadoVisibilidad = EstadoVisibilidad.PENDIENTE;
        if (notificaciones == null) notificaciones = List.of();
        notificaciones = List.copyOf(notificaciones); // copia defensiva inmutable
        // resumenClinico puede ser nulo (aún no generado por el flujo de IA)
    }

    // ─── Claves DynamoDB ────────────────────────────────────────────────

    /**
     * Genera la clave de ordenamiento (SK) para el modelo Single-Table.
     *
     * @return cadena con formato LAB#&lt;labId&gt;
     */
    public String toDynamoSortKey() {
        return "LAB#" + labId;
    }

    // ─── Métodos with* (builders funcionales inmutables) ────────────────

    /**
     * Crea un nuevo LabResult con la versión incrementada para OCC.
     *
     * @return nueva instancia con version + 1
     */
    public LabResult withIncrementedVersion() {
        return new LabResult(
                labId, patientId, tipoExamen, valoresAnaliticos,
                timestamp, alertaCritica, s3PathCompleto, resumenClinico,
                version + 1, estadoVisibilidad, notificaciones
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
                version, estadoVisibilidad, notificaciones
        );
    }

    /**
     * Crea un nuevo LabResult con el indicador de alerta crítica actualizado.
     *
     * @param esAlertaCritica true si el resultado supera umbrales de peligro
     * @return nueva instancia con el indicador de alerta actualizado
     */
    public LabResult withAlertaCritica(boolean esAlertaCritica) {
        return new LabResult(
                labId, patientId, tipoExamen, valoresAnaliticos,
                timestamp, esAlertaCritica, s3PathCompleto, resumenClinico,
                version, estadoVisibilidad, notificaciones
        );
    }

    /**
     * Crea un nuevo LabResult con el estado de visibilidad actualizado.
     *
     * <p>Usado por {@code AcknowledgeLabReadService} y {@code NotifyLabReadyService}
     * para avanzar el ciclo: PENDIENTE → NOTIFICADO → CONFIRMADO_LECTURA.
     *
     * @param nuevoEstado nuevo estado de visibilidad
     * @return nueva instancia con el estado actualizado
     */
    public LabResult withEstadoVisibilidad(EstadoVisibilidad nuevoEstado) {
        Objects.requireNonNull(nuevoEstado, "El estado de visibilidad no puede ser nulo");
        return new LabResult(
                labId, patientId, tipoExamen, valoresAnaliticos,
                timestamp, alertaCritica, s3PathCompleto, resumenClinico,
                version, nuevoEstado, notificaciones
        );
    }

    /**
     * Crea un nuevo LabResult con una notificación adicional en el historial.
     *
     * <p>Usa una nueva lista inmutable que incluye todas las notificaciones
     * anteriores más la nueva, preservando el orden de inserción.
     *
     * @param nuevaNotificacion notificación a agregar al historial
     * @return nueva instancia con la notificación registrada
     */
    public LabResult withNotificacionAgregada(NotificacionEnviada nuevaNotificacion) {
        Objects.requireNonNull(nuevaNotificacion, "La notificación no puede ser nula");
        List<NotificacionEnviada> nuevaLista = new ArrayList<>(notificaciones);
        nuevaLista.add(nuevaNotificacion);
        return new LabResult(
                labId, patientId, tipoExamen, valoresAnaliticos,
                timestamp, alertaCritica, s3PathCompleto, resumenClinico,
                version, estadoVisibilidad, nuevaLista
        );
    }

    // ─── Factory methods ────────────────────────────────────────────────

    /**
     * Crea un nuevo LabResult con UUID generado automáticamente y versión inicial 0.
     *
     * <p>El estado de visibilidad inicia en PENDIENTE y sin notificaciones.
     * El resumen clínico se genera en un flujo posterior por IA.
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
                null,                           // resumenClinico: se genera después
                0L,                             // version inicial para OCC
                EstadoVisibilidad.PENDIENTE,    // estado inicial
                List.of()                       // sin notificaciones al crear
        );
    }
}