package com.hospital.alertas.application.service;

import com.hospital.alertas.application.ports.inbound.IngestLabUseCase;
import com.hospital.alertas.application.ports.outbound.LabPersistencePort;
import com.hospital.alertas.domain.exception.LabValidationException;
import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.infrastructure.config.CloudWatchEmfMetrics;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrator for the clinical laboratory ingestion use case.
 *
 * <p>Implements sequential synchronous business logic:
 * <ol>
 *   <li>Defensive validation of mandatory fields.</li>
 *   <li>Automatic critical alert threshold evaluation.</li>
 *   <li>Persistence of processed result.</li>
 * </ol>
 *
 * <p>Concurrency is managed externally by Project Loom (virtual threads)
 * in the infrastructure layer. This service is 100% imperative and blocking.
 *
 * <p><strong>Architectural constraint:</strong> forbidden to import
 * {@code software.amazon.awssdk.*} or use reactive APIs (Mutiny, Uni, Multi).
 */
@ApplicationScoped
public class IngestLabService implements IngestLabUseCase {

    /** Pattern to extract the numeric part (including decimal and negative sign)
     * at the beginning of an analytical value. Example: "14.5 g/dL" → 14.5 */
    private static final Pattern NUMERIC_PREFIX = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)");

    /** Critical thresholds for clinical analytes.
     * Each entry defines: analyte name (lowercase), minimum threshold, maximum threshold.
     * If the value is OUTSIDE the range [min, max], the critical alert is activated.
     *
     * <p>Clinical reference: standard panic values from hospital laboratory.
     */
    private static final Map<String, CriticalThreshold> CRITICAL_THRESHOLDS = Map.of(
            "hemoglobina",  new CriticalThreshold(7.0,   20.0),
            "potasio",      new CriticalThreshold(2.5,    6.0),
            "glucosa",      new CriticalThreshold(50.0, 400.0),
            "sodio",        new CriticalThreshold(120.0, 160.0),
            "creatinina",   new CriticalThreshold(0.0,   10.0),
            "plaquetas",    new CriticalThreshold(50.0, 1000.0)
    );

    private final LabPersistencePort persistencePort;
    private final CloudWatchEmfMetrics metrics;

    /**
     * Constructor for standard CDI injection.
     *
     * @param persistencePort persistence port (injected by Quarkus/CDI)
     * @param metrics         CloudWatch EMF metrics
     */
    public IngestLabService(LabPersistencePort persistencePort, CloudWatchEmfMetrics metrics) {
        this.persistencePort = Objects.requireNonNull(persistencePort,
                "LabPersistencePort cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "CloudWatchEmfMetrics cannot be null");
    }

    @Override
    public void execute(LabResult command) {
        validateMandatoryFields(command);

        LabResult labToSave = evaluateCriticalAlerts(command);

        persistencePort.saveLab(labToSave);

        // Log success metric
        metrics.logSuccess(0.0); // Duration not tracked here, handler does it
        if (labToSave.criticalAlert()) {
            metrics.logCriticalAlertTriggered();
        }
    }

    // ─── Mandatory field validation ───────────────────────────────

    /**
     * Validates that mandatory clinical fields of LabResult are not null or empty.
     * Record compact constructor validations already cover nulls on construction,
     * but this additional layer protects against corrupt data from input adapters
     * that could deserialize malformed JSON.
     *
     * @throws LabValidationException if any mandatory field is invalid
     */
    private void validateMandatoryFields(LabResult lab) {
        if (lab == null) {
            throw new LabValidationException("Lab result cannot be null");
        }
        if (lab.patientId() == null) {
            throw new LabValidationException("patientId", "is required for ingestion");
        }
        if (lab.testType() == null || lab.testType().isBlank()) {
            throw new LabValidationException("testType", "is required and cannot be blank");
        }
        if (lab.analyticValues() == null || lab.analyticValues().isEmpty()) {
            throw new LabValidationException("analyticValues",
                    "must contain at least one analytical result");
        }
        if (lab.timestamp() == null) {
            throw new LabValidationException("timestamp", "is required for clinical traceability");
        }
        if (lab.s3Path() == null || lab.s3Path().isBlank()) {
            throw new LabValidationException("s3Path",
                    "is required: original PDF must be stored in S3");
        }
    }

    // ─── Critical alert threshold evaluation ───────────────────────

    /**
     * Evaluates analytical values against clinical danger thresholds.
     * If ANY analyte exceeds its threshold, returns a new instance
     * with {@code criticalAlert = true}. If none exceed, returns the original lab.
     *
     * <p>Analyte name comparison is case-insensitive to tolerate
     * variations in output from different laboratory equipment.
     *
     * @param lab laboratory result to evaluate
     * @return original lab or new instance with critical alert activated
     */
    LabResult evaluateCriticalAlerts(LabResult lab) {
        // If already marked as critical alert, respect upstream decision
        if (lab.criticalAlert()) {
            return lab;
        }

        for (var entry : lab.analyticValues().entrySet()) {
            String analyteKey = entry.getKey().toLowerCase().trim();
            CriticalThreshold threshold = CRITICAL_THRESHOLDS.get(analyteKey);

            if (threshold == null) {
                continue; // Analyte without defined threshold, not evaluable
            }

            double numericValue = extractNumericValue(entry.getKey(), entry.getValue());

            if (numericValue < threshold.min() || numericValue > threshold.max()) {
                return lab.withAlertaCritica(true);
            }
        }

        return lab;
    }

    /**
     * Extracts numeric value from the start of an analytical value string.
     * Example: "14.5 g/dL" → 14.5, "120 mg/dL" → 120.0
     *
     * @param analyteName name of analyte (for error messages)
     * @param rawValue    raw analyte value with possible unit
     * @return extracted numeric value
     * @throws LabValidationException if value doesn't contain a valid number
     */
    private double extractNumericValue(String analyteName, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new LabValidationException(analyteName,
                    "analytical value cannot be blank");
        }

        Matcher matcher = NUMERIC_PREFIX.matcher(rawValue.trim());
        if (!matcher.find()) {
            throw new LabValidationException(analyteName,
                    "could not extract numeric value from: '%s'".formatted(rawValue));
        }

        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new LabValidationException(
                    "Error parsing numeric value for analyte '%s': %s"
                            .formatted(analyteName, rawValue), e);
        }
    }

    // ─── Internal record for thresholds ───────────────────────────────────

    /**
     * Critical alert threshold for a clinical analyte.
     * Values OUTSIDE the range [min, max] trigger critical alert.
     */
    record CriticalThreshold(double min, double max) {
        CriticalThreshold {
            if (min > max) {
                throw new IllegalArgumentException(
                        "min (%s) cannot be greater than max (%s)".formatted(min, max));
            }
        }
    }
}
