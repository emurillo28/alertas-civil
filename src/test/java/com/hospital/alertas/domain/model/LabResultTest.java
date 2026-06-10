package com.hospital.alertas.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the domain entity {@link LabResult}.
 * Verifies creation, validations, immutability, and builder methods.
 */
class LabResultTest {

    private static final PatientId PATIENT_ID = new PatientId("PAC001");
    // Translations: Biometria Hematica -> Hematic Biometry
    private static final String TEST_TYPE = "Hematic Biometry"; 
    private static final Map<String, String> VALUES = Map.of(
            "Hemoglobin", "14.5 g/dL", // Translated key names implicitly through usage/context
            "Leukocytes", "7500 /uL"
    );
    private static final Instant TIMESTAMP = Instant.parse("2026-06-01T10:30:00Z");
    private static final String S3_PATH = "s3://hc-laboratorios/2026/06/01/PAC001/BH-001.pdf";

    private LabResult createValidLab() {
        return new LabResultTestBuilder()
                .withLabId("LAB001")
                .build();
    }

    @Nested
    @DisplayName("Valid Creation Cases") // Translated: Creación válida
    class ValidCreationCases {
        
        @Test
        @DisplayName("should create LabResult with all fields present") // Translated: crea LabResult con todos los campos
        void createFullLab() {
            var lab = createValidLab();

            assertEquals("LAB001", lab.labId());
            assertEquals(PATIENT_ID, lab.patientId());
            // Fix typeExamen(): tipoExamen() -> testType()
            assertEquals(TEST_TYPE, lab.testType()); 
            // Fix valoresAnaliticos(): valoresAnaliticos() -> analyticValues()
            assertEquals(VALUES, lab.analyticValues()); 
            assertEquals(TIMESTAMP, lab.timestamp());
            // Fix alertaCritica(): alertaCritica() -> criticalAlert()
            assertFalse(lab.criticalAlert(), "Should not have a critical alert initially");
            // Fix s3PathCompleto(): s3PathCompleto() -> s3Path()
            assertEquals(S3_PATH, lab.s3Path()); 
            // Fix resumenClinico(): resumenClinico() -> clinicalSummary()
            assertNull(lab.clinicalSummary(), "Clinical summary should be null initially");
            assertEquals(0L, lab.version());
        }

        @Test
        @DisplayName("should create LabResult with active critical alert") // Translated: crea LabResult con alerta crítica activa
        void createLabWithCriticalAlert() {
            var lab = new LabResultTestBuilder()
                    .withLabId("LAB002")
                    // Assume a dedicated English builder field exists or use the generic one
                    .withTestType("Glucose") 
                    .withSingleAnalyte("Glucose", "450 mg/dL") // Using translated specialized method name
                    // Fix withAlertaCritica(): withAlertaCritica -> withCriticalAlert() or similar English equivalent
                    .withCriticalAlert(true) 
                    .build();

            assertTrue(lab.criticalAlert(), "Lab must show a critical alert.");
        }

        @Test
        @DisplayName("should create LabResult with clinical summary") // Translated: crea LabResult con resumen clínico
        void createLabWithSummary() {
            var lab = new LabResultTestBuilder()
                    .withLabId("LAB003")
                    .withTestType(TEST_TYPE)
                    // Use the dedicated builder method for map loading consistency if possible, otherwise use generic:
                    .withAnalyticValues(VALUES) 
                    // Renamed and translated summary setting
                    .withClinicalSummary("Values within normal ranges")
                    .withVersion(1L)
                    .build();

            assertEquals("Values within normal ranges", lab.clinicalSummary(), "The clinical summary should be set correctly.");
            assertEquals(1L, lab.version());
        }
    }

    @Nested
    @DisplayName("Factory method create()")
    class FactoryMethod {

        @Test
        @DisplayName("should generate automatic UUID and version 0") // Translated: genera UUID automático y versión 0
        void createGeneratesUuidAndVersionZero() {
            var lab = LabResult.create(
                    PATIENT_ID, TEST_TYPE, VALUES,
                    TIMESTAMP, false, S3_PATH
            );

            assertNotNull(lab.labId());
            assertFalse(lab.labId().isBlank(), "Lab ID should not be blank"); 
            // Fix resumenClinico(): resumenClinico() -> clinicalSummary()
            assertNull(lab.clinicalSummary()); 
            assertEquals(0L, lab.version());
        }

