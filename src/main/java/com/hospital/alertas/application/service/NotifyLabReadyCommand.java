package com.hospital.alertas.application.service;

import com.hospital.alertas.domain.model.PatientId;
import java.util.Objects;

/**
 * Comando de entrada para el caso de uso de notificación de laboratorio listo.
 * <p>
 * Encapsula los datos mínimos necesarios para disparar la notificación de un
 * resultado de laboratorio hacia el canal correspondiente (email, SMS, push, etc.).
 *
 * <p>Inmutable por diseño (Java Record). Validación defensiva en constructor compacto.
 */
public record NotifyLabReadyCommand(

        /** Identificador único del resultado de laboratorio (no nulo, no vacío). */
        String labId,

        /** Identificador del paciente propietario del laboratorio (no nulo). */
        PatientId patientId,

        /** Canal de notificación a utilizar (ej: EMAIL, SMS, PUSH). Opcional: si nulo, se usa canal por defecto. */
        String canalNotificacion,

        /** Destinatario de la notificación (ej: email del médico, número de teléfono). Opcional: si nulo, se resuelve desde configuración. */
        String destinatario

) {

    /**
     * Constructor compacto con validaciones defensivas.
     * Garantiza integridad de los datos antes de entrar al dominio.
     */
    public NotifyLabReadyCommand {
        Objects.requireNonNull(labId, "labId no puede ser nulo");
        if (labId.isBlank()) {
            throw new IllegalArgumentException("labId no puede estar vacío");
        }
        Objects.requireNonNull(patientId, "patientId no puede ser nulo");
        // canalNotificacion y destinatario pueden ser null (se resuelven en el adaptador de salida)
        if (destinatario != null && destinatario.isBlank()) {
            throw new IllegalArgumentException("destinatario no puede estar vacío si se proporciona");
        }
    }
}