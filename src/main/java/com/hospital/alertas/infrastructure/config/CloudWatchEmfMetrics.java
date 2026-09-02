package com.hospital.alertas.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

/**
 * Utilidad de registro de métricas nativas para AWS Lambda mediante
 * el formato EMF (Embedded Metric Format) de CloudWatch.
 *
 * <p>Esta clase escribe un JSON estructurado directamente en {@code System.out}.
 * El agente de CloudWatch Logs intercepta la salida de consola de forma asíncrona
 * y extrae las métricas sin retrasar el hilo de procesamiento de la Lambda.
 *
 * <p>Esto evita la necesidad de configurar un endpoint HTTP expuesto (Prometheus)
 * o realizar llamadas de red síncronas bloqueantes (push metrics) en entornos Serverless.
 */
@ApplicationScoped
public class CloudWatchEmfMetrics {

    private final String environment;
    private final String namespace;

    /** Constructor con inyección de propiedades. */
    public CloudWatchEmfMetrics(
            @ConfigProperty(name = "quarkus.profile", defaultValue = "dev") String environment,
            @ConfigProperty(name = "app.metrics.emf.namespace", defaultValue = "hc-alertas") String namespace
    ) {
        this.environment = environment;
        this.namespace = namespace;
    }

    public void logSuccess(double durationSeconds) {
        logEmf(1, 1, 0, 0, 0, 0, 0, durationSeconds);
    }

    public void logCriticalAlertTriggered() {
        logEmf(1, 0, 0, 0, 1, 0, 0, 0.0);
    }

    public void logValidationError() {
        logEmf(1, 0, 1, 0, 0, 0, 0, 0.0);
    }

    public void logInfrastructureError() {
        logEmf(1, 0, 0, 1, 0, 0, 0, 0.0);
    }

    public void logOccConflict() {
        logEmf(1, 0, 0, 0, 0, 1, 0, 0.0);
    }

    public void logIdempotentDuplicateAbsorbed() {
        logEmf(1, 0, 0, 0, 0, 0, 1, 0.0);
    }

    public void logSqsBatchProcessed(int batchSize) {
        logEmf(batchSize, 0, 0, 0, 0, 0, 0, 0.0);
    }

    /**
     * Construye y escribe en stdout la estructura EMF requerida por CloudWatch.
     */
    private void logEmf(long total, long success, long validationErr, long infraErr,
                        long criticalAlerts, long occConflicts, long idempotentDuplicates, double duration) {
        String emfJson = "{"
                + "\"_aws\":{"
                + "\"Timestamp\":" + Instant.now().toEpochMilli() + ","
                + "\"CloudWatchMetrics\":[{"
                + "\"Namespace\":\"" + namespace + "\","
                + "\"Dimensions\":[[\"Environment\"]],"
                + "\"Metrics\":["
                + "{\"Name\":\"medical_alerts_ingest_total\",\"Unit\":\"Count\"},"
                + "{\"Name\":\"medical_alerts_ingest_success_total\",\"Unit\":\"Count\"},"
                + "{\"Name\":\"medical_alerts_validation_error_total\",\"Unit\":\"Count\"},"
                + "{\"Name\":\"medical_alerts_infrastructure_error_total\",\"Unit\":\"Count\"},"
                + "{\"Name\":\"medical_alerts_critical_alert_total\",\"Unit\":\"Count\"},"
                + "{\"Name\":\"medical_alerts_occ_conflict_total\",\"Unit\":\"Count\"},"
                + "{\"Name\":\"medical_alerts_idempotent_duplicate_total\",\"Unit\":\"Count\"},"
                + "{\"Name\":\"medical_alerts_processing_duration_seconds\",\"Unit\":\"Seconds\"}"
                + "]}"
                + "]"
                + "},"
                + "\"Environment\":\"" + environment + "\","
                + "\"medical_alerts_ingest_total\":" + total + ","
                + "\"medical_alerts_ingest_success_total\":" + success + ","
                + "\"medical_alerts_validation_error_total\":" + validationErr + ","
                + "\"medical_alerts_infrastructure_error_total\":" + infraErr + ","
                + "\"medical_alerts_critical_alert_total\":" + criticalAlerts + ","
                + "\"medical_alerts_occ_conflict_total\":" + occConflicts + ","
                + "\"medical_alerts_idempotent_duplicate_total\":" + idempotentDuplicates + ","
                + "\"idempotent_duplicate_absorbed\":" + (idempotentDuplicates > 0 ? 1 : 0) + ","
                + "\"medical_alerts_processing_duration_seconds\":" + duration
                + "}";

        System.out.println(emfJson);
    }
}