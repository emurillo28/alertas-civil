# HCG Coding Standards

## Java
- **Records only** for domain models (immutable, no setters).
- **OCC versioning** on all mutable entities (`long version`).
- **Package structure**: `com.hospital.alertas.{domain,application,infrastructure}`.
- **Null & invariant safety**: Validate in record constructors (e.g., regex checks in `PatientId`).
- **No reactive frameworks**: Strictly imperative, sequential Java code. No Mutiny (`Uni`/`Multi`), Reactor, or WebFlux.

## Quarkus / Lambda
- `@RegisterForReflection(serialization = true)` on all domain records and DTOs.
- `@ApplicationScoped` for services and adapters.
- Virtual threads (Project Loom) for concurrent I/O.
- Native image profile: `-Pnative -Dquarkus.native.container-build=true`.
- Unified stream handler: `QuarkusStreamHandler` handles API Gateway HTTP & SQS events.

## DynamoDB
- Single-table design only (`app.dynamodb.table-name`, default `hc-alertas`).
- **Synchronous client**: Use blocking `DynamoDbClient` with virtual threads, NOT `DynamoDbAsyncClient`.
- Query API over Scan (query `PK = PATIENT#{id}` or `SK begins_with LAB#`).
- Conditional writes for OCC (`attribute_not_exists(PK)` for version 0; `#v = :expected` for updates).

## Testing
- `@QuarkusTest` + `@QuarkusTestResource(LocalStackTestResource.class)` for integration tests.
- Pure unit tests for domain invariants with no framework dependencies.
- In-memory test fakes (`InMemoryLabPersistence`) for testing application use cases.
- Realism in test fixtures: realistic clinical ranges and analytes.

## Build & Deploy
```bash
# Build native binary
mvn clean package -Pnative -Dquarkus.native.container-build=true

# Local tests with LocalStack
mvn test
```
