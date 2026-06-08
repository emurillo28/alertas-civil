# HCG Architecture

## Project: alertas-civil (Hospital Civil Guadalajara - Clinical Alerts System)

### Stack
- Java 25, Quarkus 3.36+, GraalVM native, AWS Lambda, DynamoDB
- Virtual threads (Project Loom) for SQS batch processing
- Hexagonal architecture: Domain → Application → Infrastructure

### Architectural Boundaries
```
Domain (records, ports) → Application (use cases) → Infrastructure (adapters)
Dependencies point INWARD only
```

### Key Decisions (ADRs)
- **2025-01-15**: Single-table DynamoDB over relational for Lambda scalability
- **2025-01-18**: Java 25 + Virtual threads for SQS batch processing concurrency
- **2025-01-20**: OCC versioning on LabResult to prevent lost updates during concurrent corrections
- **2025-01-22**: Native image mandatory for Lambda (<50MB, <100ms cold start)

### Package Structure
```
com.hcg.alertas
├── domain
│   ├── model          # Java records (LabResult, PatientId, NotificacionEnviada)
│   ├── port           # Interfaces (LabPersistencePort, NotificationPort)
│   └── enum           # EstadoVisibilidad
├── application
│   └── usecase        # IngestLabUseCase, NotifyLabReadyUseCase
└── infrastructure
    ├── adapter        # DynamoDbLabAdapter, SnsNotificationAdapter
    ├── config         # Quarkus, AWS, DynamoDB config
    └── handler        # SQS Lambda handlers (IngestLabHandler)
```