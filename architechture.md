# Architecture Blueprint: Hospital Civil Clinical Alerts Backend

## 1. Executive Summary & System Objectives
This system is the high-concurrency, resilient backend engine responsible for ingesting clinical laboratory results, evaluating critical alert thresholds, orchestrating clinician notifications, and persisting structured patient medical timelines for Hospital Civil de Guadalajara (HCG).

### Core Optimization Goals:
1. **Ultra-Low Latency & Cost Efficiency in Serverless**: Designed for AWS Lambda with dual deployment flexibility (JVM on Project Loom or GraalVM Native Image for sub-50ms cold starts).
2. **Elimination of Reactive Complexity (Anti-Vibe Coding)**: Zero reactive pipelines (no Mutiny, `Uni`/`Multi`, or WebFlux). The codebase is 100% sequential, imperative, and synchronous, delegating massive concurrent I/O scaling directly to the Java Virtual Machine via Virtual Threads (Project Loom).
3. **Optimized DynamoDB Single-Table Design**: Consolidates patient demographics, clinical laboratory records, and notification audit trails into a single physical table (`hc-alertas`), achieving atomic 1-RCU reads and zero network join overhead.
4. **Resilient Decoupled Ingestion**: Direct integration from API Gateway to Amazon SQS for laboratory ingestion, isolating high-volume ingestion spikes from backend compute resources.

---

## 2. Technology Stack & Specifications

| Component | Specification / Version | Rationale |
| :--- | :--- | :--- |
| **Language** | Java 25 (with Preview Features enabled) | Modern records for immutability, pattern matching, JEP 491 virtual thread synchronization. |
| **Framework** | Quarkus 3.36.0 (Imperative Mode) | MicroProfile/Jakarta EE ecosystem optimized for container/serverless runtimes and fast startup. |
| **Runtime & Execution** | AWS Lambda (SQS Handler + HTTP JAX-RS REST) | Serverless event-driven processing and on-demand clinical query endpoints. |
| **Concurrency Engine** | Project Loom (Virtual Threads) | Native JVM lightweight concurrency for blocking I/O without reactive overhead. |
| **Compilation Profile** | JVM Bytecode & GraalVM Native Executable | Profiles for local rapid development and sub-50ms cold-start native deployment. |
| **Persistence** | AWS SDK for DynamoDB v2 (`DynamoDbClient`) | Purely synchronous AWS client, fully descheduling virtual threads on network I/O. |
| **Messaging & Queues**| Amazon SQS (`quarkus-amazon-sqs`) | Decoupled ingest queue (`hc-alertas-ingest-queue`), DLQ, and notification queue. |
| **Object Storage** | Amazon S3 (`hc-alertas-clinical-docs`) | Immutable binary storage for original equipment laboratory PDF documents. |
| **Observability** | CloudWatch Embedded Metric Format (EMF) & SmallRye Health | Zero-latency structured metrics logging and `/q/health` health probes. |
| **Testing** | JUnit 5 + Testcontainers + LocalStack 3.4.0 | Hexagonal test pyramid with in-memory domain fakes and LocalStack integration tests. |

---

## 3. Architectural Design Principles

### A. Strict Hexagonal Architecture (Ports & Adapters)
The codebase enforces strict, unidirectional dependency rules from the outside inward:

```
[ External Systems: UI / Lab Devices / AWS API Gateway / SQS ]
                         │
                         ▼
        ┌──────────────────────────────────┐
        │     Infrastructure Adapters      │
        │  (SQS Handler, REST Resource)    │
        │                  │               │
        │                  ▼               │
        │       Application Layer          │
        │    (Inbound Use Cases / Ports)   │
        │                  │               │
        │                  ▼               │
        │          Domain Layer            │
        │    (Entities, VOs, Exceptions)   │
        │                  ▲               │
        │                  │               │
        │       Application Layer          │
        │   (Outbound Persistence Ports)   │
        │                  │               │
        │                  ▼               │
        │     Infrastructure Adapters      │
        │   (DynamoDB, SQS Notification)   │
        └──────────────────────────────────┘
```

1. **Domain Layer (`com.hospital.alertas.domain`)**:
   - Contains pure business models (`LabResult`, `PatientId`, `PatientTimeline`, `SentNotification`), value objects, and domain exceptions (`LabValidationException`, `OptimisticLockException`, `DatabaseThrottledException`).
   - **Absolute Boundary Rule:** Strictly forbidden from importing framework annotations (Quarkus, CDI, Jackson) or cloud infrastructure SDKs (`software.amazon.awssdk.*`).
2. **Application Layer (`com.hospital.alertas.application`)**:
   - Defines inbound use cases (`IngestLabUseCase`, `NotifyLabReadyUseCase`) and outbound SPI ports (`LabPersistencePort`, `NotificationPort`).
   - Orchestrates clinical flows and business validations (`IngestLabService`, `NotifyLabReadyService`).
   - Pure Java orchestration; CDI dependency injection via standard constructor injection.
