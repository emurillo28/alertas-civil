package com.hospital.alertas.infrastructure.adapters.outbound;

import com.hospital.alertas.application.ports.outbound.NotificationPort;
import com.hospital.alertas.domain.model.PatientId;
import com.hospital.alertas.domain.model.SentNotification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Infrastructure adapter that implements {@link NotificationPort} using AWS SQS.
 *
 * <p>Enqueues clinical notifications as JSON messages in an SQS queue for
 * asynchronous processing by downstream channel delivery workers.
 */
@ApplicationScoped
public class SqsNotificationAdapter implements NotificationPort {

    private static final Logger LOGGER = Logger.getLogger(SqsNotificationAdapter.class);

    private final SqsClient sqsClient;
    private final String queueUrl;

    @Inject
    public SqsNotificationAdapter(SqsClient sqsClient,
                                  @ConfigProperty(name = "app.sqs.notification-queue-url") String queueUrl) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
    }

    @Override
    public void enqueueNotification(PatientId patientId, String labId, SentNotification notification) {
        try {
            String messageBody = String.format(
                    "{\"notificationId\":\"%s\",\"patientId\":\"%s\",\"labId\":\"%s\",\"recipient\":\"%s\",\"channel\":\"%s\",\"status\":\"%s\"}",
                    notification.notificationId(),
                    patientId.value(),
                    labId,
                    notification.recipient(),
                    notification.channel(),
                    notification.status()
            );

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .build();

            sqsClient.sendMessage(request);
            LOGGER.infof("Notification enqueued: notificationId=%s, queueUrl=%s", notification.notificationId(), queueUrl);
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to enqueue notification: notificationId=%s", notification.notificationId());
            throw new RuntimeException("SQS enqueue failed", e);
        }
    }
}
