# HCG Quarkus Lambda Patterns

## Native Image Configuration

### pom.xml Profile
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

### Reflection Registration
```java
@RegisterForReflection(serialization = true)
public record LabResult(...) {}
```

### Build Args
```properties
quarkus.native.additional-build-args=-H:+AllowIncompleteClasspath \
  -H:ReflectionConfigurationFiles=reflect-config.json \
  --initialize-at-build-time=org.slf4j
```

## Lambda Handlers

### SQS Batch Processor (Virtual Threads)
```java
@ApplicationScoped
public class BatchLabIngestHandler implements RequestHandler<SQSEvent, Void> {
    
    @Inject IngestLabUseCase useCase;
    @Inject ExecutorService virtualExecutor; // Virtual thread executor
    
    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        List<CompletableFuture<Void>> futures = event.getRecords().stream()
            .map(record -> CompletableFuture.runAsync(() -> {
                LabResult result = parseRecord(record);
                useCase.execute(result);
            }, virtualExecutor))
            .toList();
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return null;
    }
}
```

### Simple Handler
```java
@ApplicationScoped
public class IngestLabHandler implements RequestHandler<SQSEvent, Void> {
    @Inject IngestLabUseCase useCase;
    
    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        event.getRecords().forEach(record -> {
            LabResult result = JsonbBuilder.create().fromJson(record.getBody(), LabResult.class);
            useCase.execute(result);
        });
        return null;
    }
}
```

## Cold Start Optimization
- **SnapStart**: Enable for Java 17+ (`AWS_LAMBDA_SNAPSHOT=true`)
- **Provisioned Concurrency**: 2-5 for predictable latency
- **Compile-time DI**: Quarkus Arc eliminates runtime scanning
- **Class initialization**: Move heavy init to build time with `--initialize-at-build-time`

## Targets
- Native binary: **< 50MB**
- Cold start: **< 100ms** (with SnapStart)