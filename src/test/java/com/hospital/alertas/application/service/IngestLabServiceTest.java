package com.hospital.alertas.application.service;

import com.hospital.alertas.domain.exception.LabValidationException;
import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.LabResultTestBuilder;
import com.hospital.alertas.domain.model.PatientId;
import com.hospital.alertas.domain.model.VisibilityStatus;
import com.hospital.alertas.infrastructure.config.CloudWatchEmfMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Clinical behavior tests for {@link IngestLabService}.
 *
 * <p>Uses {@link InMemoryLabPersistence} as fake (no mocks).
 * Each test verifies an observable business scenario, not a line of code.
 *
 * <p>Naming convention: when<Action>_with<Condition>_then<Result>
 */
class IngestLabServiceTest {

    private InMemoryLabPersistence persistence;
    private IngestLabService service;
    private CloudWatchEmfMetrics metrics;

    @BeforeEach
    void setUp() {
        persistence = new InMemoryLabPersistence();
        metrics = new CloudWatchEmfMetrics("test", "test");
        service = new IngestLabService(persistence, metrics);
    }

    // ─── Successful Flow (Happy Path) ─────────────────────────────────────

    @Nested
    @DisplayName("Successful laboratory ingestion")
    class IngestaExitosa {

        @Test
        @DisplayName("when ingesting a lab with normal values, it persists without critical alert")
        void whenIngestLab_withNormalValues_thenPersistsAndNotCriticalAlert() {
            LabResult lab = LabResultTestBuilder.unLabNormal();

            service.execute(lab);

            assertEquals(1, persistence.count());
            Optional<LabResult> saved = persistence.getLastSaved();
            assertTrue(saved.isPresent());
            assertFalse(saved.get().criticalAlert(),
                    "A lab with Hemoglobin 14.5 g/dL should NOT be critical alert");
        }

        @Test
        @DisplayName("when ingesting a lab, it can be retrieved by patient")
        void whenIngestLab_thenCanRetrieveByPatient() {
            LabResult lab = new LabResultTestBuilder()
                    .withPatientId("PAC999")
                    .build();

            service.execute(lab);

            Optional<LabResult> found = persistence.findLatestByPatient(new PatientId("PAC999"));
            assertTrue(found.isPresent());
            assertEquals("PAC999", found.get().patientId().value());
        }

        @Test
        @DisplayName("when ingesting multiple labs for same patient, all are persisted")
        void whenIngestMultipleLabs_forSamePatient_thenAllAreSaved() {
            LabResult lab1 = new LabResultTestBuilder()
                    .withLabId("LAB001")
                    .withPatientId("PAC100")
                    .build();
            LabResult lab2 = new LabResultTestBuilder()
                    .withLabId("LAB002")
                    .withPatientId("PAC100")
                    .withTestType("Clinical Chemistry")
                    .withSoloAnalito("Glucose", "95 mg/dL")
                    .build();

            service.execute(lab1);
            service.execute(lab2);

            assertEquals(2, persistence.findAllByPatient(new PatientId("PAC100")).size());
        }
    }


    // ─── Automatic evaluation of critical clinical alerts ────────────────────

    @Nested
    @DisplayName("Automatic evaluation of critical clinical alerts")
    class CriticalAlerts {

        @Test
        @DisplayName("when ingesting a lab with Hemoglobin < 7.0, then critical alert is true")
        void whenIngestLabWithLowHemoglobin_thenCriticalAlertIsTrue() {
            LabResult lab = LabResultTestBuilder.unLabConHemoglobinaCritica();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.criticalAlert(),
                    "Hemoglobin 5.2 g/dL is below the threshold of 7.0 → critical alert");
        }

