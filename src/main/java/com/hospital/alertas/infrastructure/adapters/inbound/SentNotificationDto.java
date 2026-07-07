package com.hospital.alertas.infrastructure.adapters.inbound;

import java.time.Instant;

/**
 * REST DTO for a single sent notification.
 *
 * <p>Exposes the notification audit trail inline within a
 * {@link LabResultDto}. No domain logic is carried here —
 * this is a pure projection.
 */
public record SentNotificationDto(
        String notificationId,
        String recipient,
        String channel,
        String status,
        String timestampSend,
        String timestampRead
) {
}
