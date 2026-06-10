package com.hospital.alertas.application.ports.outbound;

import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.PatientId;
import com.hospital.alertas.domain.model.PatientTimeline;
import com.hospital.alertas.domain.model.SentNotification;
import com.hospital.alertas.domain.model.VisibilityStatus;

import java.util.Optional;

/**
 * Outbound port for persistence of clinical laboratory results.
 *
 * <p>Defines the contract that any persistence adapter must implement
 * (DynamoDB, in-memory database for tests, etc.) following the Dependency
 * Inversion Principle of Hexagonal Architecture.
 *
 * <p>All methods are <strong>synchronous and blocking</strong> by design.
 * Concurrency is managed natively via Project Loom (virtual threads)
 * in the infrastructure layer, not through reactive APIs.
 *
 * <p><strong>Architectural constraint:</strong> This interface belongs to the
 * application layer and is forbidden from importing any AWS SDK
 * ({@code software.amazon.awssdk.*}) packages or Quarkus annotations.
 *
 * <p>Clinical semantics instead of generic CRUD to express medical domain.
 */
public interface LabPersistencePort {

    /**
     * Saves a clinical laboratory result (creates or updates with OCC).
     *
     * <p>The implementation must guarantee Optimistic Concurrency Control (OCC)
     * using the {@link LabResult#version()} field via
     * DynamoDB {@code ConditionExpression}.
     *
     * <p>For critical alerts, writes GSI1_PK = 'STATUS#PENDING' to
     * enable efficient query of labs pending notification.
     *
     * @param lab laboratory result to persist (not null, contains patientId)
     * @throws com.hospital.alertas.domain.exception.LabValidationException
     *         if laboratory data violates business invariants
     * @throws OptimisticLockException if an optimistic concurrency conflict occurs
     * @throws DatabaseThrottledException if database capacity is exceeded
     */
    void saveLab(LabResult lab);

    /**
     * Retrieves the most recent laboratory result for a patient.
     *
     * <p>The implementation must query by PK = PATIENT#&lt;patientId&gt;
     * with SK starting with "LAB#" and sort descending by timestamp
     * to return the latest.
     *
     * @param patientId patient identifier (not null)
     * @return {@link Optional} with the latest laboratory, or empty
     *         if the patient has no registered laboratories
     * @throws DatabaseThrottledException if read capacity is exceeded
     */
    Optional<LabResult> findLatestByPatient(PatientId patientId);

    /**
     * Retrieves the complete patient clinical record.
     *
     * <p>Performs a single query by PK = PATIENT#&lt;patientId&gt; and filters
     * SK = METADATA (demographics) and SK starting with LAB# (history).
     *
     * @param patientId patient identifier (not null)
     * @return {@link Optional} with {@link PatientTimeline} containing
     *         demographic data and list of laboratories, or empty if not found
     * @throws DatabaseThrottledException if read capacity is exceeded
     */
    Optional<PatientTimeline> findCompleteRecord(PatientId patientId);

    /**
     * Updates the visibility status of a laboratory using OCC.
     *
     * <p>Executes UpdateItem with ConditionExpression on the Version field.
     * Valid transitions: PENDING → NOTIFIED → READ_CONFIRMED.
     *
     * @param patientId     patient identifier
     * @param labId         laboratory identifier
     * @param newStatus     new visibility status
     * @param expectedVersion expected version for OCC
     * @throws OptimisticLockException if version doesn't match (concurrent conflict)
     * @throws DatabaseThrottledException if write capacity is exceeded
     */
    void updateVisibilityStatus(PatientId patientId, String labId,
                                 VisibilityStatus newStatus, long expectedVersion);

    /**
     * Registers a notification in the laboratory history and updates
     * the visibility status in an atomic operation with OCC.
     *
     * <p>Adds the notification to the {@code SentNotifications} list,
     * updates {@code VisibilityStatus} and increments {@code Version}
     * via UpdateItem with ConditionExpression.
     *
     * @param patientId       patient identifier
     * @param labId           laboratory identifier
     * @param notification    notification to register
     * @param newStatus       new visibility status (usually NOTIFIED)
     * @param expectedVersion expected version for OCC
     * @throws OptimisticLockException if version doesn't match
     * @throws DatabaseThrottledException if write capacity is exceeded
     */
    void registerNotification(PatientId patientId, String labId,
                               SentNotification notification,
                               VisibilityStatus newStatus, long expectedVersion);
}