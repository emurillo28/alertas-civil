package com.hospital.alertas.domain.model;

/**
 * Enumera los canales disponibles para enviar notificaciones clínicas.
 *
 * <p>El canal determina el medio de comunicación con el médico
 * o personal de salud responsable de atender el resultado.
 *
 * <p>Diseñado para extensión: nuevos canales (WhatsApp, Pager) se agregan
 * aquí sin modificar el dominio existente ni los adaptadores actuales.
 */
public enum NotificationChannel {

    /** Mensaje de texto al número registrado del destinatario. */
    SMS,

    /** Mensaje de texto al número registrado del destinatario. */
    WHATSAPP,

    /** Correo electrónico institucional del destinatario. */
    EMAIL,

    /** Notificación push a la app móvil HCG-v1 del destinatario. */
    PUSH,

    /**
     * Notificación visible únicamente dentro de la aplicación web/móvil.
     * No genera comunicación externa. Útil para alertas de baja urgencia.
     */
    IN_APP
}