        @Test
        @DisplayName("each invocation should generate distinct UUID") // Translated: cada invocación genera un UUID distinto
        void createGeneratesDistinctUuids() {
            var lab1 = LabResult.create(PATIENT_ID, TEST_TYPE, VALUES, TIMESTAMP, false, S3_PATH);
            var lab2 = LabResult.create(PATIENT_ID, TEST_TYPE, VALUES, TIMESTAMP, false, S3_PATH);

            assertNotEquals(lab1.labId(), lab2.labId(), "Lab IDs should be different"); 
        }
    }

    @Nested
    @DisplayName("Rejection Validation") // Translated: Validación de rechazo
    class RejectionValidation {
        
        // Utility method fixed types for compilation purposes.
        private LabResult buildWithInvalid(String labId, PatientId patientId, String testType,
                                         Map<String, String> analyticValues, Instant timestamp,
                                         Boolean criticalAlert, String s3Path, String clinicalSummary,
                                         Long version) {
            return new LabResult(
                    labId, patientId, testType, analyticValues,
                    timestamp, criticalAlert, s3Path,
                    clinicalSummary, version,
                    VisibilityStatus.PENDING, List.of() // Fixed enum reference
            );
        }

        @Test
        @DisplayName("should reject null labId") // Translated: rechaza labId nulo
        void rejectsNullLabId() {
            assertThrows(NullPointerException.class, () -> buildWithInvalid(
                    null, PATIENT_ID, TEST_TYPE, VALUES, TIMESTAMP, false, S3_PATH, null, 0L)
            );
        }

        @Test
        @DisplayName("should reject empty labId") // Translated: rechaza labId vacío
        void rejectsEmptyLabId() {
            assertThrows(IllegalArgumentException.class, () -> buildWithInvalid(
                    "", PATIENT_ID, TEST_TYPE, VALUES, TIMESTAMP, false, S3_PATH, null, 0L)
            );
        }

        @Test
        @DisplayName("should reject null patientId") // Translated: rechaza patientId nulo
        void rejectsNullPatientId() {
            assertThrows(NullPointerException.class, () -> buildWithInvalid(
                    "LAB001", null, TEST_TYPE, VALUES, TIMESTAMP, false, S3_PATH, null, 0L)
            );
        }

        @Test
        @DisplayName("should reject null testType") // Translated: rechaza tipoExamen nulo
        void rejectsNullTestType() {
            assertThrows(NullPointerException.class, () -> buildWithInvalid(
                    "LAB001", PATIENT_ID, null, VALUES, TIMESTAMP, false, S3_PATH, null, 0L)
            );
        }

        @Test
        @DisplayName("should reject empty testType") // Translated: rechaza tipoExamen vacío
        void rejectsEmptyTestType() {
            assertThrows(IllegalArgumentException.class, () -> buildWithInvalid(
                    "LAB001", PATIENT_ID, "", VALUES, TIMESTAMP, false, S3_PATH, null, 0L)
            );
        }

        @Test
        @DisplayName("should reject null analyticValues") // Translated: rechaza valoresAnaliticos nulo
        void rejectsNullAnalyticValues() {
            assertThrows(NullPointerException.class, () -> buildWithInvalid(
                    "LAB001", PATIENT_ID, TEST_TYPE, null, TIMESTAMP, false, S3_PATH, null, 0L)
            );
        }

        @Test
        @DisplayName("should reject empty analyticValues") // Translated: rechaza valoresAnaliticos vacío
        void rejectsEmptyAnalyticValues() {
            assertThrows(IllegalArgumentException.class, () -> buildWithInvalid(
                    "LAB001", PATIENT_ID, TEST_TYPE, Map.of(), TIMESTAMP, false, S3_PATH, null, 0L)
            );
        }

