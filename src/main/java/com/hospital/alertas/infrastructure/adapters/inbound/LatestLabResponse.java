package com.hospital.alertas.infrastructure.adapters.inbound;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST DTO wrapping the most recent laboratory for a patient.
 *
 * <p>Returned by {@code GET /labs/patients/{patientId}/latest}.
 */
public record LatestLabResponse(
        String patientId,
        LabResultDto lab
) {
}
