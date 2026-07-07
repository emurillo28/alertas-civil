package com.hospital.alertas.infrastructure.adapters.inbound;

import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.PatientId;
import com.hospital.alertas.domain.model.PatientTimeline;
import com.hospital.alertas.domain.model.SentNotification;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Stateless mapper between domain models and REST DTOs.
 *
 * <p>Converts internal domain representations (which carry DDB keys,
 * version numbers, and framework details) into clean API contracts
 * that are safe to expose to mobile clients.
 */
public final class LabResultMapper {

    private LabResultMapper() {
    }

    /**
     * Maps a {@link LabResult} domain record to a REST DTO.
     *
     * <p>Excluded fields: PK, SK, s3Path, version — implementation
     * details of the persistence layer that must not reach the UI.
     */
    public static LabResultDto toDto(LabResult lab) {
        List<SentNotificationDto> notificationDtos = lab.notifications().stream()
                .map(LabResultMapper::toNotificationDto)
                .toList();

        return new LabResultDto(
                lab.labId(),
                lab.patientId().value(),
                lab.testType(),
                Map.copyOf(lab.analyticValues()),
                lab.timestamp().toString(),
                lab.criticalAlert(),
                lab.clinicalSummary(),
                lab.visibilityStatus().name(),
                notificationDtos
        );
    }

    /**
     * Maps a {@link com.hospital.alertas.domain.model.SentNotification}
     * to its REST DTO form.
     */
    public static SentNotificationDto toNotificationDto(SentNotification n) {
        return new SentNotificationDto(
                n.notificationId(),
                n.recipient(),
                n.channel().name(),
                n.status().name(),
                n.timestampSend().toString(),
                n.timestampRead() != null ? n.timestampRead().toString() : null
        );
    }

    /**
     * Maps a {@link PatientTimeline} to the REST response.
     *
     * <p>Demographic fields may be null when the METADATA record
     * does not yet exist; they are serialised as JSON null.
     * Empty lab lists return as empty arrays (not null).
     */
    public static PatientTimelineResponse toTimelineResponse(PatientTimeline timeline) {
        List<LabResultDto> labDtos = timeline.labs().stream()
                .sorted((a, b) -> b.timestamp().compareTo(a.timestamp()))
                .map(LabResultMapper::toDto)
                .toList();

        return new PatientTimelineResponse(
                timeline.patientId().value(),
                timeline.name(),
                timeline.dateOfBirth() != null ? timeline.dateOfBirth().toString() : null,
                timeline.clinicalHistory(),
                labDtos
        );
    }
}
