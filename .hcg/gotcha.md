# HCG Project Gotchas

**Purpose**: Minimal reference for coding LLMs working on `alertas-civil`. Only the non-obvious pitfalls that cause bugs/rework.

---

## Java / Domain Gotchas

| Gotcha | Fix |
|--------|-----|
| **Domain models are Java records only** — no setters, no mutable state | Use `withX()` pattern for new instances; constructors validate via `throw IllegalArgumentException` |
| **OCC versioning is MANDATORY** on every mutable entity (`Integer version`) | Every write: `UpdateItem` with `ConditionExpression` on `Version`; insert uses `attribute_not_exists(PK) AND attribute_not_exists(SK)` |
| **`LabResult` PK/SK ≠ what ADR/schema says** | Code uses `PK=PATIENT#{patientId}`, `SK=LAB#{labId}` (see `DynamoDbPersistenceAdapter`), NOT `PROJECT#alertas-civil` |
| **Port contract forbids reactive types** | No `Uni`, `Multi`, Mutiny in `application` layer — synchronous blocking calls with virtual threads only |
| **All records need `@RegisterForReflection(serialization=true)`** | Required for native image JSON serialization |

---

## DynamoDB Gotchas

| Gotcha | Fix |
|--------|-----|
| **Single-table design with SYNCHRONOUS client + virtual threads** | Not `DynamoDbAsyncClient` + Mutiny — use `DynamoDbClient` (blocking) unparked by Loom virtual threads |
| **Adapter class is `DynamoDbPersistenceAdapter`** (not `DynamoDbLabAdapter` as architecture.md suggests) | Implement `LabPersistencePort` with synchronous `DynamoDbClient` |
| **All queries use Query API, never Scan** | Use PK/SK + GSI1/GSI2; design access patterns upfront |
| **Conditional writes = optimistic locking** | Update: `Version = :expected`; Insert: `attribute_not_exists(PK) AND attribute_not_exists(SK)` |
| **Table name is configurable** (`app.dynamodb.table-name`, default `hc-alertas`) | Don't hardcode; inject via `@ConfigProperty` |

---

## Virtual Threads / Lambda Gotchas

| Gotcha | Fix |
|--------|-----|
| **Lambda handlers use `ExecutorService` from `Executors.newVirtualThreadPerTaskExecutor()`** | Inject via `@Produces @ApplicationScoped`; use `@Virtual` qualifier |
| **SQS batch: `CompletableFuture.allOf(...).join()` — one failed record doesn't fail batch** | Wrap each record in try/catch inside the future; log and continue |
| **No `synchronized` blocks** — DynamoDB client is thread-safe | Using `java.util.logging` avoids carrier thread pinning (SLF4J may pin) |
| **Native image: `< 50MB`, cold start `< 100ms` with SnapStart** | Profile `native`, container build, `ghcr.io/graalvm/native-image:25` |

---

## Quarkus / Build Gotchas

| Gotcha | Fix |
|--------|-----|
| **Native profile required for Lambda** | `mvn clean package -Pnative -Dquarkus.native.container-build=true` |
| **Reflection registration on ALL domain records** | `@RegisterForReflection(serialization=true)` or native image fails |
| **Build-time class initialization** | `--initialize-at-build-time=org.slf4j` and other heavy libs |
| **Arc (CDI) is compile-time DI** — no runtime scanning | All beans must be discoverable at build time |

---

## Clinical Thresholds Gotchas

| Gotcha | Fix |
|--------|-----|
| **Analyte codes are LOINC** (e.g., `718-7` = Hemoglobin, `2823-3` = Potassium) | See `clinical_thresholds.md` for full mapping |
| **Visibility enum: `PENDING` \| `VISIBLE` \| `CRITICAL_ALERT`** | `CRITICAL_ALERT` triggers SNS/SES notification flow |
| **Logic is in `determineVisibility(LabResult)`** — switch on `analyteCode` | Extract to unit-testable pure function |

---

## Architecture Boundaries (Hexagonal)

| Gotcha | Fix |
|--------|-----|
| **Dependencies point INWARD only** | `domain` → `application` → `infrastructure`; never reverse |
| **Ports are interfaces in `domain.port`** | `LabPersistencePort`, `NotificationPort` |
| **Adapters in `infrastructure.adapter`** | `DynamoDbPersistenceAdapter`, `SnsNotificationAdapter` |
| **Use cases in `application.usecase`** | `IngestLabUseCase`, `NotifyLabReadyUseCase` |

---

## Testing Gotchas

| Gotcha | Fix |
|--------|-----|
| **Integration: `@QuarkusTest` + Testcontainers (LocalStack)** | LocalStack for DynamoDB/SQS; not mockito for infra layer |
| **Unit tests for pure domain logic only** | No framework, no Quarkus |
| **Coverage target: ≥80% on domain + application** | Infrastructure adapters tested via integration |

---

## Quick Debug Checklist (Before Commit)

- [ ] Domain records: immutable, validated constructors, `@RegisterForReflection`
- [ ] Every DynamoDB write: OCC `ConditionExpression` on `Version`
- [ ] No reactive types (`Uni`/`Multi`) in application layer
- [ ] Virtual thread executor injected and used for blocking I/O
- [ ] Native profile builds: `< 50MB`, SnapStart enabled
- [ ] Clinical threshold logic covered by unit tests
- [ ] Hexagonal boundaries respected (no infra imports in domain/application)