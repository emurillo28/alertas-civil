package com.hospital.alertas.infrastructure.adapters.inbound;

import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.PatientId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTO (Data Transfer Object) para el payload de ingesta de laboratorios
 * recibido desde el API Gateway / cliente REST.
 *
 * <p>Actúa como anti-corruption layer entre el mundo exterior (JSON HTTP)
 * y el modelo de dominio ({@link LabResult}). Permite que el esquema JSON
 * evolucione independientemente del dominio.
 *
 * <p>Ejemplo de payload JSON:
 * <pre>{@code
 * {
 *   "patientId": "PAC001",
 *   "tipoExamen": "Biometria Hematica",
 *   "valoresAnaliticos": {
 *     "Hemoglobina": "14.5 g/dL",
 *     "Leucocitos": "7500 /uL"
 *   },
 *   "s3PathCompleto": "s3://hc-laboratorios/2026/06/01/PAC001/BH-001.pdf",
 *   "timestamp": "2026-06-01T10:30:00Z"
 * }
 * }</pre>
 */
public record IngestLabRequest(
        String labId,
        String patientId,
        String tipoExamen,
        Map<String, String> valoresAnaliticos,
        String timestamp,
        Boolean alertaCritica,
        String s3PathCompleto
) {

    /**
     * Convierte el DTO del API al modelo de dominio.
     *
     * <p>Aplica valores por defecto para campos opcionales:
     * <ul>
     *   <li>{@code labId}: UUID aleatorio si no se proporciona</li>
     *   <li>{@code timestamp}: {@code Instant.now()} si no se proporciona</li>
     *   <li>{@code alertaCritica}: {@code false} (el servicio lo evaluará)</li>
     * </ul>
     *
     * @return instancia de {@link LabResult} con versión 0 (nuevo) y sin resumen clínico
     */
    public LabResult toDomain() {
        return new LabResult(
                labId != null && !labId.isBlank() ? labId : UUID.randomUUID().toString(),
                new PatientId(patientId),
                tipoExamen,
                valoresAnaliticos != null ? valoresAnaliticos : Map.of(),
                timestamp != null ? Instant.parse(timestamp) : Instant.now(),
                alertaCritica != null ? alertaCritica : false,
                s3PathCompleto,
                null,   // resumenClinico — se genera en un flujo posterior
                0L      // versión inicial para OCC
        );
    }
}
