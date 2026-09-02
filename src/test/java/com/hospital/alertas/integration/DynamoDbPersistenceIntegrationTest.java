package com.hospital.alertas.integration;

import com.hospital.alertas.application.ports.outbound.LabPersistencePort;
import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.PatientId;
import com.hospital.alertas.domain.model.VisibilityStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link LabPersistencePort} against real LocalStack DynamoDB/SQS.
 *
 * <p>These tests use Testcontainers-managed LocalStack 3.4.0 (pinned version)
 * to verify the DynamoDB adapter works against a real AWS-compatible endpoint.
 *
 * <p>Run with: {@code mvn verify -DskipUnitTests}
 */
@QuarkusTest
@QuarkusTestResource(LocalStackTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbPersistenceIntegrationTest {

    @Inject
    LabPersistencePort persistencePort;

    private static final PatientId TEST_PATIENT = new PatientId("INTEGRATIONTESTPATIENT");
    private static final String TEST_TYPE = "Integration Test Panel";

    @BeforeEach
    void setUp() {
        // Fresh test data per test (no shared table state between tests in integration)
    }

    @Test
    @Order(1)
    @DisplayName("Should create and retrieve lab result via DynamoDB")
    void shouldCreateAndRetrieveLab() {
        // Given
        LabResult lab = LabResult.create(
                TEST_PATIENT,
                TEST_TYPE,
                Map.of("Glucosa", "95 mg/dL", "Hemoglobina", "14.5 g/dL"),
                Instant.now(),
                false,
                "s3://test-bucket/test.pdf"
        );

        // When
        persistencePort.saveLab(lab);

        // Then
        Optional<LabResult> found = persistencePort.findLatestByPatient(TEST_PATIENT);
        assertTrue(found.isPresent());
        LabResult saved = found.get();
        assertEquals(TEST_TYPE, saved.testType());
        assertEquals(2, saved.analyticValues().size());
        assertEquals("95 mg/dL", saved.analyticValues().get("Glucosa"));
        assertFalse(saved.criticalAlert());
        assertEquals(0, saved.version()); // Initial version
        assertEquals(VisibilityStatus.PENDING, saved.visibilityStatus());
        assertTrue(saved.notifications().isEmpty());
    }

    @Test
    @Order(2)
    @DisplayName("Should update lab with OCC version increment")
    void shouldUpdateLabWithOcc() {
        // Given
        LabResult lab = LabResult.create(
                TEST_PATIENT,
                TEST_TYPE,
                Map.of("Potasio", "4.5 mEq/L"),
                Instant.now(),
                false,
                "s3://test-bucket/test2.pdf"
        );
        persistencePort.saveLab(lab);

        // Retrieve saved lab
        LabResult saved = persistencePort.findLatestByPatient(TEST_PATIENT).orElseThrow();

        // When - update with new clinical summary
        LabResult updated = saved.withClinicalSummary("Patient stable, values normal")
                                .withVersionIncremented();

        persistencePort.saveLab(updated);

        // Then
        Optional<LabResult> found = persistencePort.findLatestByPatient(TEST_PATIENT);
        assertTrue(found.isPresent());
        LabResult retrieved = found.get();
        assertEquals(1, retrieved.version()); // Version incremented
        assertEquals("Patient stable, values normal", retrieved.clinicalSummary());
    }

    @Test
    @Order(3)
    @DisplayName("Should reject concurrent update with OCC conflict")
    void shouldRejectStaleVersion() {
        // Given
        LabResult lab = LabResult.create(
                TEST_PATIENT,
                TEST_TYPE,
                Map.of("Sodio", "140 mEq/L"),
                Instant.now(),
                false,
                "s3://test-bucket/test3.pdf"
        );
        persistencePort.saveLab(lab);

        // Retrieve twice - simulate two concurrent readers
        LabResult reader1 = persistencePort.findLatestByPatient(TEST_PATIENT).orElseThrow();
        LabResult reader2 = persistencePort.findLatestByPatient(TEST_PATIENT).orElseThrow();

        // When - first update succeeds
        persistencePort.saveLab(reader1.withVersionIncremented());

        // Then - second update with stale version should fail
        assertThrows(com.hospital.alertas.domain.exception.OptimisticLockException.class,
                () -> persistencePort.saveLab(reader2.withVersionIncremented()));
    }

    @Test
    @Order(4)
    @DisplayName("Should update visibility status with OCC")
    void shouldUpdateVisibilityStatus() {
        // Given
        LabResult lab = LabResult.create(
                TEST_PATIENT,
                TEST_TYPE,
                Map.of("Creatinina", "1.2 mg/dL"),
                Instant.now(),
                false,
                "s3://test-bucket/test4.pdf"
        );
        persistencePort.saveLab(lab);

        LabResult saved = persistencePort.findLatestByPatient(TEST_PATIENT).orElseThrow();

        // When
        persistencePort.updateVisibilityStatus(TEST_PATIENT, saved.labId(),
                                               VisibilityStatus.NOTIFIED, saved.version());

        // Then
        Optional<LabResult> found = persistencePort.findLatestByPatient(TEST_PATIENT);
        assertTrue(found.isPresent());
        assertEquals(VisibilityStatus.NOTIFIED, found.get().visibilityStatus());
        assertEquals(1, found.get().version());
    }

    @Test
    @Order(5)
    @DisplayName("Should register notification with OCC")
    void shouldRegisterNotification() {
        // Given
        LabResult lab = LabResult.create(
                TEST_PATIENT,
                TEST_TYPE,
                Map.of("Plaquetas", "250000 /uL"),
                Instant.now(),
                false,
                "s3://test-bucket/test5.pdf"
        );
        persistencePort.saveLab(lab);

        LabResult saved = persistencePort.findLatestByPatient(TEST_PATIENT).orElseThrow();
        var notification = com.hospital.alertas.domain.model.SentNotification
                .create("DR001", com.hospital.alertas.domain.model.NotificationChannel.SMS);

        // When
        persistencePort.registerNotification(TEST_PATIENT, saved.labId(),
                                             notification, VisibilityStatus.NOTIFIED, saved.version());

        // Then
        Optional<LabResult> found = persistencePort.findLatestByPatient(TEST_PATIENT);
        assertTrue(found.isPresent());
        LabResult retrieved = found.get();
        assertEquals(VisibilityStatus.NOTIFIED, retrieved.visibilityStatus());
        assertEquals(1, retrieved.notifications().size());
        assertEquals("DR001", retrieved.notifications().get(0).recipient());
        assertEquals(com.hospital.alertas.domain.model.NotificationChannel.SMS,
                     retrieved.notifications().get(0).channel());
    }

    @Test
    @Order(6)
    @DisplayName("Should retrieve all unique patient IDs from persistence")
    void shouldFindAllPatientIds() {
        // When
        List<PatientId> patientIds = persistencePort.findAllPatientIds();

        // Then
        assertNotNull(patientIds);
        assertTrue(patientIds.contains(TEST_PATIENT));
    }
}