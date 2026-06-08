package com.hospital.alertas.domain.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builder de test para {@link LabResult} con datos clínicos realistas por defecto.
 *
 * <p>Permite construir instancias válidas sobrescribiendo selectivamente
 * solo los campos relevantes para cada escenario de prueba, evitando
 * la repetición de datos boilerplate.
 *
 * <p>Valores por defecto: paciente PAC001, Biometría Hemática con
 * Hemoglobina 14.5 g/dL y Leucocitos 7500 /uL (rangos normales).
 */
public final class LabResultTestBuilder {

    private String labId = UUID.randomUUID().toString();
    private PatientId patientId = new PatientId("PAC001");
    private String tipoExamen = "Biometria Hematica";
    private Map<String, String> valoresAnaliticos = new HashMap<>(Map.of(
            "Hemoglobina", "14.5 g/dL",
            "Leucocitos", "7500 /uL"
    ));
    private Instant timestamp = Instant.parse("2026-06-01T10:30:00Z");
    private boolean alertaCritica = false;
    private String s3PathCompleto = "s3://hc-laboratorios/2026/06/01/PAC001/BH-001.pdf";
    private String resumenClinico = null;
    private long version = 0L;
    private EstadoVisibilidad estadoVisibilidad = EstadoVisibilidad.PENDIENTE;
    private List<NotificacionEnviada> notificaciones = List.of();

    public LabResultTestBuilder withLabId(String labId) {
        this.labId = labId;
        return this;
    }

    public LabResultTestBuilder withPatientId(String patientIdValue) {
        this.patientId = new PatientId(patientIdValue);
        return this;
    }

    public LabResultTestBuilder withPatientId(PatientId patientId) {
        this.patientId = patientId;
        return this;
    }

    public LabResultTestBuilder withTipoExamen(String tipoExamen) {
        this.tipoExamen = tipoExamen;
        return this;
    }

    public LabResultTestBuilder withValoresAnaliticos(Map<String, String> valores) {
        this.valoresAnaliticos = new HashMap<>(valores);
        return this;
    }

    /**
     * Agrega o sobrescribe un valor analítico específico, manteniendo los demás.
     */
    public LabResultTestBuilder withAnalito(String nombre, String valor) {
        this.valoresAnaliticos.put(nombre, valor);
        return this;
    }

    /**
     * Reemplaza TODOS los valores analíticos con un solo analito.
     * Útil para tests que evalúan un umbral específico en aislamiento.
     */
    public LabResultTestBuilder withSoloAnalito(String nombre, String valor) {
        this.valoresAnaliticos = new HashMap<>(Map.of(nombre, valor));
        return this;
    }

    public LabResultTestBuilder withTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public LabResultTestBuilder withAlertaCritica(boolean alertaCritica) {
        this.alertaCritica = alertaCritica;
        return this;
    }

    public LabResultTestBuilder withS3Path(String s3PathCompleto) {
        this.s3PathCompleto = s3PathCompleto;
        return this;
    }

    public LabResultTestBuilder withResumenClinico(String resumenClinico) {
        this.resumenClinico = resumenClinico;
        return this;
    }

    public LabResultTestBuilder withVersion(long version) {
        this.version = version;
        return this;
    }

    public LabResultTestBuilder withEstadoVisibilidad(EstadoVisibilidad estadoVisibilidad) {
        this.estadoVisibilidad = estadoVisibilidad;
        return this;
    }

    public LabResultTestBuilder withNotificaciones(List<NotificacionEnviada> notificaciones) {
        this.notificaciones = notificaciones;
        return this;
    }

    public LabResult build() {
        return new LabResult(
                labId, patientId, tipoExamen,
                Map.copyOf(valoresAnaliticos),
                timestamp, alertaCritica, s3PathCompleto,
                resumenClinico, version,
                estadoVisibilidad, notificaciones
        );
    }

    /**
     * Shortcut: crea un LabResult con valores clínicos normales (sin alerta).
     */
    public static LabResult unLabNormal() {
        return new LabResultTestBuilder().build();
    }

    /**
     * Shortcut: crea un LabResult con Hemoglobina crítica (< 7 g/dL).
     */
    public static LabResult unLabConHemoglobinaCritica() {
        return new LabResultTestBuilder()
                .withSoloAnalito("Hemoglobina", "5.2 g/dL")
                .withTipoExamen("Biometria Hematica")
                .build();
    }

    /**
     * Shortcut: crea un LabResult con Potasio crítico (> 6.0 mEq/L).
     */
    public static LabResult unLabConPotasioCritico() {
        return new LabResultTestBuilder()
                .withSoloAnalito("Potasio", "7.8 mEq/L")
                .withTipoExamen("Electrolitos Sericos")
                .build();
    }

    /**
     * Shortcut: crea un LabResult con Glucosa crítica (> 400 mg/dL).
     */
    public static LabResult unLabConGlucosaCritica() {
        return new LabResultTestBuilder()
                .withSoloAnalito("Glucosa", "520 mg/dL")
                .withTipoExamen("Quimica Sanguinea")
                .build();
    }
}
