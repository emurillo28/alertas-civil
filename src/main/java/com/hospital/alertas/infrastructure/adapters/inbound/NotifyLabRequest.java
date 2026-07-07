package com.hospital.alertas.infrastructure.adapters.inbound;

/**
 * Request DTO for the notify-lab-ready endpoint.
 *
 * <p>Acceptable via {@code POST /labs/{labId}/notify}.
 * Only the two identifiers needed by {@code NotifyLabReadyUseCase}
 * are required; the channel defaults to {@code IN_APP} server-side.
 */
public record NotifyLabRequest(
        String patientId,
        String recipient
) {
}