        @Test
        @DisplayName("when ingesting a lab with Potassium > 6.0, then critical alert is true")
        void whenIngestLabWithHighPotassium_thenCriticalAlertIsTrue() {
            LabResult lab = LabResultTestBuilder.unLabConPotasioCritico();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.criticalAlert(),
                    "Potassium 7.8 mEq/L exceeds the threshold of 6.0 → critical alert");
        }

        @Test
        @DisplayName("when ingesting a lab with Glucose > 400, then critical alert is true")
        void whenIngestLabWithHighGlucose_thenCriticalAlertIsTrue() {
            LabResult lab = LabResultTestBuilder.unLabConGlucosaCritica();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.criticalAlert(),
                    "Glucose 520 mg/dL exceeds the threshold of 400 → critical alert");
        }

        @Test
        @DisplayName("when ingesting a lab with Glucose < 50 (hypoglycemia), then critical alert is true")
        void whenIngestLabWithVeryLowGlucose_thenCriticalAlertIsTrue() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("Glucosa", "38 mg/dL")
                    .withTipoExamen("Quimica Sanguinea")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.criticalAlert(),
                    "Glucose 38 mg/dL is below 50 → critical hypoglycemia");
        }

        @Test
        @DisplayName("when ingesting a lab with Sodium > 160 (hypernatremia), then critical alert is true")
        void whenIngestLabWithHighSodium_thenCriticalAlertIsTrue() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("Sodio", "172 mEq/L")
                    .withTipoExamen("Electrolitos Sericos")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.criticalAlert(),
                    "Sodium 172 mEq/L exceeds the threshold of 160 → critical hypernatremia");
        }

        @Test
        @DisplayName("when ingesting a lab with Creatinine > 10 (renal failure), then critical alert is true")
        void whenIngestLabWithHighCreatinine_thenCriticalAlertIsTrue() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("Creatinina", "12.3 mg/dL")
                    .withTipoExamen("Quimica Sanguinea")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.criticalAlert(),
                    "Creatinine 12.3 mg/dL exceeds the threshold of 10.0 → critical renal failure");
        }

        @Test
        @DisplayName("when ingesting a lab with values right on the boundary, NO alert")
        void whenIngestLabWithValuesOnLimit_thenNotCriticalAlert() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("Hemoglobina", "7.0 g/dL")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertFalse(saved.criticalAlert(),
                    "Hemoglobin exactly at 7.0 is WITHIN the range [7.0, 20.0] → no alert");
        }

        @Test
        @DisplayName("when lab already has critical alert true, it must be respected and not reevaluated")
        void whenLabAlreadyHasCriticalAlert_thenItIsRespected() {
            LabResult lab = new LabResultTestBuilder()
                    .withAlertaCritica(true) // Assuming builder supports this for testing immutability/precedence
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.criticalAlert(),
                    "The pre-existing critical alert must be respected");
        }

        @Test
        @DisplayName("when ingesting a lab with unknown analyte, no alert triggered")
        void whenIngestLabWithUnknownAnalyte_thenNoAlertIsTriggered() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("VitaminaB12", "350 pg/mL")
                    .withTipoExamen("Vitaminas")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertFalse(saved.criticalAlert(),
                    "An analyte without a defined threshold should not trigger an alert");
        }

        @Test
        @DisplayName("when a lab has multiple analytes and only one is critical, alert triggers")
        void whenIngestLabWithOneCriticalAnalyteAmongMany_thenAlertIsTrue() {
            LabResult lab = new LabResultTestBuilder()
                    .withValoresAnaliticos(Map.of(
                            "Hemoglobina", "14.0 g/dL",  // Normal
                            "Leucocitos", "7500 /uL",    // No threshold
                            "Potasio", "6.5 mEq/L"       // CRITICAL (> 6.0)
                    ))
                    .withTipoExamen("Panel Completo")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.criticalAlert(),
                    "Potassium 6.5 exceeds the threshold of 6.0 even if others are normal");
        }

        @Test
        @DisplayName("analyte name comparison is case-insensitive")
        void whenIngestLabWithUppercaseAnalyteName_thenEvaluates() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("HEMOGLOBINA", "5.0 g/dL")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.criticalAlert(),
                    "Evaluation must be case-insensitive: 'HEMOGLOBINA' == 'hemoglobina'");
        }
    }


    // ─── Rejection of Corrupt Clinical Data ────────────────────────

    @Nested
    @DisplayName("Rejection of Corrupt Clinical Data")
    class CorruptedData {

        @Test
        @DisplayName("when ingesting a lab with non-numeric analytic value for evaluable analyte, throws exception")
        void whenIngestLabWithNonNumericValue_thenThrowsException() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("Hemoglobina", "INVALIDO")
                    .build();

            LabValidationException ex = assertThrows(LabValidationException.class,
                    () -> service.execute(lab));
            assertTrue(ex.getMessage().contains("Hemoglobina"),
                    "The error message must indicate which analyte failed");
        }

        @Test
        @DisplayName("when ingesting a lab with empty analytic value for evaluable analyte, throws exception")
        void whenIngestLabWithEmptyValue_thenThrowsException() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("Potasio", "")
                    .build();

            assertThrows(LabValidationException.class, () -> service.execute(lab));
        }

        @Test
        @DisplayName("when the service is constructed with null port, throws exception")
        void whenConstructingServiceWithNullPort_thenThrowsException() {
            CloudWatchEmfMetrics testMetrics = new CloudWatchEmfMetrics("test", "test");
            assertThrows(NullPointerException.class, () -> new IngestLabService(null, testMetrics));
        }
    }


    // ─── Inmutability in Ingestion Flow ─────────────────────────

    @Nested
    @DisplayName("Inmutability in Ingestion Flow")
    class Immutability {

        @Test
        @DisplayName("original LabResult is not modified when critical alert is activated")
        void whenAlertIsActivated_thenOriginalLabIsNotMutated() {
            LabResult labOriginal = new LabResultTestBuilder()
                    .withSoloAnalito("Hemoglobina", "5.0 g/dL")
                    .build();

            assertFalse(labOriginal.criticalAlert(), "Original lab has no alert");

            service.execute(labOriginal);

            // The original remains intact (Record immutability)
            assertFalse(labOriginal.criticalAlert(),
                    "The original Record must not mutate — a new instance is created");

            // But the persisted one DOES have an alert
            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.criticalAlert());
        }
    }
}