package com.hospital.alertas.application.service;

import com.hospital.alertas.application.ports.inbound.IngestLabUseCase;
import com.hospital.alertas.application.ports.outbound.LabPersistencePort;
import com.hospital.alertas.domain.exception.LabValidationException;
import com.hospital.alertas.domain.model.LabResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orquestador del caso de uso de ingesta de laboratorios clínicos.
 *
 * <p>Implementa la lógica de negocio secuencial y síncrona:
 * <ol>
 *   <li>Validación defensiva de campos mandatorios.</li>
 *   <li>Evaluación automática de umbrales de alerta crítica.</li>
 *   <li>Persistencia del resultado procesado.</li>
 * </ol>
 *
 * <p>La concurrencia se gestiona externamente por Project Loom (hilos virtuales)
 * en la capa de infraestructura. Este servicio es 100% imperativo y bloqueante.
 *
 * <p><strong>Restricción arquitectónica:</strong> prohibido importar
 * {@code software.amazon.awssdk.*} o usar APIs reactivas (Mutiny, Uni, Multi).
 */
@ApplicationScoped
public class IngestLabService implements IngestLabUseCase {

    /**
     * Patrón para extraer la parte numérica (incluyendo decimales y signo negativo)
     * al inicio de un valor analítico. Ejemplo: "14.5 g/dL" → 14.5
     */
    private static final Pattern NUMERIC_PREFIX = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)");

    /**
     * Umbrales de alerta crítica clínica.
     * Cada entrada define: nombre del analito (lowercase), límite inferior, límite superior.
     * Si el valor está FUERA del rango [min, max], se activa la alerta crítica.
     *
     * <p>Referencia clínica: valores de pánico estándar de laboratorio hospitalario.
     */
    private static final Map<String, CriticalThreshold> CRITICAL_THRESHOLDS = Map.of(
            "hemoglobina",  new CriticalThreshold(7.0,   20.0),
            "potasio",      new CriticalThreshold(2.5,    6.0),
            "glucosa",      new CriticalThreshold(50.0, 400.0),
            "sodio",        new CriticalThreshold(120.0, 160.0),
            "creatinina",   new CriticalThreshold(0.0,   10.0),
            "plaquetas",    new CriticalThreshold(50.0, 1000.0)
    );

    private final LabPersistencePort persistencePort;

    /**
     * Constructor para inyección CDI estándar.
     *
     * @param persistencePort puerto de persistencia (inyectado por Quarkus/CDI)
     */
    public IngestLabService(LabPersistencePort persistencePort) {
        this.persistencePort = Objects.requireNonNull(persistencePort,
                "LabPersistencePort no puede ser nulo");
    }

    @Override
    public void execute(LabResult command) {
        validateMandatoryFields(command);

        LabResult labToSave = evaluateCriticalAlerts(command);

        persistencePort.saveLab(labToSave);
    }

    // ─── Validación de campos mandatorios ───────────────────────────────

    /**
     * Valida que los campos clínicos mandatorios del LabResult no sean nulos ni vacíos.
     * Las validaciones del Record compact constructor ya cubren los nulls en construcción,
     * pero esta capa adicional protege contra datos corruptos provenientes de adaptadores
     * de entrada que podrían deserializar JSON malformado.
     *
     * @throws LabValidationException si algún campo mandatorio es inválido
     */
    private void validateMandatoryFields(LabResult lab) {
        if (lab == null) {
            throw new LabValidationException("El resultado de laboratorio no puede ser nulo");
        }
        if (lab.patientId() == null) {
            throw new LabValidationException("patientId", "es obligatorio para la ingesta");
        }
        if (lab.tipoExamen() == null || lab.tipoExamen().isBlank()) {
            throw new LabValidationException("tipoExamen", "es obligatorio y no puede estar vacío");
        }
        if (lab.valoresAnaliticos() == null || lab.valoresAnaliticos().isEmpty()) {
            throw new LabValidationException("valoresAnaliticos",
                    "debe contener al menos un resultado analítico");
        }
        if (lab.timestamp() == null) {
            throw new LabValidationException("timestamp", "es obligatorio para trazabilidad clínica");
        }
        if (lab.s3PathCompleto() == null || lab.s3PathCompleto().isBlank()) {
            throw new LabValidationException("s3PathCompleto",
                    "es obligatorio: el PDF original debe estar almacenado en S3");
        }
    }

    // ─── Evaluación de umbrales de alerta crítica ───────────────────────

    /**
     * Evalúa los valores analíticos contra los umbrales de peligro clínico.
     * Si CUALQUIER analito excede su umbral, retorna una nueva instancia
     * con {@code alertaCritica = true}. Si ninguno excede, retorna el lab original.
     *
     * <p>La comparación de nombres de analitos es case-insensitive para tolerar
     * variaciones en la salida de diferentes equipos de laboratorio.
     *
     * @param lab resultado de laboratorio a evaluar
     * @return lab original o nueva instancia con alerta crítica activada
     */
    LabResult evaluateCriticalAlerts(LabResult lab) {
        // Si ya viene marcado como alerta crítica, respetar la decisión upstream
        if (lab.alertaCritica()) {
            return lab;
        }

        for (var entry : lab.valoresAnaliticos().entrySet()) {
            String analyteKey = entry.getKey().toLowerCase().trim();
            CriticalThreshold threshold = CRITICAL_THRESHOLDS.get(analyteKey);

            if (threshold == null) {
                continue; // Analito sin umbral definido, no es evaluable
            }

            double numericValue = extractNumericValue(entry.getKey(), entry.getValue());

            if (numericValue < threshold.min() || numericValue > threshold.max()) {
                return lab.withAlertaCritica(true);
            }
        }

        return lab;
    }

    /**
     * Extrae el valor numérico del inicio de una cadena de valor analítico.
     * Ejemplo: "14.5 g/dL" → 14.5, "120 mg/dL" → 120.0
     *
     * @param analyteName nombre del analito (para mensajes de error)
     * @param rawValue    valor crudo del analito con posible unidad
     * @return valor numérico extraído
     * @throws LabValidationException si el valor no contiene un número válido
     */
    private double extractNumericValue(String analyteName, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new LabValidationException(analyteName,
                    "el valor analítico no puede estar vacío");
        }

        Matcher matcher = NUMERIC_PREFIX.matcher(rawValue.trim());
        if (!matcher.find()) {
            throw new LabValidationException(analyteName,
                    "no se pudo extraer un valor numérico de: '%s'".formatted(rawValue));
        }

        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new LabValidationException(
                    "Error parseando valor numérico del analito '%s': %s"
                            .formatted(analyteName, rawValue), e);
        }
    }

    // ─── Record interno para umbrales ───────────────────────────────────

    /**
     * Umbral de alerta crítica para un analito clínico.
     * Valores FUERA del rango [min, max] disparan alerta crítica.
     */
    record CriticalThreshold(double min, double max) {
        CriticalThreshold {
            if (min > max) {
                throw new IllegalArgumentException(
                        "min (%s) no puede ser mayor que max (%s)".formatted(min, max));
            }
        }
    }
}