3. **Infrastructure Layer (`com.hospital.alertas.infrastructure`)**:
   - Inbound adapters:
     - `IngestLabSqsHandler`: AWS Lambda event handler processing SQS ingestion batches.
     - `LabResultResource`: Quarkus REST / JAX-RS endpoints for clinical queries and notification triggers.
   - Outbound adapters:
     - `DynamoDbPersistenceAdapter`: Implements `LabPersistencePort` against the DynamoDB single table with OCC.
     - `SqsNotificationAdapter`: Implements `NotificationPort` for publishing alert notifications to SQS.
   - Config & Health: `CloudWatchEmfMetrics` and `SystemHealthCheck`.

---

### B. Imperative Concurrency Model & Loom in Java 25

Traditional reactive programming (e.g., Mutiny, Reactive Streams, `Mono`/`Flux`, callbacks) is **strictly banned** in this architecture.

* **Mechanics:** All application and domain logic is written sequentially, imperatively, and with blocking semantics.
* **Virtual Threads:** Network I/O against DynamoDB, S3, or SQS executes using standard synchronous AWS SDK clients (`DynamoDbClient`, `SqsClient`). The JVM automatically unmounts the virtual thread from its underlying carrier thread during network waits, scaling efficiently with minimal memory overhead.
* **Java 25 Synchronization Reality (JEP 491):**
  > **Note on Java 25:** In earlier Java versions (Java 21–23), virtual threads were "pinned" to carrier threads when executing within `synchronized` blocks or methods. With **JEP 491 (Synchronize Virtual Threads without Pinning)** in Java 24/25, virtual threads can now unmount and remount freely within `synchronized` blocks.
  > 
  > However, to maintain peak throughput and clean design:
  > - JNI / native calls and C-library invocations that block the carrier thread remain restricted.
  > - Lock-free primitives (`ConcurrentHashMap`, atomic references) or standard SDK thread-safe clients are used across the infrastructure adapters.

---

## 4. End-to-End System Topology & Ingestion Paths

The system provides two distinct operational paths: an asynchronous, decoupled write path and a synchronous query path.

```
1. Write Path (Ingest):
   [Lab Machine / UI] ──POST /labs──> [API Gateway] ──(Direct VTL)──> [SQS Ingest Queue]
                                                                              │
                                                                       (Batch Ingest)
                                                                              ▼
   [DynamoDB: hc-alertas] <──(OCC PutItem)── [IngestLabService] <── [IngestLabSqsHandler]

2. Read Path & Actions (HTTP Query):
   [Clinician Mobile / Web UI] ──GET /labs/patients/...──> [API Gateway]
                                                                  │
                                                           (Lambda Proxy)
                                                                  ▼
   [DynamoDB: hc-alertas] <──(Query)── [LabResultResource (JAX-RS)]

3. Critical Notification Flow:
   [Clinician UI / Alert Event] ──POST /labs/{id}/notify──> [LabResultResource]
                                                                    │
                                                      [NotifyLabReadyService]
                                                                    │
                                                        [SqsNotificationAdapter]
                                                                    │
                                                                    ▼
                                                        [SQS Notification Queue]
                                                                    │
                                                          (Downstream Push/SMS)
```

### Ingestion Fault-Tolerance & DLQ Strategy
The `IngestLabSqsHandler` implements differentiated exception handling:
* **Clinical Validation Failures (`LabValidationException`):** Identified as permanent client-side or payload errors (e.g., malformed patient ID, missing analytes). The error is logged via CloudWatch EMF, and the exception is **absorbed** so SQS removes the message, preventing fruitless retry loops.
* **Infrastructure & Transient Failures (`DatabaseThrottledException`, `SdkException`):** Identified as system or capacity issues. The exception is **rethrown**, allowing AWS Lambda and SQS native redrive policies to retry the message and ultimately route to the Dead-Letter Queue (`hc-alertas-dlq-queue`) if retries are exhausted.

---

## 5. DynamoDB Single-Table Design (`hc-alertas`)

All application entities are co-located in a single physical DynamoDB table (`hc-alertas`) configured via `app.dynamodb.table-name`.

### A. Key Schema & Access Patterns

| Entity / Access Pattern | Partition Key (`PK`) | Sort Key (`SK`) | Critical Attributes |
| :--- | :--- | :--- | :--- |
| **Patient Demographics** | `PATIENT#<PatientId>` | `METADATA` | `Name`, `DateOfBirth`, `ClinicalHistory` |
| **Clinical Lab Result** | `PATIENT#<PatientId>` | `LAB#<LabId>` | `TestType`, `AnalyticValues` (Map), `Timestamp`, `IsCriticalAlert` (Bool), `S3PathComplete`, `ClinicalSummary`, `Version` (OCC), `VisibilityStatus`, `SentNotifications` (List) |

