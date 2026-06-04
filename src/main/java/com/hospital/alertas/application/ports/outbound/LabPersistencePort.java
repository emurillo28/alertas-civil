package com.hospital.alertas.application.ports.outbound;

import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.PatientId;

import java.util.Optional;

/**
 * Puerto de salida (outbound) para la persistencia de resultados de laboratorio.
 *
 * <p>Define el contrato que debe implementar cualquier adaptador de persistencia
 * (DynamoDB, base de datos en memoria para tests, etc.) siguiendo el principio
 * de inversión de dependencias de la Arquitectura Hexagonal.
 *
 * <p>Todos los métodos son <strong>síncronos y bloqueantes</strong> por diseño.
 * La concurrencia se gestiona nativamente mediante Project Loom (hilos virtuales)
 * en la capa de infraestructura, no mediante APIs reactivas.
 *
 * <p><strong>Restricción arquitectónica:</strong> Esta interfaz pertenece a la capa
 * de aplicación y tiene prohibido importar cualquier paquete de AWS SDK
 * ({@code software.amazon.awssdk.*}) o anotaciones de Quarkus.
 */
public interface LabPersistencePort {

    /**
     * Persiste un resultado de laboratorio clínico.
     *
     * <p>La implementación debe garantizar Control de Concurrencia Optimista (OCC)
     * utilizando el campo {@link LabResult#version()} mediante
     * {@code ConditionExpression} de DynamoDB.
     *
     * @param lab resultado de laboratorio a persistir (no nulo)
     * @throws com.hospital.alertas.domain.exception.LabValidationException
     *         si los datos del laboratorio violan invariantes de negocio
     * @throws RuntimeException si ocurre un conflicto de concurrencia (OCC)
     *         o un error de infraestructura
     */
    void saveLab(LabResult lab);

    /**
     * Recupera el resultado de laboratorio más reciente de un paciente.
     *
     * <p>La implementación debe consultar por PK = PATIENT#<patientId>
     * con SK que comience con "LAB#" y ordenar descendentemente por timestamp
     * para retornar el más reciente.
     *
     * @param patientId identificador del paciente (no nulo)
     * @return {@link Optional} con el laboratorio más reciente, o vacío
     *         si el paciente no tiene laboratorios registrados
     */
    Optional<LabResult> findLatestByPatient(PatientId patientId);
}
