package com.hospital.alertas.application.ports.inbound;

import com.hospital.alertas.application.service.NotifyLabReadyCommand;

/**
 * Inbound port for the notify-lab-ready use case.
 *
 * <p>Invoked by the HTTP adapter ({@code NotifyLabHttpHandler}) when:
 * <ul>
 *   <li>The external Hospital Civil system notifies that a lab is available.</li>
 *   <li>An operator manually triggers notification from the UI.</li>
 * </ul>
 *
 * <p>The use case is idempotent by design: if the lab was already notified,
 * it registers the new notification in history without throwing an exception.
 */
public interface NotifyLabReadyUseCase {

    /**
     * Executes the notification flow for a laboratory result.
     *
     * <p>Internal flow:
     * <ol>
     *   <li>Retrieves the lab from DynamoDB.</li>
     *   <li>Creates a {@code SentNotification} in PENDING status.</li>
     *   <li>Updates the lab in DynamoDB with the notification and NOTIFIED status.</li>
     *   <li>Enqueues the notification in SQS for delivery via external channel.</li>
     * </ol>
     *
     * @param command notification data to send (not null)
     * @throws com.hospital.alertas.domain.exception.LabValidationException
     *         if lab doesn't exist or command data is invalid
     */
    void execute(NotifyLabReadyCommand command);
}