### B. Attribute Specifications in Implementation

| Attribute Name | DynamoDB Type | Description |
| :--- | :--- | :--- |
| `PK` | String (`S`) | `PATIENT#<PatientId>` (strictly alphanumeric, 1–64 characters). |
| `SK` | String (`S`) | `METADATA` for patient profile or `LAB#<LabId>` for clinical test. |
| `TestType` | String (`S`) | Display name of the test (e.g., `"Complete Blood Count"`, `"Blood Chemistry"`). |
| `AnalyticValues` | Map (`M`) | Key-value mapping of analyte measurements (e.g., `{"Hemoglobin": "14.5 g/dL", "Glucose": "120 mg/dL"}`). |
| `Timestamp` | String (`S`) | Specimen collection or test execution time in ISO-8601 format. |
| `IsCriticalAlert` | Boolean (`BOOL`) | `true` if any analyte breaches emergency clinical thresholds. |
| `S3PathComplete` | String (`S`) | Canonical S3 URI pointing to the immutable raw PDF report in `hc-alertas-clinical-docs`. |
| `ClinicalSummary` | String (`S`) | Optional inline medical summary generated by physician notes or clinical AI pipelines. |
| `Version` | Number (`N`) | Monotonically increasing sequence number for Optimistic Concurrency Control. |
| `VisibilityStatus` | String (`S`) | Enum: `PENDING` (initial) → `NOTIFIED` (alert sent) → `READ_CONFIRMED` (acknowledged by clinician). |
| `SentNotifications` | List (`L`) | Inline list of notification maps: `NotificationId`, `Recipient`, `Channel`, `Status`, `TimestampSend`, `TimestampRead`. |

### C. Access Pattern Implementations

1. **Write Lab Result (Create / Update with OCC):**
   - New Lab (`version == 0`): `PutItem` with `ConditionExpression = "attribute_not_exists(PK) AND attribute_not_exists(SK)"`.
   - Update Lab (`version > 0`): `PutItem` with `ConditionExpression = "#version = :expectedVersion"`, where `:expectedVersion = version - 1`.
2. **Fetch Latest Lab Result for Patient:**
   - Single partition query: `PK = PATIENT#<patientId> AND begins_with(SK, "LAB#")`.
   - In-memory reduction by latest `Timestamp` (or ordered query with reverse index).
3. **Fetch Complete Patient Record / Timeline:**
   - Single paginated query: `PK = PATIENT#<patientId>`.
   - In a single network round-trip, retrieves both `METADATA` and all `LAB#` items to assemble a comprehensive `PatientTimeline`.
4. **Update Visibility Status / Register Notification:**
   - Atomic `UpdateItem` with condition on `Version`, incrementing `Version` by 1 and updating `VisibilityStatus` or appending to `SentNotifications`.
5. **List Registered Patients:**
   - Scan operation across partition keys to discover unique registered patients for directory lookup.

---

## 6. Project & Package Layout

The implementation aligns with the following package topology under base package `com.hospital.alertas`:

