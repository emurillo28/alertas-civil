package com.hospital.alertas.infrastructure.adapters.inbound;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST DTO for a single laboratory result.
 *
 * <p>This is an anti-corruption layer between the HTTP contract
 * and the domain model. It intentionally omits internal fields
 * (version, PK, SK, s3Path) that are implementation details
 * of the persistence layer and must not leak to API consumers.
 */
public record LabResultDto(
        String labId,
        String patientId,
        String testType,
        Map<String, String> analyticValues,
        String timestamp,
        boolean criticalAlert,
        String clinicalSummary,
        String visibilityStatus,
        List<SentNotificationDto> notifications
) {

    /**
     * Converts a {@link LabResultDto} to a plain response form
     * omitting the notifications list when it is empty.
     */
    public LabResultDto withEmptyNotificationsIfAbsent() {
        if (notifications == null || notifications.isEmpty()) {
            return new LabResultDto(
                    labId, patientId, testType, analyticValues,
                    timestamp, criticalAlert, clinicalSummary,
                    visibilityStatus, List.of()
            );
        }
        return this;
    }
}
