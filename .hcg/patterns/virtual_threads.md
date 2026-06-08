# HCG Virtual Threads Pattern

## Configuration
```java
@ApplicationScoped
public class VirtualThreadConfig {
    @Produces
    @ApplicationScoped
    ExecutorService virtualExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
```

## SQS Batch Processing
```java
@ApplicationScoped
public class BatchLabIngestHandler implements RequestHandler<SQSEvent, Void> {
    
    @Inject IngestLabUseCase useCase;
    @Inject @Virtual ExecutorService executor;
    
    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        List<CompletableFuture<Void>> futures = event.getRecords().stream()
            .map(record -> CompletableFuture.runAsync(() -> {
                try {
                    LabResult result = parseRecord(record);
                    useCase.execute(result);
                } catch (Exception e) {
                    // Log and continue - don't fail entire batch
                    logger.error("Failed to process record", e);
                }
            }, executor))
            .toList();
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return null;
    }
}
```

## DynamoDB Async + Virtual Threads
```java
DynamoDbAsyncClient.builder()
    .httpClientBuilder(NettyNioAsyncHttpClient.builder()
        .maxConcurrency(1000))
    .overrideConfiguration(b -> b
        .apiCallTimeout(Duration.ofSeconds(10))
        .retryStrategy(RetryMode.STANDARD))
    .build();
```

## Benefits Over Reactive
- Simpler debugging (stack traces preserved)
- Blocking DynamoDB calls work naturally
- No reactive stream operators to learn
- Same performance for I/O-bound work