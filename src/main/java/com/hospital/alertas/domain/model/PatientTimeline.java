package com.hospital.alertas.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Agregado de solo lectura que representa la línea de tiempo clínica completa de un paciente.
 *
 * <p>Combina los datos demográficos (METADATA) con el historial de resultados de laboratorio (LAB#)
 * en un único objeto de dominio, optimizado para consultas de expediente completo.
 *
 * <p>Se construye mediante {@link LabPersistencePort#obtenerExpedienteCompleto(PatientId)}
 * que realiza una sola query DynamoDB por PK y filtra por SK prefixes.
 */
public record PatientTimeline(
        PatientId patientId,
        String nombre,
        Instant fechaNacimiento,
        String historialClinico,
        List<LabResult> laboratorios
) {

    /**
     * Constructor compacto con validaciones defensivas.
     */
    public PatientTimeline {
        Objects.requireNonNull(patientId, "patientId no puede ser nulo");
        // nombre, fechaNacimiento, historialClinico pueden ser null si el METADATA no existe aún
        if (laboratorios == null) {
            throw new IllegalArgumentException("La lista de laboratorios no puede ser nula");
        }
        laboratorios = List.copyOf(laboratorios); // copia defensiva inmutable
    }

    /**
     * Obtiene el resultado de laboratorio más reciente.
     *
     * @return {@link Optional} con el lab más reciente por timestamp, o vacío si no hay laboratorios
     */
    public java.util.Optional<LabResult> laboratorioMasReciente() {
        return laboratorios.stream()
                .max(java.util.Comparator.comparing(LabResult::timestamp));
    }

    /**
     * Obtiene todos los laboratorios con alerta crítica.
     *
     * @return lista inmutable de laboratorios con {@code alertaCritica == true}
     */
    public List<LabResult> laboratoriosConAlertaCritica() {
        return laboratorios.stream()
                .filter(LabResult::alertaCritica)
                .toList();
    }

    /**
     * Obtiene laboratorios en estado de visibilidad específico.
     *
     * @param estado estado de visibilidad a filtrar
     * @return lista inmutable de laboratorios con el estado dado
     */
    public List<LabResult> laboratoriosPorEstadoVisibilidad(EstadoVisibilidad estado) {
        Objects.requireNonNull(estado, "estado no puede ser nulo");
        return laboratorios.stream()
                .filter(lab -> lab.estadoVisibilidad() == estado)
                .toList();
    }

    /**
     * Verifica si el paciente tiene datos demográficos registrados.
     *
     * @return true si existe registro METADATA con nombre no nulo
     */
    public boolean tieneDatosDemograficos() {
        return nombre != null && !nombre.isBlank();
    }
}