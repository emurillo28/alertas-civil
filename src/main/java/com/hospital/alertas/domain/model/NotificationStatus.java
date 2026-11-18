package com.hospital.alertas.domain.model;

/**
 * Lifecycle status of an individual {@link SentNotification}.
 *
 * <p>Expected flow:
 * <pre>
 *   PENDING → SENT → READ
 *                 ↘ FAILED
 * </pre>
 *
 * <p>Invalid transitions (e.g., READ → PENDING) must be
 * rejected by domain business logic, not by the adapter.
 */
public enum NotificationStatus {

    /** Notification queued in SQS, not yet processed by the delivery channel. */
    PENDING,

    /** The external channel confirmed delivery (SMS sent, email accepted, push received). */
    SENT,

    /** The recipient explicitly confirmed reading from the application. */
    READ,

    /**
     * The external channel reported definitive failure.
     * Examples: invalid phone number, email hard bounce, expired push token.
     * Not retried automatically.
     */
    FAILED
}