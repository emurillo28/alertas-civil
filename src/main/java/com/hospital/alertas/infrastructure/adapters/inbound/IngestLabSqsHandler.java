package com.hospital.alertas.infrastructure.adapters.inbound;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.alertas.application.ports.inbound.IngestLabUseCase;
import com.hospital.alertas.domain.exception.LabValidationException;
import com.hospital.alertas.domain.exception.OptimisticLockException;
import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.infrastructure.config.CloudWatchEmfMetrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler de AWS Lambda que actúa como adaptador de entrada para eventos SQS.
 *
 * <p>Esta clase implementa la lógica de desacoplamiento y tolerancia a fallos:
 * <ol>
 *   <li>Recibe lotes de mensajes desde la cola de ingesta de SQS.</li>
 *   <li>Deserializa cada mensaje JSON a {@link IngestLabRequest} usando Jackson.</li>
 *   <li>Llama al caso de uso de negocio {@link IngestLabUseCase}.</li>
 *   <li>Registra el éxito o el tipo de error en formato EMF para CloudWatch.</li>
 *   <li>Aplica gestión de excepciones diferencial para SQS (DLQ nativa):
 *     <ul>
 *       <li><strong>Errores Clínicos (LabValidationException):</strong> Se consideran fallos
 *       del cliente. Se registran y se absorbe la excepción. El mensaje se elimina
 *       de la cola origen de SQS, ya que reintentar datos corruptos es fútil.</li>
 *       <li><strong>Errores del Sistema (SdkException, fallos de red/DB):</strong> Se consideran
 *       fallos de infraestructura. Se registran y se relanza la excepción. AWS Lambda
 *       marcará el procesamiento como fallido, activando el ciclo de reintentos nativos
 *       de SQS y su posterior descarte automático a la DLQ.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p><strong>Project Loom:</strong> Al ejecutarse en AWS Lambda tradicional, la JVM administra
 * la ejecución de forma secuencial y bloqueante. Este handler delega de forma síncrona
 * liberando al sistema de la complejidad de programación reactiva.
 */
@Named("ingestLabSqsHandler")
@ApplicationScoped
public class IngestLabSqsHandler implements RequestHandler<SQSEvent, Void> {

    private static final Logger LOG = Logger.getLogger(IngestLabSqsHandler.class.getName());

    private final IngestLabUseCase ingestLabUseCase;
    private final CloudWatchEmfMetrics metrics;
    private final ObjectMapper objectMapper;

    /**
     * Constructor con inyección CDI compatible con Quarkus.
     *
     * @param ingestLabUseCase caso de uso de ingesta (inyectado como IngestLabService)
     * @param metrics          registrador de métricas EMF
     * @param objectMapper     mapeador JSON de Jackson
     */
    public IngestLabSqsHandler(
            IngestLabUseCase ingestLabUseCase,
            CloudWatchEmfMetrics metrics,
            ObjectMapper objectMapper
    ) {
        this.ingestLabUseCase = Objects.requireNonNull(ingestLabUseCase, "ingestLabUseCase no puede ser nulo");
        this.metrics = Objects.requireNonNull(metrics, "metrics no puede ser nulo");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper no puede ser nulo");
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        if (event == null || event.getRecords() == null) {
            LOG.warning("Recibido evento SQS nulo o sin registros.");
            return null;
        }

        LOG.fine(() -> "Procesando lote SQS de " + event.getRecords().size() + " registros");

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            long startTime = System.nanoTime();
            try {
                String body = message.getBody();
                IngestLabRequest request = objectMapper.readValue(body, IngestLabRequest.class);
                LabResult lab = request.toDomain();

                ingestLabUseCase.execute(lab);

                double duration = (System.nanoTime() - startTime) / 1_000_000_000.0;
                metrics.logSuccess(duration);
                LOG.fine(() -> "Mensaje SQS " + message.getMessageId() + " (Lab " + lab.labId() + ") procesado con éxito.");

            } catch (LabValidationException | IllegalArgumentException e) {
                // Fail Fast para datos clínicos inválidos (Error del cliente)
                metrics.logValidationError();
                LOG.log(Level.WARNING, "Error de validación clínica en mensaje SQS " + message.getMessageId() + ": " + e.getMessage());
                // Absorber para eliminar de la cola de ingesta

            } catch (OptimisticLockException e) {
                // Idempotencia: El laboratorio ya fue persistido previamente (duplicado en SQS)
                metrics.logIdempotentDuplicateAbsorbed();
                LOG.log(Level.INFO, "Duplicado idempotente detectado y absorbido para mensaje SQS {0} (Lab {1}): {2}",
                        new Object[]{message.getMessageId(), e.getEntityId(), e.getMessage()});
                // Absorber para confirmar ACK a SQS y evitar bucle de reintentos hacia la DLQ

            } catch (Exception e) {
                // Error de infraestructura (Error del sistema)
                metrics.logInfrastructureError();
                LOG.log(Level.SEVERE, "Fallo de infraestructura procesando mensaje SQS " + message.getMessageId() + ". Relanzando para reintento y descarte a DLQ.", e);
                // Relanzar para notificar fallo a AWS Lambda
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                } else {
                    throw new RuntimeException("Error inesperado en procesamiento de SQS", e);
                }
            }
        }

        return null;
    }
}
