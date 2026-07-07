package com.hospital.alertas.infrastructure.adapters.inbound;

import com.hospital.alertas.application.ports.inbound.IngestLabUseCase;
import com.hospital.alertas.application.ports.inbound.NotifyLabReadyUseCase;
import com.hospital.alertas.application.ports.outbound.LabPersistencePort;
import com.hospital.alertas.domain.exception.LabValidationException;
import com.hospital.alertas.domain.model.NotificationChannel;
import com.hospital.alertas.domain.model.PatientId;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * LabResultResource - Quarkus JAX-RS adapter for backend inbound endpoints.
 *
 * <p>Read path endpoints are exposed directly here and should be routed through API Gateway in production.
 * Write path for lab ingestion currently bypasses this REST resource (UI -> API Gateway -> SQS direct integration).
 */
@Path("/labs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LabResultResource {

    private final IngestLabUseCase ingestLabUseCase;
    private final NotifyLabReadyUseCase notifyLabReadyUseCase;
    private final LabPersistencePort labPersistencePort;

    @Inject
    public LabResultResource(
            IngestLabUseCase ingestLabUseCase,
            NotifyLabReadyUseCase notifyLabReadyUseCase,
            LabPersistencePort labPersistencePort) {
        this.ingestLabUseCase = ingestLabUseCase;
        this.notifyLabReadyUseCase = notifyLabReadyUseCase;
        this.labPersistencePort = labPersistencePort;
    }

    /**
     * Patient directory stub endpoint.
     *
     * <p>Returns seeded identifiers for local development so the frontend can test read-side
     * routing through API Gateway without depending on a separate Patient service.
     * Production should replace this with an injected patient lookup port behind the same gateway auth.
     */
    @GET
    @Path("/patients")
    public java.util.List<PatientId> getPatients() {
        return java.util.List.of(
                new PatientId("P102"),
                new PatientId("P103"),
                new PatientId("P104")
        );
    }

    // ─── Read Endpoints ──────────────────────────────────────────────────────

    /**
     * Returns the most recent laboratory result for a patient.
     *
     * <p>HTTP 200 with the lab wrapped in {@link LatestLabResponse} when found.
     * HTTP 404 when the patient has no registered laboratories.
     * HTTP 400 when the patientId format is invalid (invalid UUID / alphanumeric).
     */
    @GET
    @Path("/patients/{patientId}/latest")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getLatestLab(@PathParam("patientId") String patientId) {
        try {
            PatientId pid = new PatientId(patientId);
            return labPersistencePort.findLatestByPatient(pid)
                    .map(lab -> Response.ok(new LatestLabResponse(
                            pid.value(),
                            LabResultMapper.toDto(lab)
                    )).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiErrorResponse("INVALID_PATIENT_ID", e.getMessage()))
                    .build();
        }
    }

    /**
     * Returns the complete clinical record for a patient, including
     * demographics (when available) and full laboratory history.
     *
     * <p>HTTP 200 with {@link PatientTimelineResponse} when the patient exists
     * (labs list will be empty array if no results yet).
     * HTTP 404 when the patient record does not exist at all (no METADATA and no labs).
     * HTTP 400 when the patientId format is invalid.
     */
    @GET
    @Path("/patients/{patientId}/history")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPatientHistory(@PathParam("patientId") String patientId) {
        try {
            PatientId pid = new PatientId(patientId);
            return labPersistencePort.findCompleteRecord(pid)
                    .map(timeline -> Response.ok(
                            LabResultMapper.toTimelineResponse(timeline)
                    ).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiErrorResponse("INVALID_PATIENT_ID", e.getMessage()))
                    .build();
        }
    }

    // ─── Notify Endpoint ─────────────────────────────────────────────────────

    /**
     * Triggers the notification flow for a specific laboratory result.
     *
     * <p>HTTP 200 on success (notification registered and enqueued).
     * HTTP 404 when the lab does not exist for the given patient.
     * HTTP 400 when the request body is invalid.
     */
    @POST
    @Path("/labs/{labId}/notify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response notifyLabReady(
            @PathParam("labId") String labId,
            NotifyLabRequest request) {
        try {
            notifyLabReadyUseCase.execute(
                    new com.hospital.alertas.application.service.NotifyLabReadyCommand(
                            labId,
                            request.patientId(),
                            request.recipient(),
                            NotificationChannel.IN_APP
                    )
            );
            return Response.ok()
                    .entity(new NotifyResponse(labId, "NOTIFIED"))
                    .build();
        } catch (LabValidationException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ApiErrorResponse("LAB_NOT_FOUND", e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiErrorResponse("INVALID_REQUEST", e.getMessage()))
                    .build();
        }
    }

    // ─── Inner DTOs ──────────────────────────────────────────────────────────

    /** Minimal error envelope returned on 400/404/503. */
    public record ApiErrorResponse(String error, String message) {
    }

    /** Success envelope for the notify endpoint. */
    public record NotifyResponse(String labId, String status) {
    }
}
