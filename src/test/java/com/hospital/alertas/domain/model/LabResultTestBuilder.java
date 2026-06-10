package com.hospital.alertas.domain.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test builder for {@link LabResult} with realistic clinical default data.
 *
 * <p>Allows building valid instances by selectively overriding
 * only the fields relevant for each test scenario, avoiding
 * boilerplate data repetition.
 *
 * <p>Default values: patient PAC001, Complete Blood Count with
 * Hemoglobin 14.5 g/dL and Leukocytes 7500 /uL (normal ranges).
 */
public final class LabResultTestBuilder {

    private String labId = UUID.randomUUID().toString();
    private PatientId patientId = new PatientId("PAC001");
    private String testType = "Complete Blood Count";
    private Map<String, String> analyticValues = new HashMap<>(Map.of(
            "Hemoglobin", "14.5 g/dL",
            "Leukocytes", "7500 /uL"
    ));
    private Instant timestamp = Instant.parse("2026-06-01T10:30:00Z");
    private boolean criticalAlert = false;
    private String s3Path = "s3://hc-laboratories/2026/06/01/PAC001/BH-001.pdf";
    private String clinicalSummary = null;
    private long version = 0L;
    private VisibilityStatus visibilityStatus = VisibilityStatus.PENDING;
    private List<SentNotification> notifications = List.of();

    public LabResultTestBuilder withLabId(String labId) {
        this.labId = labId;
        return this;
    }

    public LabResultTestBuilder withPatientId(String patientIdValue) {
        this.patientId = new PatientId(patientIdValue);
        return this;
    }

    public LabResultTestBuilder withPatientId(PatientId patientId) {
        this.patientId = patientId;
        return this;
    }

    /**
     * @deprecated Use {@link #withTestType(String)} instead.
     */
    @Deprecated
    public LabResultTestBuilder withTipoExamen(String tipoExamen) {
        return withTestType(tipoExamen);
    }

    public LabResultTestBuilder withTestType(String testType) {
        this.testType = testType;
        return this;
    }

    public LabResultTestBuilder withAnalyticValues(Map<String, String> values) {
        this.analyticValues = new HashMap<>(values);
        return this;
    }

    /**
     * Adds or overrides a specific analytical value, keeping others.
     */
    public LabResultTestBuilder withAnalyte(String name, String value) {
        this.analyticValues.put(name, value);
        return this;
    }

    /**
     * Replaces ALL analytical values with a single analyte.
     * Useful for tests evaluating a specific threshold in isolation.
     */
    public LabResultTestBuilder withSingleAnalyte(String name, String value) {
        this.analyticValues = new HashMap<>(Map.of(name, value));
        return this;
    }

    public LabResultTestBuilder withTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public LabResultTestBuilder withCriticalAlert(boolean criticalAlert) {
        this.criticalAlert = criticalAlert;
        return this;
    }

    public LabResultTestBuilder withS3Path(String s3Path) {
        this.s3Path = s3Path;
        return this;
    }

    public LabResultTestBuilder withClinicalSummary(String clinicalSummary) {
        this.clinicalSummary = clinicalSummary;
        return this;
    }

    public LabResultTestBuilder withVersion(long version) {
        this.version = version;
        return this;
    }

    public LabResultTestBuilder withVisibilityStatus(VisibilityStatus visibilityStatus) {
        this.visibilityStatus = visibilityStatus;
        return this;
    }

    public LabResultTestBuilder withNotifications(List<SentNotification> notifications) {
        this.notifications = notifications;
        return this;
    }

    // ─── Aliases for backward compatibility with tests ────────────────────

    /**
     * @deprecated Use {@link #withSingleAnalyte(String, String)} instead.
     */
    @Deprecated
    public LabResultTestBuilder withSoloAnalito(String nombre, String valor) {
        return withSingleAnalyte(nombre, valor);
    }

    /**
     * @deprecated Use {@link #withAnalyticValues(Map)} instead.
     */
    @Deprecated
    public LabResultTestBuilder withValoresAnaliticos(Map<String, String> valores) {
        return withAnalyticValues(valores);
    }

    /**
     * @deprecated Use {@link #withCriticalAlert(boolean)} instead.
     */
    @Deprecated
    public LabResultTestBuilder withAlertaCritica(boolean alertaCritica) {
        return withCriticalAlert(alertaCritica);
    }

    public LabResult build() {
        return new LabResult(
                labId, patientId, testType,
                Map.copyOf(analyticValues),
                timestamp, criticalAlert, s3Path,
                clinicalSummary, version,
                visibilityStatus, notifications
        );
    }

    /**
     * Shortcut: creates a LabResult with normal clinical values (no alert).
     */
    public static LabResult aNormalLab() {
        return new LabResultTestBuilder().build();
    }

    /**
     * Shortcut: creates a LabResult with critical Hemoglobin (< 7 g/dL).
     */
    public static LabResult aLabWithCriticalHemoglobin() {
        return new LabResultTestBuilder()
                .withSingleAnalyte("Hemoglobin", "5.2 g/dL")
                .withTestType("Complete Blood Count")
                .build();
    }

    /**
     * Shortcut: creates a LabResult with critical Potassium (> 6.0 mEq/L).
     */
    public static LabResult aLabWithCriticalPotassium() {
        return new LabResultTestBuilder()
                .withSingleAnalyte("Potassium", "7.8 mEq/L")
                .withTestType("Serum Electrolytes")
                .build();
    }

    /**
     * Shortcut: creates a LabResult with critical Glucose (> 400 mg/dL).
     */
    public static LabResult aLabWithCriticalGlucose() {
        return new LabResultTestBuilder()
                .withSingleAnalyte("Glucose", "520 mg/dL")
                .withTestType("Clinical Chemistry")
                .build();
    }
}