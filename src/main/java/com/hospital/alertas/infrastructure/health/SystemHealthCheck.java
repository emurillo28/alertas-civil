package com.hospital.alertas.infrastructure.health;

import com.hospital.alertas.application.ports.outbound.LabPersistencePort;
import com.hospital.alertas.domain.model.PatientId;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

import java.time.Instant;

/**
 * Health checks for the Lambda application.
 *
 * <p>
 * Liveness: Basic check that the application is running (always passes if JVM
 * is up).
 * Readiness: Checks that downstream dependencies (DynamoDB) are reachable.
 *
 * <p>
 * Used by:
 * <ul>
 * <li>AWS ALB / API Gateway health checks</li>
 * <li>Kubernetes liveness/readiness probes (if deployed to K8s)</li>
 * <li>LocalStack Dev Services readiness detection</li>
 * </ul>
 */
@Liveness
@Readiness
@ApplicationScoped
public class SystemHealthCheck implements HealthCheck {

    private final LabPersistencePort persistencePort;

    public SystemHealthCheck(LabPersistencePort persistencePort) {
        this.persistencePort = persistencePort;
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("system-liveness")
                .withData("timestamp", Instant.now().toString())
                .withData("version", "0.1.0-SNAPSHOT")
                .up()
                .build();
    }

    /**
     * Readiness check - verifies DynamoDB is reachable.
     * Called by @Readiness annotation automatically in Quarkus 3.x.
     */
    public HealthCheckResponse callReadiness() {
        try {
            // Quick DynamoDB connectivity check using a dummy patient ID
            // This validates the AWS SDK client can reach the endpoint
            String testPatient = "health-check-" + System.currentTimeMillis();
            persistencePort.findLatestByPatient(new PatientId(testPatient));

            return HealthCheckResponse.named("system-readiness")
                    .withData("timestamp", Instant.now().toString())
                    .withData("dynamodb", "reachable")
                    .up()
                    .build();
        } catch (Exception e) {
            return HealthCheckResponse.named("system-readiness")
                    .withData("timestamp", Instant.now().toString())
                    .withData("dynamodb", "unreachable")
                    .withData("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .down()
                    .build();
        }
    }
}