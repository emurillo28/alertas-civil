package com.hospital.alertas.application.service;

import com.hospital.alertas.domain.model.NotificationChannel;

/**
 * Command to trigger the notification flow for a laboratory result.
 *
 * <p>
 * This is a mutable command object (not a domain record) because it carries
 * transient input from the UI/integration layer before being converted into
 * domain objects.
 */
public class NotifyLabReadyCommand {

    private final String labId;
    private final String patientId;
    private final String recipient;
    private final NotificationChannel channel;

    public NotifyLabReadyCommand(String labId, String patientId, String recipient, NotificationChannel channel) {
        this.labId = labId;
        this.patientId = patientId;
        this.recipient = recipient;
        this.channel = channel;
    }

    public String labId() {
        return labId;
    }

    public String patientId() {
        return patientId;
    }

    public String recipient() {
        return recipient;
    }

    public NotificationChannel channel() {
        return channel;
    }
}
