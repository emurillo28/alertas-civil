package com.hospital.alertas.integration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

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

        return Map.of(
                "quarkus.dynamodb.endpoint-override", endpoint,
                "quarkus.s3.endpoint-override", endpoint,
                "quarkus.sqs.endpoint-override", endpoint,
                "quarkus.dynamodb.aws.region", region,
                "quarkus.s3.aws.region", region,
                "quarkus.sqs.aws.region", region,
                "app.dynamodb.table-name", "hc-alertas-test"
        );
    }

    @Override
    public void stop() {
        System.out.println("=== Stopping LocalStack ===");
        LOCALSTACK.stop();
    }
}