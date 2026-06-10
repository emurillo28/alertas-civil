package com.hospital.alertas.application.service;

import com.hospital.alertas.domain.model.PatientId;
import java.util.Objects;

/**
 * Input command for the notify-lab-ready use case.
 * <p>
 * Encapsulates the minimum necessary data to trigger the notification of a
 * laboratory result to the corresponding channel (email, SMS, push, etc.).
 *
 * <p>Immutable by design (Java Record). Defensive validation in compact constructor.
 */
public record NotifyLabReadyCommand(

        /** Unique laboratory result identifier (not null, not blank). */
        String labId,

        /** Owner patient identifier (not null). */
        PatientId patientId,

        /** Notification channel to use (e.g: EMAIL, SMS, PUSH). Optional: if null, uses default channel. */
        String notificationChannel,

        /** Notification recipient (e.g: doctor's email, phone number). Optional: if null, resolved from configuration. */
        String recipient

) {

    /**
     * Constructor compact with defensive validations.
     * Guarantees data integrity before entering the domain.
     */
    public NotifyLabReadyCommand {
        Objects.requireNonNull(labId, "labId cannot be null");
        if (labId.isBlank()) {
            throw new IllegalArgumentException("labId cannot be blank");
        }
        Objects.requireNonNull(patientId, "patientId cannot be null");
        // notificationChannel and recipient can be null (resolved in the output adapter)
        if (recipient != null && recipient.isBlank()) {
            throw new IllegalArgumentException("recipient cannot be blank if provided");
        }
    }
}