package com.hospital.alertas.domain.model;

/**
 * Estado del ciclo de vida de una {@link NotificacionEnviada} individual.
 *
 * <p>Flujo esperado:
 * <pre>
 *   PENDIENTE → ENVIADA → LEIDA
 *                       ↘ FALLIDA
 * </pre>
 *
 * <p>Las transiciones inválidas (ej. LEIDA → PENDIENTE) deben ser
 * rechazadas por la lógica de negocio en el dominio, no por el adaptador.
 */
public enum NotificationStatus {

    /** Notificación encolada en SQS, aún no procesada por el canal de entrega. */
    PENDIENTE,

    /** El canal externo confirmó la entrega (SMS enviado, email aceptado, push recibido). */
    ENVIADA,

    /** El destinatario confirmó la lectura explícitamente desde la aplicación. */
    LEIDA,

    /**
     * El canal externo reportó fallo definitivo.
     * Ejemplos: número telefónico inválido, email rebotado (hard bounce),
     * token push expirado.
     * No se reintenta automáticamente.
     */
    FALLIDA
}