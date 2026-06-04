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

    /**
     * Constructor con inyección de propiedades.
     *
     * @param environment perfil activo de la aplicación (dev, staging, prod)
     */
    public CloudWatchEmfMetrics(
            @ConfigProperty(name = "quarkus.profile", defaultValue = "dev") String environment
    ) {
        this.environment = environment;
    }

    /**
     * Registra un procesamiento de ingesta exitoso.
     *
     * @param durationSeconds tiempo de procesamiento de la ingesta en segundos
     */
    public void logSuccess(double durationSeconds) {
        logEmf(1, 1, 0, 0, durationSeconds);
    }

    /**
     * Registra un error de validación médica (error sintáctico/semántico del cliente).
     */
    public void logValidationError() {
        logEmf(1, 0, 1, 0, 0.0);
    }

    /**
     * Registra un error de infraestructura (fallos de conexión, base de datos caída).
     */
    public void logInfrastructureError() {
        logEmf(1, 0, 0, 1, 0.0);
    }

    /**
     * Construye y escribe en stdout la estructura EMF requerida por CloudWatch.
     */
    private void logEmf(long total, long success, long validationErr, long infraErr, double duration) {
        String emfJson = "{"
                + "\"_aws\":{"
                + "\"Timestamp\":" + Instant.now().toEpochMilli() + ","
                + "\"CloudWatchMetrics\":[{"
                + "\"Namespace\":\"hc-alertas\","
                + "\"Dimensions\":[[\"Environment\"]],"
                + "\"Metrics\":["
                + "{\"Name\":\"medical_alerts_ingest_total\",\"Unit\":\"Count\"},"
                + "{\"Name\":\"medical_alerts_ingest_success_total\",\"Unit\":\"Count\"},"
                + "{\"Name\":\"medical_alerts_validation_error_total\",\"Unit\":\"Count\"},"
                + "{\"Name\":\"medical_alerts_infrastructure_error_total\",\"Unit\":\"Count\"},"
                + "{\"Name\":\"medical_alerts_processing_duration_seconds\",\"Unit\":\"Seconds\"}"
                + "]"
                + "}]"
                + "},"
                + "\"Environment\":\"" + environment + "\","
                + "\"medical_alerts_ingest_total\":" + total + ","
                + "\"medical_alerts_ingest_success_total\":" + success + ","
                + "\"medical_alerts_validation_error_total\":" + validationErr + ","
                + "\"medical_alerts_infrastructure_error_total\":" + infraErr + ","
                + "\"medical_alerts_processing_duration_seconds\":" + duration
                + "}";

        System.out.println(emfJson);
    }
}
