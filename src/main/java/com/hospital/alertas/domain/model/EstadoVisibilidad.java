package com.hospital.alertas.domain.model;

/**
 * Estado del ciclo de vida de visibilidad de un resultado de laboratorio
 * desde la perspectiva del clínico destinatario.
 *
 * <p>Flujo de estados:
 * <pre>
 *   PENDIENTE → NOTIFICADO → CONFIRMADO_LECTURA
 * </pre>
 *
 * <p>Un laboratorio inicia en {@code PENDIENTE} al ser ingresado.
 * Cuando se dispara el envío de notificación, transiciona a {@code NOTIFICADO}.
 * Cuando el médico confirma la lectura, transiciona a {@code CONFIRMADO_LECTURA}.
 */
public enum EstadoVisibilidad {

    /**
     * Laboratorio ingresado, pendiente de notificación al clínico responsable.
     * Estado inicial al crear un {@link LabResult}.
     */
    PENDIENTE,

    /**
     * Notificación enviada (encolada en SQS para entrega por canal externo).
     * El laboratorio ha sido notificado pero no confirmada la lectura.
     */
    NOTIFICADO,

    /**
     * El médico destinatario confirmó explícitamente la lectura del resultado.
     * Estado terminal del ciclo de visibilidad.
     */
    CONFIRMADO_LECTURA
}