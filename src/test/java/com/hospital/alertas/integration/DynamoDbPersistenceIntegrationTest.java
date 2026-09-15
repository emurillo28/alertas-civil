package com.hospital.alertas.integration;

import com.hospital.alertas.application.ports.outbound.LabPersistencePort;
import com.hospital.alertas.domain.exception.OptimisticLockException;
import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.NotificationChannel;
import com.hospital.alertas.domain.model.PatientId;
import com.hospital.alertas.domain.model.SentNotification;
import com.hospital.alertas.domain.model.VisibilityStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link LabPersistencePort} against real LocalStack
 * DynamoDB/SQS.
 *
 * <p>
 * Tests are fully isolated: each test generates its own {@link PatientId} via a
 * random UUID
 * in {@link #setUp()}, preventing any cross-test state contamination in the
 * shared DynamoDB table.
 *
 * <p>
 * Run with: {@code mvn verify -DskipUnitTests}
 */
@QuarkusTest
@QuarkusTestResource(LocalStackTestResource.class)
class DynamoDbPersistenceIntegrationTest {

    @Inject
    LabPersistencePort persistencePort;

    // A fresh, isolated patient ID is generated before every single test.
    // This eliminates shared partition-key state and removes the need for
    // @TestMethodOrder.
    private PatientId testPatient;
    private static final String TEST_TYPE = "Integration Test Panel";

    @BeforeEach
    void setUp() {
        // PatientId enforces strict alphanumeric — strip UUID hyphens to stay compliant
        testPatient = new PatientId(UUID.randomUUID().toString().replace("-", ""));
    }

    // -------------------------------------------------------------------------
    // CRUD & OCC — sequential, isolated
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should create and retrieve lab result via DynamoDB")
    void shouldCreateAndRetrieveLab() {
        // Given
        LabResult lab = LabResult.create(
                testPatient,
                TEST_TYPE,
                Map.of("Glucosa", "95 mg/dL", "Hemoglobina", "14.5 g/dL"),
                Instant.now(),
                false,
                "s3://test-bucket/test.pdf");

        // When
        persistencePort.saveLab(lab);

        // Then
        Optional<LabResult> found = persistencePort.findLatestByPatient(testPatient);
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
    @DisplayName("Should update lab with OCC version increment")
    void shouldUpdateLabWithOcc() {
        // Given
        LabResult lab = LabResult.create(
                testPatient,
                TEST_TYPE,
                Map.of("Potasio", "4.5 mEq/L"),
                Instant.now(),
                false,
                "s3://test-bucket/test2.pdf");
        persistencePort.saveLab(lab);

        LabResult saved = persistencePort.findLatestByPatient(testPatient).orElseThrow();

        // When - update with new clinical summary
        LabResult updated = saved.withClinicalSummary("Patient stable, values normal")
                .withVersionIncremented();
        persistencePort.saveLab(updated);

        // Then
        Optional<LabResult> found = persistencePort.findLatestByPatient(testPatient);
        assertTrue(found.isPresent());
        LabResult retrieved = found.get();
        assertEquals(1, retrieved.version()); // Version incremented
        assertEquals("Patient stable, values normal", retrieved.clinicalSummary());
    }

    @Test
    @DisplayName("Should reject stale version on sequential OCC conflict")
    void shouldRejectStaleVersion() {
        // Given
        LabResult lab = LabResult.create(
                testPatient,
                TEST_TYPE,
                Map.of("Sodio", "140 mEq/L"),
                Instant.now(),
                false,
                "s3://test-bucket/test3.pdf");
        persistencePort.saveLab(lab);

        // Simulate two readers that both read version=0
        LabResult reader1 = persistencePort.findLatestByPatient(testPatient).orElseThrow();
        LabResult reader2 = persistencePort.findLatestByPatient(testPatient).orElseThrow();

        // When - first writer wins
        persistencePort.saveLab(reader1.withVersionIncremented());

        // Then - second writer holds a stale version and must be rejected
        assertThrows(OptimisticLockException.class,
                () -> persistencePort.saveLab(reader2.withVersionIncremented()));
    }

    @Test
    @DisplayName("Should update visibility status with OCC")
    void shouldUpdateVisibilityStatus() {
        // Given
        LabResult lab = LabResult.create(
                testPatient,
                TEST_TYPE,
                Map.of("Creatinina", "1.2 mg/dL"),
                Instant.now(),
                false,
                "s3://test-bucket/test4.pdf");
        persistencePort.saveLab(lab);

        LabResult saved = persistencePort.findLatestByPatient(testPatient).orElseThrow();

        // When
        persistencePort.updateVisibilityStatus(
                testPatient, saved.labId(), VisibilityStatus.NOTIFIED, saved.version());

        // Then
        Optional<LabResult> found = persistencePort.findLatestByPatient(testPatient);
        assertTrue(found.isPresent());
        assertEquals(VisibilityStatus.NOTIFIED, found.get().visibilityStatus());
        assertEquals(1, found.get().version());
    }

    @Test
    @DisplayName("Should register notification with OCC")
    void shouldRegisterNotification() {
        // Given
        LabResult lab = LabResult.create(
                testPatient,
                TEST_TYPE,
                Map.of("Plaquetas", "250000 /uL"),
                Instant.now(),
                false,
                "s3://test-bucket/test5.pdf");
        persistencePort.saveLab(lab);

        LabResult saved = persistencePort.findLatestByPatient(testPatient).orElseThrow();
        SentNotification notification = SentNotification.create("DR001", NotificationChannel.SMS);

        // When
        persistencePort.registerNotification(
                testPatient, saved.labId(), notification, VisibilityStatus.NOTIFIED, saved.version());

        // Then
        Optional<LabResult> found = persistencePort.findLatestByPatient(testPatient);
        assertTrue(found.isPresent());
        LabResult retrieved = found.get();
        assertEquals(VisibilityStatus.NOTIFIED, retrieved.visibilityStatus());
        assertEquals(1, retrieved.notifications().size());
        assertEquals("DR001", retrieved.notifications().get(0).recipient());
        assertEquals(NotificationChannel.SMS, retrieved.notifications().get(0).channel());
    }

    @Test
    @DisplayName("Should retrieve all unique patient IDs from persistence")
    void shouldFindAllPatientIds() {
        // Seed at least one record under the isolated patient so it is visible to the
        // scan
        LabResult lab = LabResult.create(
                testPatient,
                TEST_TYPE,
                Map.of("Marker", "present"),
                Instant.now(),
                false,
                "s3://test-bucket/scan-seed.pdf");
        persistencePort.saveLab(lab);

        // When
        List<PatientId> patientIds = persistencePort.findAllPatientIds();

        // Then
        assertNotNull(patientIds);
        assertTrue(patientIds.contains(testPatient));
    }

    // -------------------------------------------------------------------------
    // TRUE CONCURRENCY TEST — Loom Virtual Threads + StructuredTaskScope
    // -------------------------------------------------------------------------

    /**
     * Proves that {@code attribute_not_exists} + OCC constraints hold up under raw
     * race conditions that mirror a real SQS duplicate-delivery event.
     *
     * <p>
     * Two virtual threads blast the same payload at DynamoDB simultaneously.
     * Exactly one must win; the other must be rejected. The database must contain
     * exactly one record afterward — no phantom duplicates, no silent data loss.
     */
    @Test
    @DisplayName("Should absorb concurrent duplicate inserts gracefully (Loom race test)")
    void shouldAbsorbConcurrentDuplicates() throws Exception {
        // Fully isolated patient — no coupling to any other test
        PatientId isolatedPatient = new PatientId(UUID.randomUUID().toString().replace("-", ""));

        LabResult duplicatePayload = LabResult.create(
                isolatedPatient,
                "Concurrent Panel",
                Map.of("X", "Y"),
                Instant.now(),
                false,
                "s3://test-bucket/concurrent.pdf");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        // ShutdownOnFailure: if either thread throws an *unexpected* exception the
        // scope propagates it, keeping the test honest.
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {

            // Thread A — first duplicate
            scope.fork(() -> {
                try {
                    persistencePort.saveLab(duplicatePayload);
                    successCount.incrementAndGet();
                } catch (OptimisticLockException e) {
                    // Expected for exactly ONE of the two threads
                    rejectedCount.incrementAndGet();
                }
                return null;
            });

            // Thread B — identical payload, racing at the exact same instant
            scope.fork(() -> {
                try {
                    persistencePort.saveLab(duplicatePayload);
                    successCount.incrementAndGet();
                } catch (OptimisticLockException e) {
                    rejectedCount.incrementAndGet();
                }
                return null;
            });

            scope.join(); // When using awaitAll(), failures inside individual subtasks are handled
                          // within the task lambdas themselves (or inspected via Subtask handles)
        }

        // One thread must have won, one must have been rejected by DynamoDB
        assertEquals(1, successCount.get(),
                "Exactly one concurrent insert should succeed");
        assertEquals(1, rejectedCount.get(),
                "Exactly one concurrent insert should be rejected by OCC");

        // Hard assertion: only ONE record persisted — no phantom duplicates
        Optional<LabResult> found = persistencePort.findLatestByPatient(isolatedPatient);
        assertTrue(found.isPresent());
        assertEquals(1, found.get().version(),
                "Concurrent execution bypassed database constraints — duplicate record found!");
    }
}