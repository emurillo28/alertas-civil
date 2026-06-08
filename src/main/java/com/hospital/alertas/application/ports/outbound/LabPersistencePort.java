package com.hospital.alertas.application.ports.outbound;

import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.NotificacionEnviada;
import com.hospital.alertas.domain.model.PatientId;
import com.hospital.alertas.domain.model.PatientTimeline;
import com.hospital.alertas.domain.model.EstadoVisibilidad;

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
 *
 * <p>Semántica clínica en lugar de CRUD genérico para expresar dominio médico.
 */
public interface LabPersistencePort {

    /**
     * Guarda un resultado de laboratorio clínico (crea o actualiza con OCC).
     *
     * <p>La implementación debe garantizar Control de Concurrencia Optimista (OCC)
     * utilizando el campo {@link LabResult#version()} mediante
     * {@code ConditionExpression} de DynamoDB.
     *
     * <p>Para alertas críticas, escribe GSI1_PK = 'STATUS#PENDING' para
     * habilitar consulta eficiente de laboratorios pendientes de notificar.
     *
     * @param lab resultado de laboratorio a persistir (no nulo, contiene patientId)
     * @throws com.hospital.alertas.domain.exception.LabValidationException
     *         si los datos del laboratorio violan invariantes de negocio
     * @throws OptimisticLockException si ocurre un conflicto de concurrencia (OCC)
     * @throws DatabaseThrottledException si la capacidad de la base de datos se excede
     */
    void saveLab(LabResult lab);

    /**
     * Obtiene el resultado de laboratorio más reciente de un paciente.
     *
     * <p>La implementación debe consultar por PK = PATIENT#&lt;patientId&gt;
     * con SK que comience con "LAB#" y ordenar descendentemente por timestamp
     * para retornar el más reciente.
     *
     * @param patientId identificador del paciente (no nulo)
     * @return {@link Optional} con el laboratorio más reciente, o vacío
     *         si el paciente no tiene laboratorios registrados
     * @throws DatabaseThrottledException si la capacidad de lectura se excede
     */
    Optional<LabResult> obtenerUltimoLaboratorio(PatientId patientId);

    /**
     * Obtiene el expediente clínico completo del paciente.
     *
     * <p>Realiza una sola query por PK = PATIENT#&lt;patientId&gt; y filtra
     * SK = METADATA (demográficos) y SK comenzando con LAB# (historial).
     *
     * @param patientId identificador del paciente (no nulo)
     * @return {@link Optional} con {@link PatientTimeline} conteniendo
     *         datos demográficos y lista de laboratorios, o vacío si no existe
     * @throws DatabaseThrottledException si la capacidad de lectura se excede
     */
    Optional<PatientTimeline> obtenerExpedienteCompleto(PatientId patientId);

    /**
     * Actualiza el estado de visibilidad de un laboratorio usando OCC.
     *
     * <p>Ejecuta UpdateItem con ConditionExpression sobre el campo Version.
     * Transiciones válidas: PENDIENTE → NOTIFICADO → CONFIRMADO_LECTURA.
     *
     * @param patientId     identificador del paciente
     * @param labId         identificador del laboratorio
     * @param nuevoEstado   nuevo estado de visibilidad
     * @param expectedVersion versión esperada para OCC
     * @throws OptimisticLockException si la versión no coincide (conflicto concurrente)
     * @throws DatabaseThrottledException si la capacidad de escritura se excede
     */
    void actualizarEstadoVisibilidad(PatientId patientId, String labId,
                                      EstadoVisibilidad nuevoEstado, long expectedVersion);

    /**
     * Registra una notificación en el historial del laboratorio y actualiza
     * el estado de visibilidad en una operación atómica con OCC.
     *
     * <p>Agrega la notificación a la lista {@code NotificacionesEnviadas},
     * actualiza {@code EstadoVisibilidad} y incrementa {@code Version}
     * mediante UpdateItem con ConditionExpression.
     *
     * @param patientId       identificador del paciente
     * @param labId           identificador del laboratorio
     * @param notificacion    notificación a registrar
     * @param nuevoEstado     nuevo estado de visibilidad (usualmente NOTIFICADO)
     * @param expectedVersion versión esperada para OCC
     * @throws OptimisticLockException si la versión no coincide
     * @throws DatabaseThrottledException si la capacidad de escritura se excede
     */
    void registrarNotificacion(PatientId patientId, String labId,
                                NotificacionEnviada notificacion,
                                EstadoVisibilidad nuevoEstado, long expectedVersion);
}