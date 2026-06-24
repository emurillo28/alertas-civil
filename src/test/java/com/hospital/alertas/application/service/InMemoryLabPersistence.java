package com.hospital.alertas.application.service;

import com.hospital.alertas.application.ports.outbound.LabPersistencePort;
import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.PatientId;
import com.hospital.alertas.domain.model.PatientTimeline;
import com.hospital.alertas.domain.model.SentNotification;
import com.hospital.alertas.domain.model.VisibilityStatus;

import java.util.*;
import java.util.Optional;

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

    @Override
    public Optional<PatientTimeline> findCompleteRecord(PatientId patientId) {
        Objects.requireNonNull(patientId, "PatientId no puede ser nulo");
        List<LabResult> labs = store.getOrDefault(patientId.value(), List.of());
        if (labs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PatientTimeline(patientId, null, null, null, labs));
    }

    @Override
    public void updateVisibilityStatus(PatientId patientId, String labId,
                                        VisibilityStatus newStatus, long expectedVersion) {
        Objects.requireNonNull(patientId, "PatientId no puede ser nulo");
        Objects.requireNonNull(labId, "labId no puede ser nulo");
        Objects.requireNonNull(newStatus, "newStatus no puede ser nulo");

        List<LabResult> labs = store.getOrDefault(patientId.value(), List.of());
        for (int i = 0; i < labs.size(); i++) {
            LabResult lab = labs.get(i);
            if (lab.labId().equals(labId)) {
                if (lab.version() != expectedVersion) {
                    throw new IllegalStateException("Version mismatch: expected " + expectedVersion + ", found " + lab.version());
                }
                LabResult updated = lab.withEstadoVisibilidad(newStatus).withIncrementedVersion();
                labs.set(i, updated);
                return;
            }
        }
        throw new NoSuchElementException("Lab not found: " + labId);
    }

    @Override
    public void registerNotification(PatientId patientId, String labId,
                                      SentNotification notification,
                                      VisibilityStatus newStatus, long expectedVersion) {
        Objects.requireNonNull(patientId, "PatientId no puede ser nulo");
        Objects.requireNonNull(labId, "labId no puede ser nulo");
        Objects.requireNonNull(notification, "notification no puede ser nulo");
        Objects.requireNonNull(newStatus, "newStatus no puede ser nulo");

        List<LabResult> labs = store.getOrDefault(patientId.value(), List.of());
        for (int i = 0; i < labs.size(); i++) {
            LabResult lab = labs.get(i);
            if (lab.labId().equals(labId)) {
                if (lab.version() != expectedVersion) {
                    throw new IllegalStateException("Version mismatch: expected " + expectedVersion + ", found " + lab.version());
                }
                LabResult withNotification = lab.withNotificacionAgregada(notification);
                LabResult updated = withNotification.withEstadoVisibilidad(newStatus).withIncrementedVersion();
                labs.set(i, updated);
                return;
            }
        }
        throw new NoSuchElementException("Lab not found: " + labId);
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