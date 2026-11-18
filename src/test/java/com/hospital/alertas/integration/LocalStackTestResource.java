package com.hospital.alertas.integration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;

/**
 * Quarkus Test Resource that manages a LocalStack container for integration tests.
 * Uses Testcontainers to spin up LocalStack 3.4.0 (pinned to avoid Pro license issues).
 *
 * <p>This resource is automatically discovered by Quarkus when placed in the test classpath
 * and annotated with {@code @QuarkusTestResource} on integration test classes.
 *
 * <p>The container provides: DynamoDB, S3, and SQS services.
 */
public class LocalStackTestResource implements QuarkusTestResourceLifecycleManager {

    private static final String LOCALSTACK_IMAGE = "localstack/localstack:3.4.0";
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse(LOCALSTACK_IMAGE))
            .withServices(
                    LocalStackContainer.Service.DYNAMODB,
                    LocalStackContainer.Service.S3,
                    LocalStackContainer.Service.SQS
            )
            .withEnv("DEFAULT_REGION", "us-east-1")
            .withEnv("AWS_DEFAULT_REGION", "us-east-1")
            .withReuse(false); // Fresh container per test run for isolation

    @Override
    public Map<String, String> start() {
        LOCALSTACK.start();

        String endpoint = LOCALSTACK.getEndpointOverride(null).toString();
        String region = LOCALSTACK.getRegion();

        System.out.println("=== LocalStack Started ===");
        System.out.println("Endpoint: " + endpoint);
        System.out.println("Region: " + region);
        System.out.println("Services: dynamodb, s3, sqs");

        // Initialize DynamoDB table
        try (DynamoDbClient ddb = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
                .build()) {
            ddb.createTable(CreateTableRequest.builder()
                    .tableName("hc-alertas-test")
                    .keySchema(
                            KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build()
                    )
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build()
                    )
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
        } catch (Exception e) {
            System.err.println("Could not initialize test DynamoDB table: " + e.getMessage());
        }

        // Initialize SQS queues
        try (SqsClient sqs = SqsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
                .build()) {
            sqs.createQueue(CreateQueueRequest.builder().queueName("notification-queue").build());
            sqs.createQueue(CreateQueueRequest.builder().queueName("test-queue").build());
        } catch (Exception e) {
            System.err.println("Could not initialize test SQS queues: " + e.getMessage());
        }

        Map<String, String> config = new HashMap<>();
        config.put("quarkus.dynamodb.endpoint-override", endpoint);
        config.put("quarkus.s3.endpoint-override", endpoint);
        config.put("quarkus.sqs.endpoint-override", endpoint);
        config.put("quarkus.dynamodb.aws.region", region);
        config.put("quarkus.s3.aws.region", region);
        config.put("quarkus.sqs.aws.region", region);
        config.put("app.dynamodb.table-name", "hc-alertas-test");
        config.put("app.sqs.notification-queue-url", endpoint + "/000000000000/notification-queue");

        // DynamoDB Client Credentials
        config.put("quarkus.dynamodb.aws.credentials.type", "static");
        config.put("quarkus.dynamodb.aws.credentials.static-provider.access-key-id", "test-key");
        config.put("quarkus.dynamodb.aws.credentials.static-provider.secret-access-key", "test-secret");

        // S3 Client Credentials
        config.put("quarkus.s3.aws.credentials.type", "static");
        config.put("quarkus.s3.aws.credentials.static-provider.access-key-id", "test-key");
        config.put("quarkus.s3.aws.credentials.static-provider.secret-access-key", "test-secret");

        // SQS Client Credentials
        config.put("quarkus.sqs.aws.credentials.type", "static");
        config.put("quarkus.sqs.aws.credentials.static-provider.access-key-id", "test-key");
        config.put("quarkus.sqs.aws.credentials.static-provider.secret-access-key", "test-secret");

        // Global fallback
        config.put("quarkus.aws.credentials.type", "static");
        config.put("quarkus.aws.credentials.static-provider.access-key-id", "test-key");
        config.put("quarkus.aws.credentials.static-provider.secret-access-key", "test-secret");
        return config;
    }

    @Override
    public void stop() {
        System.out.println("=== Stopping LocalStack ===");
        LOCALSTACK.stop();
    }
}