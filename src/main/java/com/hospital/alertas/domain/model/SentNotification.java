package com.hospital.alertas.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable Value Object representing a clinical notification sent to a
 * physician or healthcare staff member, with complete lifecycle audit trail.
 *
 * <p>Persisted as a list element in the {@code SentNotifications} attribute
 * within the LAB# item in DynamoDB (Single-Table inline). Ensures traceability
 * without additional queries: 1 RCU covers lab + all its notifications.
 *
 * <p>Lifecycle model:
 * <pre>
 *   create() → PENDING
 *   asSent() → SENT         (channel confirmed delivery)
 *   asRead() → READ         (physician confirmed reading)
 *   asFailed() → FAILED     (definitive delivery failure)
 * </pre>
 *
 * <p>The {@code notificationId} field acts as an idempotency key:
 * if the SQS adapter re-delivers the same message, the service can detect
 * that the notification was already registered and avoid duplication.
 */
public record SentNotification(

        /** Unique identifier for this notification (UUID). */
        String notificationId,

        /** Destination identifier (e.g., physician ID "DR001", phone number, email). */
        String recipient,

        /** Communication channel used. */
        NotificationChannel channel,

        /** Current lifecycle status. */
        NotificationStatus status,

        /** Timestamp when notification was queued/sent. */
        Instant timestampSend,

        /** Timestamp when physician confirmed reading — only present when status = READ. */
        Instant timestampRead

) {

    /**
     * Compact constructor with defensive domain validations.
     */
    public SentNotification {
        Objects.requireNonNull(notificationId, "notificationId cannot be null");
        if (notificationId.isBlank()) {
            throw new IllegalArgumentException("notificationId cannot be blank");
        }
        Objects.requireNonNull(recipient, "recipient cannot be null");
        if (recipient.isBlank()) {
            throw new IllegalArgumentException("recipient cannot be blank");
        }
        Objects.requireNonNull(channel, "channel cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(timestampSend, "timestampSend cannot be null");
        // Business invariant: read requires timestamp
        if (status == NotificationStatus.READ && timestampRead == null) {
            throw new IllegalArgumentException(
                    "timestampRead is required when status = READ. " +
                    "Use asRead(Instant) for the correct transition.");
        }
        // Business invariant: non-read statuses must not have read timestamp
        if (status != NotificationStatus.READ && timestampRead != null) {
            throw new IllegalArgumentException(
                    "timestampRead is only valid when status = READ. " +
                    "Current status: " + status);
        }
    }

    // ─── Factory methods ────────────────────────────────────────────────

    /**
     * Creates a newly queued notification in PENDING status.
     *
     * @param recipient ID of the target physician or staff (e.g., "DR001")
     * @param channel   selected communication medium
     * @return instance with auto-generated UUID and current timestamp
     */
    public static SentNotification create(String recipient, NotificationChannel channel) {
        Objects.requireNonNull(recipient, "recipient cannot be null");
        Objects.requireNonNull(channel, "channel cannot be null");
        if (recipient.isBlank()) {
            throw new IllegalArgumentException("recipient cannot be blank");
        }
        return new SentNotification(
                UUID.randomUUID().toString(),
                recipient,
                channel,
                NotificationStatus.PENDING,
                Instant.now(),
                null
        );
    }

    // ─── State transition methods (return new instances) ────────────────

    /**
     * Creates a new instance marked as SENT.
     * The external channel confirmed message delivery.
     *
     * @return new instance with status SENT
     */
    public SentNotification asSent() {
        return new SentNotification(notificationId, recipient, channel,
                NotificationStatus.SENT, timestampSend, null);
    }

    /**
     * Creates a new instance marked as READ with confirmation audit.
     *
     * @param timestampConfirmation instant when physician confirmed reading
     * @return new instance with status READ and confirmation timestamp
     */
    public SentNotification asRead(Instant timestampConfirmation) {
        Objects.requireNonNull(timestampConfirmation, "timestampConfirmation cannot be null");
        return new SentNotification(
                notificationId, recipient, channel,
                NotificationStatus.READ,
                timestampSend, timestampConfirmation
        );
    }

    /**
     * Creates a new instance marked as FAILED.
     * Not retried automatically; requires manual intervention or new send.
     *
     * @return new instance with status FAILED
     */
    public SentNotification asFailed() {
        return new SentNotification(notificationId, recipient, channel,
                NotificationStatus.FAILED, timestampSend, timestampRead);
    }
}