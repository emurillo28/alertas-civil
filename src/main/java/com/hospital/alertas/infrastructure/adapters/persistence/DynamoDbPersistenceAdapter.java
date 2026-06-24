package com.hospital.alertas.infrastructure.adapters.persistence;

import com.hospital.alertas.application.ports.outbound.LabPersistencePort;
import com.hospital.alertas.domain.exception.DatabaseThrottledException;
import com.hospital.alertas.domain.exception.LabValidationException;
import com.hospital.alertas.domain.exception.OptimisticLockException;
import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.NotificationChannel;
import com.hospital.alertas.domain.model.NotificationStatus;
import com.hospital.alertas.domain.model.PatientId;
import com.hospital.alertas.domain.model.PatientTimeline;
import com.hospital.alertas.domain.model.SentNotification;
import com.hospital.alertas.domain.model.VisibilityStatus;
import com.hospital.alertas.infrastructure.config.CloudWatchEmfMetrics;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistence adapter that connects the {@link LabPersistencePort} port
 * with Amazon DynamoDB using the Single-Table pattern.
 *
 * <p>Uses the standard synchronous {@link DynamoDbClient}, which is compatible
 * with Project Loom: virtual threads are efficiently descheduled during
 * network I/O operations, without thread pinning.
 *
 * <p><strong>Single-Table Schema (table: hc-alertas):</strong>
 * <ul>
 *   <li>PK: PATIENT&lt;PatientId&gt;</li>
 *   <li>SK: LAB&lt;LabId&gt;</li>
 *   <li>Version: numeric, required for OCC</li>
 * </ul>
 *
 * <p><strong>Note on thread pinning:</strong> This adapter does NOT use
 * {@code synchronized}. DynamoDB operations are thread-safe by design
 * of the AWS SDK client. We use {@code java.util.logging} instead of locks
 * to prevent carrier thread pinning in virtual threads.
 */
@ApplicationScoped
public class DynamoDbPersistenceAdapter implements LabPersistencePort {

    private static final Logger LOGGER = Logger.getLogger(DynamoDbPersistenceAdapter.class.getName());

    // ─── DynamoDB Attribute Names ────────────────────────────────────────
    static final String ATTR_PK = "PK";
    static final String ATTR_SK = "SK";
    /** Maps to 'TipoExamen' */
    static final String ATTR_TEST_TYPE = "TestType";
    /** Maps to 'ValoresAnaliticos' */
    static final String ATTR_ANALYTIC_VALUES = "AnalyticValues";
    static final String ATTR_TIMESTAMP = "Timestamp";
    static final String ATTR_IS_CRITICAL_ALERT = "IsCriticalAlert";
    /** Maps to 'S3_Path_Completo' */
    static final String ATTR_S3_PATH = "S3PathComplete";
    /** Maps to 'Resumen_Clinico' */
    static final String ATTR_CLINICAL_SUMMARY = "ClinicalSummary";
    static final String ATTR_VERSION = "Version";
    /** The type associated with the VisibilityStatus enum */
    static final String ATTR_VISIBILITY_STATUS = "VisibilityStatus";
    /** Maps to 'SentNotifications' */
    static final String ATTR_SENT_NOTIFICATIONS = "SentNotifications";

    private static final String SK_LAB_PREFIX = "LAB#";

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final CloudWatchEmfMetrics metrics;