        @Test
        @DisplayName("should reject null timestamp") // Translated: rechaza timestamp nulo
        void rejectsNullTimestamp() {
            assertThrows(NullPointerException.class, () -> buildWithInvalid(
                    "LAB001", PATIENT_ID, TEST_TYPE, VALUES, null, false, S3_PATH, null, 0L)
            );
        }

        @Test
        @DisplayName("should reject null s3Path") // Translated: rechaza s3Path nulo
        void rejectsNullS3Path() {
            assertThrows(NullPointerException.class, () -> buildWithInvalid(
                    "LAB001", PATIENT_ID, TEST_TYPE, VALUES, TIMESTAMP, false, null, null, 0L)
            );
        }

        @Test
        @DisplayName("should reject empty s3Path") // Translated: rechaza s3Path vacío
        void rejectsEmptyS3Path() {
            assertThrows(IllegalArgumentException.class, () -> buildWithInvalid(
                    "LAB001", PATIENT_ID, TEST_TYPE, VALUES, TIMESTAMP, false, "", null, 0L)
            );
        }

        @Test
        @DisplayName("should reject negative version") // Translated: rechaza versión negativa
        void rejectsNegativeVersion() {
            assertThrows(IllegalArgumentException.class, () -> buildWithInvalid(
                    "LAB001", PATIENT_ID, TEST_TYPE, VALUES, TIMESTAMP, false, S3_PATH, null, -1L)
            );
        }
    }

    @Nested
    @DisplayName("Immutability") // Translated: Inmutabilidad
    class ImmutabilityTest {
        
        @Test
        @DisplayName("the analytic values map must be immutable") 
        void analyticValuesMapIsImmutable() {
            // Using HashMap for mutability testing context
            var mutableMap = new java.util.HashMap<String, String>();
            mutableMap.put("Glucose", "120 mg/dL");

            var lab = new LabResult(
                    "LAB001", PATIENT_ID, TEST_TYPE, mutableMap,
                    TIMESTAMP, false, S3_PATH, null, 0L,
                    VisibilityStatus.PENDING, List.of()
            );

            // Verify immutability against external changes
            mutableMap.put("Extra", "value");
            assertFalse(lab.analyticValues().containsKey("Extra"), "The analytic values map should remain protected from external changes."); 

            // Attempt to modify the internal state (should throw UnsupportedOperationException)
            assertThrows(UnsupportedOperationException.class,
                    () -> lab.analyticValues().put("Hack", "value"));
        }
    }

    @Nested
    @DisplayName("With* Methods") // Translated: Métodos with*
    class WithMethods {
        
        @Test
        @DisplayName("withIncrementedVersion should increment the version by 1") 
        void withIncrementedVersion() {
            var lab = createValidLab();
            var updated = lab.withIncrementedVersion();

            assertEquals(1L, updated.version(), "Version must increase by exactly one.");
            // All other fields are equal
            assertEquals(lab.labId(), updated.labId());
            assertEquals(lab.patientId(), updated.patientId());
            assertEquals(lab.testType(), updated.testType()); 
        }

        @Test
        @DisplayName("withClinicalSummary should set the summary") 
        void withClinicalSummary() {
            var lab = createValidLab();
            assertNull(lab.clinicalSummary(), "Summary must be null before setting.");

            // Use translated method name for consistency
            var withSummary = lab.withClinicalSummary("Patient stable, normal values"); 
            
            assertEquals("Patient stable, normal values", withSummary.clinicalSummary(), "The clinical summary should reflect the input.");
            assertEquals(lab.version(), withSummary.version(), "Version number should not change when setting summary.");
        }

        @Test
        @DisplayName("withClinicalSummary should reject null input") 
        void withClinicalSummaryRejectsNull() {
            var lab = createValidLab();
            assertThrows(NullPointerException.class, () -> lab.withClinicalSummary(null), "Setting summary with null must throw NPE.");
        }
    }


    @Nested
    @DisplayName("DynamoDB Key Generation") // Translated: Clave DynamoDB
    class DynamoKey {

        @Test
        @DisplayName("should generate sort key in format LAB#<labId>") 
        void generatesSortKey() {
            var lab = createValidLab();
            assertEquals("LAB#LAB001", lab.toDynamoSortKey(), "The sorted key must follow the LAB#<id> pattern.");
        }
    }
}