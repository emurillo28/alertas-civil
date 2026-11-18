# HCG Architecture

## Project: alertas-civil (Hospital Civil Guadalajara - Clinical Alerts System)

### Stack
- Java 25, Quarkus 3.36+, GraalVM native, AWS Lambda, DynamoDB
- Virtual threads (Project Loom) for concurrent blocking I/O
- Hexagonal architecture: Domain → Application → Infrastructure

### Architectural Boundaries
```
Domain (records, exceptions)
  ▲
  │ (implements ports)
Application (use cases, inbound/outbound ports, services)
  ▲
  │ (calls use cases / implements ports)
Infrastructure (adapters, handlers, resources, DynamoDB, SQS)
```
*Dependencies point strictly INWARD only.*

### Key Decisions (ADRs)
- **Single-table DynamoDB**: Consolidates demographics, labs, and notifications into `hc-alertas`.
- **Java 25 + Virtual Threads**: Loom for blocking I/O without reactive frameworks (no Mutiny, Uni, or Multi).
- **OCC versioning on LabResult**: Mandatory monotonically increasing `version` prevents lost updates.
- **Synchronous AWS SDK Client**: `DynamoDbClient` deschedules virtual threads cleanly during network calls.
- **Native image profile**: Sub-50ms cold starts on AWS Lambda.

### Package Structure
```
com.hospital.alertas
├── domain
│   ├── model          # Immutable records (LabResult, PatientId, PatientTimeline, SentNotification)
│   └── exception      # LabValidationException, OptimisticLockException, DatabaseThrottledException
├── application
│   ├── ports
│   │   ├── inbound    # IngestLabUseCase, NotifyLabReadyUseCase
│   │   └── outbound   # LabPersistencePort, NotificationPort
│   └── service        # IngestLabService, NotifyLabReadyService
└── infrastructure
    ├── adapters
    │   ├── inbound    # IngestLabSqsHandler, LabResultResource, DTOs, LabResultMapper
    │   ├── outbound   # SqsNotificationAdapter
    │   └── persistence# DynamoDbPersistenceAdapter (single-table, OCC)
    ├── config         # CloudWatchEmfMetrics
    └── health         # SystemHealthCheck
```