    /**
     * Constructor for CDI injection.
     *
     * @param dynamoDbClient synchronous DynamoDB client (injected by Quarkus Amazon Services)
     * @param tableName      name of the DynamoDB table (configurable via application.properties)
     * @param metrics        CloudWatch EMF metrics
     */
    public DynamoDbPersistenceAdapter(
            DynamoDbClient dynamoDbClient,
            @ConfigProperty(name = "app.dynamodb.table-name", defaultValue = "hc-alertas") String tableName,
            CloudWatchEmfMetrics metrics
    ) {
        this.dynamoDbClient = Objects.requireNonNull(dynamoDbClient, "DynamoDbClient cannot be null");
        this.tableName = Objects.requireNonNull(tableName, "tableName cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "CloudWatchEmfMetrics cannot be null");
    }

    // ─── saveLab ────────────────────────────────────────────────────────
    @Override
    public void saveLab(LabResult lab) {
        Objects.requireNonNull(lab, "LabResult cannot be null");

        long newVersion = lab.version() + 1;

        // Build SET expression for UpdateItem
        // PK and SK go in the Key, not in SET
        Map<String, AttributeValue> key = Map.of(
                ATTR_PK, AttributeValue.fromS(lab.patientId().toDynamoPartitionKey()),
                ATTR_SK, AttributeValue.fromS(SK_LAB_PREFIX + lab.labId())
        );

        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        Map<String, String> expressionAttributeNames = new HashMap<>();
        StringBuilder updateExpression = new StringBuilder("SET ");

        // Map each field using attribute name aliases to avoid reserved keywords
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#testType", ATTR_TEST_TYPE, AttributeValue.fromS(lab.testType()));
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#timestamp", ATTR_TIMESTAMP, AttributeValue.fromS(lab.timestamp().toString()));
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#isCriticalAlert", ATTR_IS_CRITICAL_ALERT, AttributeValue.fromBool(lab.criticalAlert()));
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#s3Path", ATTR_S3_PATH, AttributeValue.fromS(lab.s3Path()));
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#version", ATTR_VERSION, AttributeValue.fromN(String.valueOf(newVersion)));

        // Analytic values (Map)
        Map<String, AttributeValue> analyticsMap = new HashMap<>();
        lab.analyticValues().forEach((k, v) -> analyticsMap.put(k, AttributeValue.fromS(v)));
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#analyticValues", ATTR_ANALYTIC_VALUES, AttributeValue.fromM(analyticsMap));

        // Clinical summary (optional)
        if (lab.clinicalSummary() != null) {
            addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                    "#clinicalSummary", ATTR_CLINICAL_SUMMARY, AttributeValue.fromS(lab.clinicalSummary()));
        }

        // Visibility status (always present, default PENDING)
        addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                "#visibilityStatus", ATTR_VISIBILITY_STATUS, AttributeValue.fromS(lab.visibilityStatus().name()));

        // Sent notifications (list, may be empty)
        if (!lab.notifications().isEmpty()) {
            List<AttributeValue> notificationsList = lab.notifications().stream()
                    .map(n -> {
                        Map<String, AttributeValue> nMap = new HashMap<>();
                        nMap.put("NotificationId", AttributeValue.fromS(n.notificationId()));
                        nMap.put("Recipient", AttributeValue.fromS(n.recipient()));
                        nMap.put("Channel", AttributeValue.fromS(n.channel().name()));
                        nMap.put("Status", AttributeValue.fromS(n.status().name()));
                        nMap.put("TimestampSend", AttributeValue.fromS(n.timestampSend().toString()));
                        if (n.timestampRead() != null) {
                            nMap.put("TimestampRead", AttributeValue.fromS(n.timestampRead().toString()));
                        }
                        return AttributeValue.fromM(nMap);
                    }).toList();
            addSetClause(updateExpression, expressionAttributeNames, expressionAttributeValues,
                    "#notifications", ATTR_SENT_NOTIFICATIONS, AttributeValue.fromL(notificationsList));
        }

        // Remove trailing comma if present
        if (updateExpression.length() > 0 && updateExpression.charAt(updateExpression.length() - 1) == ',') {
            updateExpression.setLength(updateExpression.length() - 2);
        }


        UpdateItemRequest.Builder requestBuilder = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression(updateExpression.toString())
                .expressionAttributeNames(expressionAttributeNames)
                .expressionAttributeValues(expressionAttributeValues);

        // ── Control de Concurrencia Optimista (OCC) ──
        if (lab.version() == 0) {
            // New item: must not exist previously
            requestBuilder.conditionExpression(
                    "attribute_not_exists(#pk) AND attribute_not_exists(#sk)");
            expressionAttributeNames.put("#pk", ATTR_PK);
            expressionAttributeNames.put("#sk", ATTR_SK);
        } else {
            // Update: check if DB version matches
            requestBuilder
                    .conditionExpression("#version = :expectedVersion")
                    .expressionAttributeValues(Map.of(
                            ":expectedVersion", AttributeValue.fromN(String.valueOf(lab.version()))
                    ));
            expressionAttributeNames.put("#version", ATTR_VERSION);
        }

