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
 *   crear() → PENDIENTE
 *   comoEnviada() → ENVIADA      (canal confirmó entrega)
 *   comoLeida() → LEIDA          (médico confirmó lectura)
 *   comoFallida() → FALLIDA      (entrega fallida definitiva)
 * </pre>
 *
 * <p>El campo {@code notificacionId} actúa como clave de idempotencia:
 * si el adaptador SQS reenvía el mismo mensaje, el servicio puede detectar
 * que la notificación ya fue registrada y no duplicarla.
 */
public record NotificacionEnviada(
        String notificacionId,
        String destinatario,
        NotificationChannel canal,
        NotificationStatus estado,
        Instant timestampEnvio,
        Instant timestampLectura  // nullable — solo presente cuando estado = LEIDA
) {

    /**
     * Constructor compacto con validaciones defensivas de dominio.
     */
    public NotificacionEnviada {
        Objects.requireNonNull(notificacionId, "notificacionId no puede ser nulo");
        if (notificacionId.isBlank()) {
            throw new IllegalArgumentException("notificacionId no puede estar vacío");
        }
        Objects.requireNonNull(destinatario, "destinatario no puede ser nulo");
        if (destinatario.isBlank()) {
            throw new IllegalArgumentException("destinatario no puede estar vacío");
        }
        Objects.requireNonNull(canal, "canal no puede ser nulo");
        Objects.requireNonNull(estado, "estado no puede ser nulo");
        Objects.requireNonNull(timestampEnvio, "timestampEnvio no puede ser nulo");
        // Invariante de negocio: lectura requiere timestamp
        if (estado == NotificationStatus.LEIDA && timestampLectura == null) {
            throw new IllegalArgumentException(
                    "timestampLectura es obligatorio cuando estado = LEIDA. " +
                    "Use comoLeida(Instant) para la transición correcta.");
        }
        // Invariante de negocio: estados sin lectura no deben tener timestamp de lectura
        if (estado != NotificationStatus.LEIDA && timestampLectura != null) {
            throw new IllegalArgumentException(
                    "timestampLectura solo es válido cuando estado = LEIDA. " +
                    "Estado actual: " + estado);
        }
    }

    // ─── Factory methods ────────────────────────────────────────────────

    /**
     * Crea una notificación recién encolada en estado PENDIENTE.
     *
     * @param destinatario ID del médico o personal destino (ej. "DR001")
     * @param canal         medio de comunicación seleccionado
     * @return instancia con UUID generado automáticamente y timestamp actual
     */
    public static NotificacionEnviada crear(String destinatario, NotificationChannel canal) {
        Objects.requireNonNull(destinatario, "destinatario no puede ser nulo");
        Objects.requireNonNull(canal, "canal no puede ser nulo");
        if (destinatario.isBlank()) {
            throw new IllegalArgumentException("destinatario no puede estar vacío");
        }
        return new NotificacionEnviada(
                UUID.randomUUID().toString(),
                destinatario,
                canal,
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
     * @return nueva instancia con estado ENVIADA
     */
    public NotificacionEnviada comoEnviada() {
        return new NotificacionEnviada(notificacionId, destinatario, canal,
                NotificationStatus.ENVIADA, timestampEnvio, null);
    }

    /**
     * Crea una nueva instancia marcada como LEIDA con auditoría de confirmación.
     *
     * @param timestampConfirmacion instante en que el médico confirmó la lectura
     * @return nueva instancia con estado LEIDA y timestamp de confirmación
     */
    public NotificacionEnviada comoLeida(Instant timestampConfirmacion) {
        Objects.requireNonNull(timestampConfirmacion, "timestampConfirmacion no puede ser nulo");
        return new NotificacionEnviada(
                notificacionId, destinatario, canal,
                NotificationStatus.LEIDA,
                timestampEnvio, timestampConfirmacion
        );
    }

    /**
     * Crea una nueva instancia marcada como FALLIDA.
     * No se reintenta automáticamente; requiere intervención manual o nuevo envío.
     *
     * @return nueva instancia con estado FALLIDA
     */
    public NotificacionEnviada comoFallida() {
        return new NotificacionEnviada(notificacionId, destinatario, canal,
                NotificationStatus.FALLIDA, timestampEnvio, timestampLectura);
    }
}