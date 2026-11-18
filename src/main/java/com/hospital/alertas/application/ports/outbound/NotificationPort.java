package com.hospital.alertas.application.ports.outbound;

import com.hospital.alertas.domain.model.SentNotification;
import com.hospital.alertas.domain.model.PatientId;

/**
 * Outbound port for sending clinical notifications
 * through external channels (SMS, Email, Push, In-App).
 *
 * <p>The infrastructure implementation ({@code SqsNotificationAdapter})
 * enqueues the message in SQS for asynchronous channel processing.
 * The application layer knows nothing about channel or broker details.
 *
 * <p><strong>Architectural constraint:</strong> This interface belongs to the
 * application layer. It is forbidden to import any AWS SDK
 * ({@code software.amazon.awssdk.*}) or Quarkus annotations.
 *
 * <p>The delivery model is "at-least-once" by SQS design.
 * Downstream consumers must be idempotent using
 * {@code notificationId} as deduplication key.
 */
public interface NotificationPort {

    /**
     * Enqueues a clinical notification for delivery via the configured channel.
     *
     * <p>The operation is synchronous from the use case perspective:
     * the virtual thread unmounts during SQS I/O (Loom compatible).
     *
     * @param patientId     patient identifier (for SQS routing)
     * @param labId         laboratory identifier to notify
     * @param notification  complete notification data to send
     * @throws RuntimeException if SQS enqueue fails due to infrastructure error
     */
    void enqueueNotification(PatientId patientId, String labId, SentNotification notification);
}