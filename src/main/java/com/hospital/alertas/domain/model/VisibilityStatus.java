package com.hospital.alertas.domain.model;

/**
 * Visibility lifecycle state of a lab result from the perspective of the
 * recipient clinician.
 *
 * <p>State flow:
 * <pre>
 *   PENDING → NOTIFIED → READ_CONFIRMED
 * </pre>
 *
 * <p>A lab starts in {@code PENDING} when ingested.
 * When notification is triggered, it transitions to {@code NOTIFIED}.
 * When the physician confirms reading, it transitions to {@code READ_CONFIRMED}.
 */
public enum VisibilityStatus {

    /**
     * Lab result ingested, pending notification to the responsible clinician.
     * Initial state when creating a {@link LabResult}.
     */
    PENDING,

    /**
     * Notification sent (queued in SQS for delivery via external channel).
     * The lab has been notified but reading not yet confirmed.
     */
    NOTIFIED,

    /**
     * The recipient physician explicitly confirmed reading the result.
     * Terminal state of the visibility lifecycle.
     */
    READ_CONFIRMED
}