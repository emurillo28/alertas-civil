package com.hospital.alertas.infrastructure.adapters.inbound;

import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.PatientId;
import com.hospital.alertas.domain.model.VisibilityStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO (Data Transfer Object) for laboratory ingestion payload
 * received from API Gateway / REST client.
 *
 * <p>Acts as anti-corruption layer between the outside world (JSON HTTP)
 * and the domain model ({@link LabResult}). Allows JSON schema
 * to evolve independently from the domain.
 *
 * <p>Example JSON payload:
 * <pre>{@code
 * {
 *   "patientId": "PAC001",
 *   "testType": "Complete Blood Count",
 *   "analyticValues": {
 *     "Hemoglobin": "14.5 g/dL",
 *     "Leukocytes": "7500 /uL"
 *   },
 *   "s3Path": "s3://hc-laboratories/2026/06/01/PAC001/BH-001.pdf",
 *   "timestamp": "2026-06-01T10:30:00Z"
 * }
 * }</pre>
 */
public record IngestLabRequest(
        String labId,
        String patientId,
        String testType,
        Map<String, String> analyticValues,
        String timestamp,
        Boolean criticalAlert,
        String s3Path
) {

    /**
     * Converts the API DTO to the domain model.
     *
     * <p>Applies default values for optional fields:
     * <ul>
     *   <li>{@code labId}: random UUID if not provided</li>
     *   <li>{@code timestamp}: {@code Instant.now()} if not provided</li>
     *   <li>{@code criticalAlert}: {@code false} (service will evaluate)</li>
     * </ul>
     *
     * @return instance of {@link LabResult} with version 0 (new) and no clinical summary
     */
    public LabResult toDomain() {
        return new LabResult(
                labId != null && !labId.isBlank() ? labId : UUID.randomUUID().toString(),
                new PatientId(patientId),
                testType,
                analyticValues != null ? analyticValues : Map.of(),
                timestamp != null ? Instant.parse(timestamp) : Instant.now(),
                criticalAlert != null ? criticalAlert : false,
                s3Path,
                null,   // clinicalSummary — generated in a subsequent flow
                0L,     // initial version for OCC
                VisibilityStatus.PENDING,    // initial status
                List.of()                    // no notifications at creation
        );
    }
}