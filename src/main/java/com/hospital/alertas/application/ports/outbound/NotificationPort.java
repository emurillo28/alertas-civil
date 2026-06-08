package com.hospital.alertas.application.ports.outbound;

import com.hospital.alertas.domain.model.SentNotification;
import com.hospital.alertas.domain.model.PatientId;

/**
 * Puerto de salida (outbound) para el envío de notificaciones clínicas
 * a través de canales externos (SMS, Email, Push, In-App).
 *
 * <p>La implementación de infraestructura ({@code SqsNotificationAdapter})
 * encola el mensaje en SQS para procesamiento asíncrono por canal.
 * La capa de aplicación no conoce los detalles del canal ni del broker.
 *
 * <p><strong>Restricción arquitectónica:</strong> Esta interfaz pertenece a la capa
 * de aplicación. Tiene prohibido importar cualquier SDK de AWS
 * ({@code software.amazon.awssdk.*}) o anotaciones de Quarkus.
 *
 * <p>El modelo de entrega es "at-least-once" por diseño de SQS.
 * Los consumidores downstream deben ser idempotentes usando
 * {@code notificacionId} como clave de deduplicación.
 */
public interface NotificationPort {

    /**
     * Encola una notificación clínica para su entrega por el canal configurado.
     *
     * <p>La operación es síncrona desde la perspectiva del caso de uso:
     * el hilo virtual se desmonta durante el I/O de SQS (Loom compatible).
     *
     * @param patientId     identificador del paciente (para routing en SQS)
     * @param labId         identificador del laboratorio a notificar
     * @param notificacion  datos completos de la notificación a enviar
     * @throws RuntimeException si el encolado en SQS falla por error de infraestructura
     */
    void encolarNotificacion(PatientId patientId, String labId, SentNotification notificacion);
}