```text
alertas-civil/
├── pom.xml                                     # Java 25, Quarkus 3.36.0, AWS SDK BOM
├── src/main/java/com/hospital/alertas/
│   ├── domain/                                 # Pure clinical domain (Zero external deps)
│   │   ├── model/
│   │   │   ├── LabResult.java                  # Immutable record representing clinical lab item
│   │   │   ├── PatientId.java                  # Validated alphanumeric Value Object
│   │   │   ├── PatientTimeline.java            # Patient clinical record aggregator
│   │   │   ├── SentNotification.java           # Notification audit entry record
│   │   │   ├── NotificationChannel.java        # Enum: SMS, WHATSAPP, EMAIL, PUSH, IN_APP
│   │   │   ├── NotificationStatus.java         # Enum: PENDING, SENT, READ, FAILED
│   │   │   └── VisibilityStatus.java           # Enum: PENDING, NOTIFIED, READ_CONFIRMED
│   │   └── exception/
│   │       ├── LabValidationException.java     # Clinical validation failure (client error)
│   │       ├── OptimisticLockException.java    # Version mismatch on concurrent mutation
│   │       ├── DatabaseThrottledException.java # DynamoDB capacity/throughput exceeded
│   │       └── PersistenceException.java       # General storage exception
│   ├── application/                            # Clinical orchestration & ports
│   │   ├── ports/
│   │   │   ├── inbound/
│   │   │   │   ├── IngestLabUseCase.java       # Ingestion contract
│   │   │   │   └── NotifyLabReadyUseCase.java  # Notification dispatch contract
│   │   │   └── outbound/
│   │   │       ├── LabPersistencePort.java     # Storage interface contract
│   │   │       └── NotificationPort.java       # Notification delivery interface contract
│   │   └── service/
│   │       ├── IngestLabService.java           # Threshold evaluation & persistence coordination
│   │       ├── NotifyLabReadyService.java      # Notification registration & SQS publishing
│   │       └── NotifyLabReadyCommand.java      # Command transfer record
│   └── infrastructure/                         # External world adapters & config
│       ├── adapters/
│       │   ├── inbound/
│       │   │   ├── IngestLabSqsHandler.java    # SQS Lambda RequestHandler (async ingestion)
│       │   │   ├── LabResultResource.java      # Quarkus JAX-RS REST resource (queries & actions)
│       │   │   ├── IngestLabRequest.java       # Inbound wire DTO from SQS
│       │   │   ├── LabResultDto.java           # Clean external response DTO
│       │   │   ├── LabResultMapper.java        # Domain <-> DTO translation
│       │   │   ├── LatestLabResponse.java      # Latest lab REST envelope
│       │   │   ├── NotifyLabRequest.java       # Notification trigger wire DTO
│       │   │   └── PatientTimelineResponse.java# Patient history REST envelope
│       │   ├── outbound/
│       │   │   └── SqsNotificationAdapter.java # Publishes alerts to SQS notification queue
│       │   └── persistence/
│       │       └── DynamoDbPersistenceAdapter.java # Single-table implementation with OCC
│       ├── config/
│       │   └── CloudWatchEmfMetrics.java       # Embedded Metric Format logger
│       └── health/
│           └── SystemHealthCheck.java          # SmallRye /q/health probe
└── src/test/java/com/hospital/alertas/         # Hexagonal test pyramid
    ├── domain/                                 # Pure unit tests (invariants, builders)
    ├── application/                            # Use case tests using InMemoryLabPersistence fakes
    └── integration/                            # Quarkus tests with LocalStack (DynamoDB & SQS)
```

---

## 7. Testing Strategy: Hexagonal Testing Pyramid

Tests are treated as executable clinical documentation of system behavior:

1. **Domain Layer (Pure Unit Tests - No Frameworks):**
   - Verify business invariants (e.g., `PatientId` rejecting spaces and non-alphanumeric characters).
   - Verify critical threshold evaluations and immutability guarantees.
2. **Application Layer (Use Case Unit Tests with In-Memory Fakes):**
   - Evaluates use cases using `InMemoryLabPersistence` instead of brittle Mockito mocks.
   - Asserts end-to-end business outcomes (e.g., verifying that ingesting a lab with Glucose > 400 mg/dL marks `isCriticalAlert == true` and records the item).
3. **Infrastructure Layer (Integration Tests with LocalStack):**
   - `@QuarkusTest` executing against LocalStack containers for DynamoDB, SQS, and S3.
   - Tests conditional write failures, concurrent OCC collisions, and SQS batch processing.
4. **Clinical Realism in Test Fixtures:**
   - Avoid generic placeholder data (`"foo"`, `"bar"`).
   - Use clinically realistic lab names, analytes, and reference ranges via test builders (`LabResultTestBuilder`).

---

## 8. Architectural Observations & Recommendations

Based on the current codebase, infrastructure declarations, and client contracts, the following recommendations are identified:

1. **Notification Secondary Index (GSI1):**
   - *Current State:* The application queries records by `PATIENT#<id>`. If a background worker or polling service needs to discover all alerts in `VisibilityStatus = 'PENDING'` across all patients, a full table scan would currently be required.
   - *Recommendation:* Provision Global Secondary Index `GSI1` with `GSI1_PK = VisibilityStatus` and `GSI1_SK = Timestamp`. The adapter can write `STATUS#PENDING` only for active critical alerts, creating a sparse index for efficient polling.
2. **Client-Side UUID Generation (`labId`):**
   - Because the ingestion path uses direct API Gateway to SQS integration (bypassing synchronous Lambda execution), the API cannot generate and return an auto-generated backend ID synchronously.
   - *Recommendation:* Enforce that client applications (e.g., React Native mobile app) generate a cryptographically secure UUID v4 client-side prior to issuing `POST /labs`.
3. **S3 Presigned URLs for Secure PDF Viewing:**
   - The entity stores `S3PathComplete` (e.g., `s3://hc-alertas-clinical-docs/...`). Direct S3 paths should not be exposed directly to mobile clients without authentication.
   - *Recommendation:* Expose an endpoint `GET /labs/{labId}/pdf-url` that generates short-lived (e.g., 15-minute) Amazon S3 Presigned URLs for authorized clinicians.
4. **Build Artifact Group ID Alignment:**
   - `pom.xml` uses `<groupId>mx.hospital.civil</groupId>` while code packages reside under `com.hospital.alertas`. Keep this documented to prevent Maven dependency resolution confusion across internal CI/CD pipelines.