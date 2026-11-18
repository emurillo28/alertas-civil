package com.hospital.alertas.infrastructure.adapters.inbound;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST DTO wrapping the complete patient clinical record.
 *
 * <p>Returned by {@code GET /labs/patients/{patientId}/history}.
 */
public record PatientTimelineResponse(
        String patientId,
        String name,
        String dateOfBirth,
        String clinicalHistory,
        List<LabResultDto> labs
) {
}
