package com.hospital.alertas.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Read-only aggregate representing the complete clinical timeline of a patient.
 *
 * <p>Combines demographic data (METADATA) with laboratory result history (LAB#)
 * into a single domain object, optimized for complete record queries.
 *
 * <p>Built via {@link LabPersistencePort#findCompleteRecord(PatientId)}
 * which performs a single DynamoDB query by PK and filters by SK prefixes.
 */
public record PatientTimeline(
        PatientId patientId,
        String name,
        Instant dateOfBirth,
        String clinicalHistory,
        List<LabResult> labs
) {

    /**
     * Compact constructor with defensive validations.
     */
    public PatientTimeline {
        Objects.requireNonNull(patientId, "patientId cannot be null");
        // name, dateOfBirth, clinicalHistory may be null if METADATA doesn't exist yet
        if (labs == null) {
            throw new IllegalArgumentException("Laboratories list cannot be null");
        }
        labs = List.copyOf(labs); // defensive immutable copy
    }

    /**
     * Gets the most recent laboratory result.
     *
     * @return {@link Optional} with the latest lab by timestamp, or empty if no labs
     */
    public java.util.Optional<LabResult> getLatestLab() {
        return labs.stream()
                .max(java.util.Comparator.comparing(LabResult::timestamp));
    }

    /**
     * Gets all laboratories with critical alert.
     *
     * @return immutable list of laboratories with {@code criticalAlert == true}
     */
    public List<LabResult> getCriticalAlertLabs() {
        return labs.stream()
                .filter(LabResult::criticalAlert)
                .toList();
    }

    /**
     * Gets laboratories with a specific visibility status.
     *
     * @param status visibility status to filter by
     * @return immutable list of laboratories with the given status
     */
    public List<LabResult> getLabsByVisibilityStatus(VisibilityStatus status) {
        Objects.requireNonNull(status, "status cannot be null");
        return labs.stream()
                .filter(lab -> lab.visibilityStatus() == status)
                .toList();
    }

    /**
     * Checks if patient has demographic data registered.
     *
     * @return true if METADATA record exists with non-null name
     */
    public boolean hasDemographics() {
        return name != null && !name.isBlank();
    }
}