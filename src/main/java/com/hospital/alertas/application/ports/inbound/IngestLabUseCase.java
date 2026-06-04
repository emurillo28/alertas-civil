package com.hospital.alertas.application.ports.inbound;

import com.hospital.alertas.domain.model.LabResult;

/**
 * Puerto de entrada (inbound) para el caso de uso de ingesta de laboratorios clínicos.
 *
 * <p>Define el contrato que expone la capa de aplicación hacia los adaptadores
 * de entrada (Lambda Handlers, endpoints REST). Siguiendo la Arquitectura
 * Hexagonal, los adaptadores invocan este puerto sin conocer la implementación.
 *
 * <p>El flujo de ejecución incluye:
 * <ol>
 *   <li>Validación defensiva de campos mandatorios del laboratorio.</li>
 *   <li>Evaluación automática de umbrales de alerta crítica clínica.</li>
 *   <li>Persistencia del resultado procesado vía {@code LabPersistencePort}.</li>
 * </ol>
 */
public interface IngestLabUseCase {

    /**
     * Ejecuta la ingesta de un resultado de laboratorio clínico.
     *
     * @param command resultado de laboratorio a procesar (no nulo)
     * @throws com.hospital.alertas.domain.exception.LabValidationException
     *         si los datos clínicos son corruptos o violan invariantes de negocio
     */
    void execute(LabResult command);
}
