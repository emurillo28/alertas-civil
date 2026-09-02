package com.hospital.alertas.application.service;

import com.hospital.alertas.application.ports.inbound.NotifyLabReadyUseCase;
import com.hospital.alertas.application.ports.outbound.LabPersistencePort;
import com.hospital.alertas.application.ports.outbound.NotificationPort;
import com.hospital.alertas.domain.exception.LabValidationException;
import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.NotificationChannel;
import com.hospital.alertas.domain.model.PatientId;
import com.hospital.alertas.domain.model.SentNotification;
import com.hospital.alertas.domain.model.VisibilityStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Objects;

/**
 * Application service that implements the notify-lab-ready use case.
 *
 * <p>Orchestrates the notification flow:
 * <ol>
 *   <li>Retrieves the lab from DynamoDB via {@link LabPersistencePort}.</li>
 *   <li>Creates a {@link SentNotification} in PENDING status.</li>
 *   <li>Updates the lab in DynamoDB with the notification and NOTIFIED status (OCC).</li>
 *   <li>Enqueues the notification in SQS for delivery via external channel.</li>
 * </ol>
 */
@ApplicationScoped
public class NotifyLabReadyService implements NotifyLabReadyUseCase {

    private static final Logger LOGGER = Logger.getLogger(NotifyLabReadyService.class);

    private final LabPersistencePort labPersistencePort;
    private final NotificationPort notificationPort;

    @Inject
    public NotifyLabReadyService(LabPersistencePort labPersistencePort, NotificationPort notificationPort) {
        this.labPersistencePort = labPersistencePort;
        this.notificationPort = notificationPort;
    }

    @Override
    public void execute(NotifyLabReadyCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        PatientId patientId = new PatientId(command.patientId());
        LOGGER.infof("Executing notify-lab-ready for lab %s, patient %s, channel %s",
                command.labId(), command.patientId(), command.channel());

        // 1. Retrieve the lab
        LabResult lab = labPersistencePort.findLatestByPatient(patientId)
                .filter(l -> command.labId().equals(l.labId()))
                .orElseThrow(() -> new LabValidationException(
                        "LabResult not found: labId=" + command.labId() + ", patientId=" + command.patientId()));

        // 2. Create notification
        SentNotification notification = SentNotification.create(command.recipient(), command.channel());

        // 3. Update lab with notification + NOTIFIED status (OCC)
        long nextVersion = lab.version() + 1;
        LabResult updatedLab = lab
                .withNotificationAdded(notification)
                .withVisibilityStatus(VisibilityStatus.NOTIFIED)
                .withVersionIncremented();

        labPersistencePort.registerNotification(
                patientId,
                command.labId(),
                notification,
                VisibilityStatus.NOTIFIED,
                lab.version()
        );

        // 4. Enqueue for delivery
        notificationPort.enqueueNotification(patientId, command.labId(), notification);

        LOGGER.infof("Notification registered for lab %s: notificationId=%s, channel=%s, status=%s",
                command.labId(), notification.notificationId(), command.channel(), notification.status());
    }
}
