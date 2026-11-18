# HCG Virtual Threads Pattern

## Core Principle
Virtual threads (Project Loom in Java 25) replace reactive programming across the entire codebase. Network calls against AWS (DynamoDB, SQS, S3) use standard synchronous clients (`DynamoDbClient`, `SqsClient`). The JVM automatically unparks virtual threads during network waits without carrier thread pinning (JEP 491).

## Benefits Over Reactive
- Stack traces are sequential and easy to debug.
- Blocking synchronous AWS SDK clients behave naturally without wrapping in `Uni` or callbacks.
- Elimination of reactive mental model and Mutiny dependencies.

## SQS Batch Processing Pattern
```java
@ApplicationScoped
public class IngestLabSqsHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    @Inject
    IngestLabUseCase ingestLabUseCase;

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new CopyOnWriteArrayList<>();

        // Structured concurrency or virtual thread dispatch per record
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (SQSEvent.SQSMessage message : event.getRecords()) {
                executor.submit(() -> {
                    try {
                        processRecord(message);
                    } catch (LabValidationException e) {
                        // Permanent payload error: do not retry (absorb)
                    } catch (Exception e) {
                        // Transient error: mark for retry / DLQ
                        failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
                    }
                });
            }
        }

        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }
}
```

## Synchronous DynamoDB Client Integration
```java
@ApplicationScoped
public class DynamoDbPersistenceAdapter implements LabPersistencePort {

    private final DynamoDbClient dynamoDbClient; // Standard blocking client

    public DynamoDbPersistenceAdapter(
            DynamoDbClient dynamoDbClient,
            @ConfigProperty(name = "app.dynamodb.table-name") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public void save(LabResult lab) {
        // Synchronous call deschedules virtual thread cleanly
        dynamoDbClient.putItem(buildPutItemRequest(lab));
    }
}
```