        try {
            dynamoDbClient.updateItem(requestBuilder.build());
            // Use String.format for consistent message formatting in logs
            LOGGER.fine(() -> String.format("Lab %s persisted successfully (version %d -> %d)", lab.labId(), lab.version(), newVersion));

        } catch (ConditionalCheckFailedException e) {
            LOGGER.log(Level.WARNING, "OCC conflict when saving lab {0} (expected version: {1})",
                    new Object[]{lab.labId(), lab.version()});
            metrics.logOccConflict();
            throw new OptimisticLockException(
                    lab.labId(),
                    lab.version(),
                    String.format("Optimistic concurrency conflict: the laboratory '%s' was modified by another process. "
                            + "Expected version: %d. Please retry the operation with updated data.",
                            lab.labId(), lab.version()),
                    e);

        } catch (ProvisionedThroughputExceededException e) {
            LOGGER.log(Level.SEVERE, "DynamoDB capacity exceeded when saving lab {0}", lab.labId());
            throw new DatabaseThrottledException(
                    "DynamoDB read/write capacity exceeded. "
                            + "Consider enabling Auto Scaling or increasing WCU.",
                    e);
        }
    }

    /** Helper to add SET clause to the update expression. */
    private void addSetClause(StringBuilder updateExpression,
                              Map<String, String> expressionAttributeNames,
                              Map<String, AttributeValue> expressionAttributeValues,
                              String attrNameAlias, String attrName, AttributeValue value) {
        expressionAttributeNames.put(attrNameAlias, attrName);
        // We use a placeholder replacement to ensure attribute value aliases are unique across sets
        String singleLetterPlaceholder = "^"; 
        String valueAliasKey = ":val" + UUID.randomUUID().toString().substring(0, 4); // Ensure uniqueness
        expressionAttributeValues.put(valueAliasKey, value);
        updateExpression.append(attrNameAlias).append(" = :").append(valueAliasKey).append(", ");
    }

    // ─── findLatestByPatient ────────────────────────────────────────
    @Override
    public Optional<LabResult> findLatestByPatient(PatientId patientId) {
        Objects.requireNonNull(patientId, "PatientId cannot be null");

        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("%s = :pk AND begins_with(%s, :skPrefix)"
                        .formatted(ATTR_PK, ATTR_SK))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(patientId.toDynamoPartitionKey()),
                        ":skPrefix", AttributeValue.fromS(SK_LAB_PREFIX)
                ))
                .build();

        try {
            QueryResponse response = dynamoDbClient.query(request);

            return response.items().stream()
                    .map(this::fromItemMap)
                    .max(Comparator.comparing(LabResult::timestamp));

        } catch (ProvisionedThroughputExceededException e) {
            LOGGER.log(Level.SEVERE, "DynamoDB capacity exceeded querying patient {0}",
                    patientId.value());
            throw new DatabaseThrottledException(
                    String.format("DynamoDB read capacity exceeded when querying patient '%s'.",
                            patientId.value()),
                    e);
        }
    }

    // ─── Map Domain → DynamoDB ────────────────────────────────────────
    /**
     * Converts a {@link LabResult} to a DynamoDB attribute map.
     * Does not include Version (added externally for OCC).
     */
    private Map<String, AttributeValue> toItemMap(LabResult lab) {
        Map<String, AttributeValue> item = new HashMap<>();

        item.put(ATTR_PK, AttributeValue.fromS(lab.patientId().toDynamoPartitionKey()));
        item.put(ATTR_SK, AttributeValue.fromS(SK_LAB_PREFIX + lab.labId()));
        item.put(ATTR_TEST_TYPE, AttributeValue.fromS(lab.testType()));
        item.put(ATTR_TIMESTAMP, AttributeValue.fromS(lab.timestamp().toString()));
        item.put(ATTR_IS_CRITICAL_ALERT, AttributeValue.fromBool(lab.criticalAlert()));
        item.put(ATTR_S3_PATH, AttributeValue.fromS(lab.s3Path()));

        // Analytic values like Map<String, String> → Map<String, AttributeValue(S)>
        Map<String, AttributeValue> analyticsMap = new HashMap<>();
        lab.analyticValues().forEach((key, value) ->
                analyticsMap.put(key, AttributeValue.fromS(value)));
        item.put(ATTR_ANALYTIC_VALUES, AttributeValue.fromM(analyticsMap));

        // Clinical summary is optional (might not be generated yet)
        if (lab.clinicalSummary() != null) {
            item.put(ATTR_CLINICAL_SUMMARY, AttributeValue.fromS(lab.clinicalSummary()));
        }

        return item;
    }

    // ─── Map DynamoDB → Domain ─────────────────────────────────────────
    /**
     * Reconstructs a {@link LabResult} from a DynamoDB item map.
     */
    private LabResult fromItemMap(Map<String, AttributeValue> item) {
        String pk = item.get(ATTR_PK).s();
        String patientIdValue = pk.replace("PATIENT#", "");

        String sk = item.get(ATTR_SK).s();
        String labId = sk.replace(SK_LAB_PREFIX, "");

        // Rebuild analytic values
        Map<String, String> analyticValues = new HashMap<>();
        if (item.containsKey(ATTR_ANALYTIC_VALUES)) {
            item.get(ATTR_ANALYTIC_VALUES).m()
                    .forEach((key, value) -> analyticValues.put(key, value.s()));
        }

        // Clinical summary might not exist
        String clinicalSummary = item.containsKey(ATTR_CLINICAL_SUMMARY)
                ? item.get(ATTR_CLINICAL_SUMMARY).s()
                : null;

        long version = Long.parseLong(item.get(ATTR_VERSION).n());

        // Visibility status (default PENDING for compatibility with legacy items)
        VisibilityStatus visibilityStatus = VisibilityStatus.PENDING;
        if (item.containsKey(ATTR_VISIBILITY_STATUS)) {
            try {
                visibilityStatus = VisibilityStatus.valueOf(item.get(ATTR_VISIBILITY_STATUS).s());
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Invalid VisibilityStatus in DynamoDB, using PENDING: " + item.get(ATTR_VISIBILITY_STATUS).s());
            }
        }

        // Sent notifications (list might not exist in legacy items)
        List<SentNotification> notifications = List.of();
        if (item.containsKey(ATTR_SENT_NOTIFICATIONS)) {
            notifications = item.get(ATTR_SENT_NOTIFICATIONS).l().stream()
                    .map(av -> {
                        Map<String, AttributeValue> nMap = av.m();
                        String notificationId = nMap.get("NotificationId").s();
                        String recipient = nMap.get("Recipient").s();
                        NotificationChannel channel = NotificationChannel.valueOf(nMap.get("Channel").s());
                        NotificationStatus status = NotificationStatus.valueOf(nMap.get("Status").s());
                        Instant timestampSend = Instant.parse(nMap.get("TimestampSend").s());
                        Instant timestampRead = nMap.containsKey("TimestampRead")
                                ? Instant.parse(nMap.get("TimestampRead").s())
                                : null;
                        return new SentNotification(notificationId, recipient, channel, status, timestampSend, timestampRead);
                    }).toList();
        }

        return new LabResult(
                labId,
                new PatientId(patientIdValue),
                item.get(ATTR_TEST_TYPE).s(),
                analyticValues,
                Instant.parse(item.get(ATTR_TIMESTAMP).s()),
                item.get(ATTR_IS_CRITICAL_ALERT).bool(),
                item.get(ATTR_S3_PATH).s(),
                clinicalSummary,
                version,
                visibilityStatus,
                notifications
        );
    }

    // ─── findCompleteRecord ────────────────────────────────────────
    @Override
    public Optional<PatientTimeline> findCompleteRecord(PatientId patientId) {
        Objects.requireNonNull(patientId, "PatientId cannot be null");

        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("%s = :pk".formatted(ATTR_PK))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(patientId.toDynamoPartitionKey())
                ))
                .build();

        try {
            QueryResponse response = dynamoDbClient.query(request);

            String name = null;
            Instant birthDate = null;
            String clinicalHistory = null;
            List<LabResult> labResults = new ArrayList<>();

            for (Map<String, AttributeValue> item : response.items()) {
                String sk = item.get(ATTR_SK).s();
                if ("METADATA".equals(sk)) {
                    // Patient metadata item
                    if (item.containsKey("Name")) name = item.get("Name").s();
                    if (item.containsKey("DateOfBirth")) birthDate = Instant.parse(item.get("DateOfBirth").s());
                    if (item.containsKey("ClinicalHistory")) clinicalHistory = item.get("ClinicalHistory").s();
                } else if (sk.startsWith(SK_LAB_PREFIX)) {
                    // Laboratory item
                    labResults.add(fromItemMap(item));
                }
            }

            if (name == null && labResults.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(new PatientTimeline(patientId, name, birthDate, clinicalHistory, labResults));

        } catch (ProvisionedThroughputExceededException e) {
            LOGGER.log(Level.SEVERE, "DynamoDB capacity exceeded querying patient record {0}",
                    patientId.value());
            throw new DatabaseThrottledException(
                    String.format("DynamoDB read capacity exceeded when querying patient record '%s'.",
                            patientId.value()),
                    e);
        }
    }

    // ─── updateVisibilityStatus ──────────────────────────────────────
    @Override
    public void updateVisibilityStatus(PatientId patientId, String labId,
                                             VisibilityStatus newStatus, long expectedVersion) {
        Objects.requireNonNull(patientId, "PatientId cannot be null");
        Objects.requireNonNull(labId, "labId cannot be null");
        Objects.requireNonNull(newStatus, "newStatus cannot be null");

        Map<String, AttributeValue> key = Map.of(
                ATTR_PK, AttributeValue.fromS(patientId.toDynamoPartitionKey()),
                ATTR_SK, AttributeValue.fromS(SK_LAB_PREFIX + labId)
        );

        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        Map<String, String> expressionAttributeNames = new HashMap<>();

        expressionAttributeValues.put(":status", AttributeValue.fromS(newStatus.name()));
        expressionAttributeValues.put(":version", AttributeValue.fromN(String.valueOf(expectedVersion + 1)));
        expressionAttributeValues.put(":expectedVersion", AttributeValue.fromN(String.valueOf(expectedVersion)));

        // Use correct English constant names for aliases/names
        expressionAttributeNames.put("#visibilityStatus", "VisibilityStatus");
        expressionAttributeNames.put("#version", ATTR_VERSION);

        String updateExpression = "SET #visibilityStatus = :status, #version = :version";

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression(updateExpression)
                .expressionAttributeNames(expressionAttributeNames)
                .expressionAttributeValues(expressionAttributeValues)
                // Condition for OCC check
                .conditionExpression("#version = :expectedVersion")
                .build();

        try {
            dynamoDbClient.updateItem(request);
            LOGGER.fine(() -> String.format("Visibility status updated for lab %s: %s (version %d -> %d)",
                    labId, newStatus, expectedVersion, expectedVersion + 1));

        } catch (ConditionalCheckFailedException e) {
            LOGGER.log(Level.WARNING, "OCC conflict when updating visibility status lab {0} (expected version: {1})",
                    new Object[]{labId, expectedVersion});
            metrics.logOccConflict();
            throw new OptimisticLockException(
                    labId,
                    expectedVersion,
                    String.format("Optimistic concurrency conflict: the laboratory '%s' was modified by another process. "
                            + "Expected version: %d. Please retry the operation with updated data.",
                            labId, expectedVersion),
                    e);

        } catch (ProvisionedThroughputExceededException e) {
            LOGGER.log(Level.SEVERE, "DynamoDB capacity exceeded when updating status lab {0}", labId);
            throw new DatabaseThrottledException(
                    "DynamoDB write capacity exceeded while updating status.",
                    e);
        }
    }

    // ─── registerNotification ────────────────────────────────────────────
    @Override
    public void registerNotification(PatientId patientId, String labId,
                                       SentNotification notification,
                                       VisibilityStatus newStatusPlaceholder, long expectedVersion) {
        Objects.requireNonNull(patientId, "PatientId cannot be null");
        Objects.requireNonNull(labId, "labId cannot be null");
        Objects.requireNonNull(notification, "notification cannot be null");

        // 1. Read current lab record via dedicated method to get existing notifications and version
        Optional<LabResult> currentLabOpt = findLatestByPatient(patientId)
                .filter(lab -> lab.labId().equals(labId));

        if (currentLabOpt.isEmpty()) {
            throw new LabValidationException("Laboratory not found: " + labId);
        }

        LabResult currentLab = currentLabOpt.get();

        // 2. Validate version consistency
        if (currentLab.version() != expectedVersion) {
            throw new OptimisticLockException(
                    labId,
                    expectedVersion,
                    String.format("Concurrency conflict: expected version %d, actual version %d", 
                            expectedVersion, currentLab.version()));
        }

        // 3. Aggregate and update the notification list
        List<SentNotification> updatedNotifications = new ArrayList<>(currentLab.notifications());
        updatedNotifications.add(notification);

        /*
         * CRITICAL: Since saveLab() increments the version number AND updates other fields,
         * we MUST construct a new LabResult object with all current state data 
         * (minus the notifications list) but with the updated notification list.
         */
        
        // Reconstruct a lab result to pass to saveLab(), which handles version incrementation correctly.
        LabResult newLabState = new LabResult(
                labId,
                currentLab.patientId(),
                currentLab.testType(),
                new HashMap<>(currentLab.analyticValues()),
                currentLab.timestamp(),
                currentLab.criticalAlert(),
                currentLab.s3Path(),
                currentLab.clinicalSummary(),
                expectedVersion, // Pass the existing version to allow saveLab's OCC check to succeed
                currentLab.visibilityStatus(),
                updatedNotifications
        );

        // Call saveLab which handles the full persistence contract (including version increment)
        saveLab(newLabState);
    }
}