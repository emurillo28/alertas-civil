# HCG Coding Standards

## Java
- **Records only** for domain models (immutable, no setters)
- **OCC versioning** on all mutable entities (`Integer version`)
- **Package structure**: `com.hcg.alertas.{domain,application,infrastructure}`
- **Null safety**: Validate in record constructors (`throw IllegalArgumentException`)
- **No getters/setters** — records expose fields directly

## Quarkus / Lambda
- `@RegisterForReflection(serialization=true)` on all domain records
- `@ApplicationScoped` for use cases and adapters
- Virtual threads for blocking I/O (DynamoDB, SQS batch)
- Native image profile: `-Pnative -Dquarkus.native.container-build=true`
- SnapStart enabled for Java 17+ (`AWS_LAMBDA_SNAPSHOT=true`)

## DynamoDB
- Single-table design only
- Use `DynamoDbAsyncClient` with Netty + virtual threads
- Query API over GetItem/Scan (use PK/SK/GSI)
- Conditional writes for OCC (`version = :expected`)

## Testing
- `@QuarkusTest` + `@Testcontainers` (LocalStack for DynamoDB/SQS)
- Testcontainers for integration tests
- Unit tests for pure domain logic (no framework)
- Coverage target: ≥80% on domain + application layers

## Build
```xml
<profile>
  <id>native</id>
  <properties>
    <quarkus.package.type>native</quarkus.package.type>
    <quarkus.native.container-build>true</quarkus.native.container-build>
    <quarkus.native.builder-image>ghcr.io/graalvm/native-image:25</quarkus.native.builder-image>
  </properties>
</profile>
```

## Deployment
```bash
mvn clean package -Pnative -Dquarkus.native.container-build=true
aws lambda update-function-code --function-name alertas-civil-ingest --zip-file fileb://target/function.zip
```