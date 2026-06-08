package com.hospital.alertas.application.ports.inbound;

import com.hospital.alertas.application.service.NotifyLabReadyCommand;

/**
 * Puerto de entrada (inbound) para el caso de uso de notificación de laboratorio listo.
 *
 * <p>Invocado por el adaptador HTTP ({@code NotifyLabHttpHandler}) cuando:
 * <ul>
 *   <li>El sistema externo del Hospital Civil notifica que un lab está disponible.</li>
 *   <li>Un operador dispara manualmente la notificación desde la interfaz.</li>
 * </ul>
 *
 * <p>El caso de uso es idempotente por diseño: si el lab ya fue notificado,
 * registra la nueva notificación en el historial sin lanzar excepción.
 */
public interface NotifyLabReadyUseCase {

    /**
     * Ejecuta el flujo de notificación para un resultado de laboratorio.
     *
     * <p>Flujo interno:
     * <ol>
     *   <li>Recupera el lab desde DynamoDB.</li>
     *   <li>Crea una {@code SentNotification} en estado PENDIENTE.</li>
     *   <li>Actualiza el lab en DynamoDB con la notificación y estado NOTIFICADO.</li>
     *   <li>Encola la notificación en SQS para entrega por el canal externo.</li>
     * </ol>
     *
     * @param command datos de la notificación a enviar (no nulo)
     * @throws com.hospital.alertas.domain.exception.LabValidationException
     *         si el lab no existe o los datos del comando son inválidos
     */
    void execute(NotifyLabReadyCommand command);
}