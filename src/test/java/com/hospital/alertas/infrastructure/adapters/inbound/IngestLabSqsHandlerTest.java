package com.hospital.alertas.infrastructure.adapters.inbound;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.alertas.application.ports.inbound.IngestLabUseCase;
import com.hospital.alertas.domain.exception.LabValidationException;
import com.hospital.alertas.domain.exception.OptimisticLockException;
import com.hospital.alertas.infrastructure.config.CloudWatchEmfMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IngestLabSqsHandler - Tests unitarios de gestión de fallos e Idempotencia")
class IngestLabSqsHandlerTest {

    private ObjectMapper objectMapper;
    private CloudWatchEmfMetrics metrics;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        metrics = new CloudWatchEmfMetrics("test", "hc-alertas-test");
    }

    @Test
    @DisplayName("cuandoMensajeEsDuplicado_entoncesAbsorbeOptimisticLockExceptionYSinReintento()")
    void cuandoMensajeEsDuplicado_entoncesAbsorbeOptimisticLockExceptionYSinReintento() throws Exception {
        // Given: Use case that throws OptimisticLockException (simulating duplicate message already persisted)
        IngestLabUseCase useCase = command -> {
            throw new OptimisticLockException(
                    command.labId(),
                    0,
                    "Duplicate lab already exists in DynamoDB",
                    null
            );
        };

        IngestLabSqsHandler handler = new IngestLabSqsHandler(useCase, metrics, objectMapper);

        IngestLabRequest request = new IngestLabRequest(
                "dup-lab-001",
                "PAC100",
                "Hemograma",
                Map.of("Hemoglobina", "14.0 g/dL"),
                "2026-06-10T10:00:00Z",
                false,
                "s3://bucket/test.pdf"
        );

        SQSEvent event = createSqsEvent(objectMapper.writeValueAsString(request));

        // When/Then: Should absorb without throwing (prevents duplicate retries and false DLQ routing)
        assertDoesNotThrow(() -> handler.handleRequest(event, new DummyContext()));
    }

    @Test
    @DisplayName("cuandoDatosSonInvalidos_entoncesAbsorbeLabValidationException()")
    void cuandoDatosSonInvalidos_entoncesAbsorbeLabValidationException() throws Exception {
        // Given: Use case that throws LabValidationException
        IngestLabUseCase useCase = command -> {
            throw new LabValidationException("Datos corruptos");
        };

        IngestLabSqsHandler handler = new IngestLabSqsHandler(useCase, metrics, objectMapper);

        IngestLabRequest request = new IngestLabRequest(
                "lab-002",
                "PAC100",
                "Quimica",
                Map.of("Glucosa", "90 mg/dL"),
                "2026-06-10T10:00:00Z",
                false,
                "s3://bucket/test.pdf"
        );

        SQSEvent event = createSqsEvent(objectMapper.writeValueAsString(request));

        // When/Then: Should absorb without throwing (eliminates bad message from SQS queue)
        assertDoesNotThrow(() -> handler.handleRequest(event, new DummyContext()));
    }

    @Test
    @DisplayName("cuandoOcurreFalloDeInfraestructura_entoncesRelanzaExcepcionParaReintentoYDLQ()")
    void cuandoOcurreFalloDeInfraestructura_entoncesRelanzaExcepcionParaReintentoYDLQ() throws Exception {
        // Given: Use case that fails with infrastructure runtime exception (e.g. database down)
        IngestLabUseCase useCase = command -> {
            throw new RuntimeException("DynamoDB Connection Timeout");
        };

        IngestLabSqsHandler handler = new IngestLabSqsHandler(useCase, metrics, objectMapper);

        IngestLabRequest request = new IngestLabRequest(
                "lab-003",
                "PAC100",
                "Quimica",
                Map.of("Glucosa", "90 mg/dL"),
                "2026-06-10T10:00:00Z",
                false,
                "s3://bucket/test.pdf"
        );

        SQSEvent event = createSqsEvent(objectMapper.writeValueAsString(request));

        // When/Then: MUST re-throw so Lambda fails, SQS retries and eventually routes to DLQ
        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> handler.handleRequest(event, new DummyContext())
        );
        assertTrue(thrown.getMessage().contains("DynamoDB Connection Timeout") || thrown.getCause() != null);
    }

    private SQSEvent createSqsEvent(String body) {
        var event = new SQSEvent();
        var msg = new SQSEvent.SQSMessage();
        msg.setMessageId("msg-unit-001");
        msg.setBody(body);
        event.setRecords(List.of(msg));
        return event;
    }

    private static class DummyContext implements Context {
        @Override public String getAwsRequestId() { return "req-1"; }
        @Override public String getLogGroupName() { return "lg"; }
        @Override public String getLogStreamName() { return "ls"; }
        @Override public String getFunctionName() { return "fn"; }
        @Override public String getFunctionVersion() { return "1"; }
        @Override public String getInvokedFunctionArn() { return "arn"; }
        @Override public com.amazonaws.services.lambda.runtime.CognitoIdentity getIdentity() { return null; }
        @Override public com.amazonaws.services.lambda.runtime.ClientContext getClientContext() { return null; }
        @Override public int getRemainingTimeInMillis() { return 10000; }
        @Override public int getMemoryLimitInMB() { return 512; }
        @Override public com.amazonaws.services.lambda.runtime.LambdaLogger getLogger() {
            return new com.amazonaws.services.lambda.runtime.LambdaLogger() {
                @Override public void log(String message) { System.out.println("LOG: " + message); }
                @Override public void log(byte[] message) { System.out.println("LOG: " + new String(message)); }
            };
        }
    }
}
