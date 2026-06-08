package com.hospital.alertas.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Value Object inmutable que representa una notificación clínica enviada
 * a un médico o personal de salud, con auditoría completa del ciclo de vida.
 *
 * <p>Se persiste como elemento de lista en el atributo {@code NotificacionesEnviadas}
 * dentro del ítem LAB# de DynamoDB (Single-Table inline). Garantiza trazabilidad
 * sin queries adicionales: 1 RCU cubre lab + todas sus notificaciones.
 *
 * <p>Modelo de ciclo de vida:
 * <pre>
 *   create() → PENDIENTE
 *   asSent() → ENVIADA      (canal confirmó entrega)
 *   asRead() → LEIDA        (médico confirmó lectura)
 *   asFailed() → FALLIDA    (entrega fallida definitiva)
 * </pre>
 *
 * <p>El campo {@code notificationId} actúa como clave de idempotencia:
 * si el adaptador SQS reenvía el mismo mensaje, el servicio puede detectar
 * que la notificación ya fue registrada y no duplicarla.
 */
public record SentNotification(
        String notificationId,
        String recipient,
        NotificationChannel channel,
        NotificationStatus status,
        Instant timestampSend,
        Instant timestampRead  // nullable — solo presente cuando status = LEIDA
) {

    /**
     * Constructor compacto con validaciones defensivas de dominio.
     */
    public SentNotification {
        Objects.requireNonNull(notificationId, "notificationId no puede ser nulo");
        if (notificationId.isBlank()) {
            throw new IllegalArgumentException("notificationId no puede estar vacío");
        }
        Objects.requireNonNull(recipient, "recipient no puede ser nulo");
        if (recipient.isBlank()) {
            throw new IllegalArgumentException("recipient no puede estar vacío");
        }
        Objects.requireNonNull(channel, "channel no puede ser nulo");
        Objects.requireNonNull(status, "status no puede ser nulo");
        Objects.requireNonNull(timestampSend, "timestampSend no puede ser nulo");
        // Invariante de negocio: lectura requiere timestamp
        if (status == NotificationStatus.LEIDA && timestampRead == null) {
            throw new IllegalArgumentException(
                    "timestampRead es obligatorio cuando status = LEIDA. " +
                    "Use asRead(Instant) para la transición correcta.");
        }
        // Invariante de negocio: estados sin lectura no deben tener timestamp de lectura
        if (status != NotificationStatus.LEIDA && timestampRead != null) {
            throw new IllegalArgumentException(
                    "timestampRead solo es válido cuando status = LEIDA. " +
                    "Estado actual: " + status);
        }
    }

    // ─── Factory methods ────────────────────────────────────────────────

    /**
     * Crea una notificación recién encolada en estado PENDIENTE.
     *
     * @param recipient ID del médico o personal destino (ej. "DR001")
     * @param channel   medio de comunicación seleccionado
     * @return instancia con UUID generado automáticamente y timestamp actual
     */
    public static SentNotification create(String recipient, NotificationChannel channel) {
        Objects.requireNonNull(recipient, "recipient no puede ser nulo");
        Objects.requireNonNull(channel, "channel no puede ser nulo");
        if (recipient.isBlank()) {
            throw new IllegalArgumentException("recipient no puede estar vacío");
        }
        return new SentNotification(
                UUID.randomUUID().toString(),
                recipient,
                channel,
                NotificationStatus.PENDIENTE,
                Instant.now(),
                null
        );
    }

    // ─── Métodos de transición de estado (devuelven nuevas instancias) ──

    /**
     * Crea una nueva instancia marcada como ENVIADA.
     * El canal externo confirmó la entrega del mensaje.
     *
     * @return nueva instancia con status ENVIADA
     */
    public SentNotification asSent() {
        return new SentNotification(notificationId, recipient, channel,
                NotificationStatus.ENVIADA, timestampSend, null);
    }

    /**
     * Crea una nueva instancia marcada como LEIDA con auditoría de confirmación.
     *
     * @param timestampConfirmacion instante en que el médico confirmó la lectura
     * @return nueva instancia con status LEIDA y timestamp de confirmación
     */
    public SentNotification asRead(Instant timestampConfirmacion) {
        Objects.requireNonNull(timestampConfirmacion, "timestampConfirmacion no puede ser nulo");
        return new SentNotification(
                notificationId, recipient, channel,
                NotificationStatus.LEIDA,
                timestampSend, timestampConfirmacion
        );
    }

    /**
     * Crea una nueva instancia marcada como FALLIDA.
     * No se reintenta automáticamente; requiere intervención manual o nuevo envío.
     *
     * @return nueva instancia con status FALLIDA
     */
    public SentNotification asFailed() {
        return new SentNotification(notificationId, recipient, channel,
                NotificationStatus.FALLIDA, timestampSend, timestampRead);
    }
}