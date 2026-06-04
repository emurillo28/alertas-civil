package com.hospital.alertas.application.service;

import com.hospital.alertas.application.ports.outbound.LabPersistencePort;
import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.PatientId;

import java.util.*;

/**
 * Implementación fake in-memory de {@link LabPersistencePort} para tests unitarios.
 *
 * <p>Usa un {@code HashMap} interno para simular la persistencia.
 * Permite inspeccionar el estado del "almacén" después de ejecutar
 * el caso de uso, verificando side-effects sin infraestructura real.
 *
 * <p>Sigue la regla 5.A del .cursorrules: tests con fakes in-memory,
 * NUNCA mocks genéricos de Mockito.
 */
public class InMemoryLabPersistence implements LabPersistencePort {

    /**
     * Almacén interno: PK (patientId.value()) → lista de labs ordenada por inserción.
     */
    private final Map<String, List<LabResult>> store = new HashMap<>();

    @Override
    public void saveLab(LabResult lab) {
        Objects.requireNonNull(lab, "LabResult no puede ser nulo");
        store.computeIfAbsent(lab.patientId().value(), _ -> new ArrayList<>())
                .add(lab);
    }

    @Override
    public Optional<LabResult> findLatestByPatient(PatientId patientId) {
        Objects.requireNonNull(patientId, "PatientId no puede ser nulo");
        List<LabResult> labs = store.getOrDefault(patientId.value(), List.of());
        if (labs.isEmpty()) {
            return Optional.empty();
        }
        // Retorna el último insertado (simula orden por timestamp descendente)
        return Optional.of(labs.getLast());
    }

    // ─── Métodos de inspección para tests ───────────────────────────────

    /**
     * @return todos los labs almacenados para un paciente
     */
    public List<LabResult> findAllByPatient(PatientId patientId) {
        return List.copyOf(store.getOrDefault(patientId.value(), List.of()));
    }

    /**
     * @return el último lab guardado globalmente, o empty si no hay ninguno
     */
    public Optional<LabResult> getLastSaved() {
        return store.values().stream()
                .flatMap(List::stream)
                .reduce((_, second) -> second);
    }

    /**
     * @return la cantidad total de labs almacenados
     */
    public int count() {
        return store.values().stream().mapToInt(List::size).sum();
    }

    /**
     * @return true si el almacén está vacío
     */
    public boolean isEmpty() {
        return store.isEmpty() || store.values().stream().allMatch(List::isEmpty);
    }

    /**
     * Limpia todo el almacén.
     */
    public void clear() {
        store.clear();
    }
}
