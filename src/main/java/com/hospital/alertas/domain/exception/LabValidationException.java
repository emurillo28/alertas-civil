package com.hospital.alertas.domain.exception;

/**
 * Excepción de dominio lanzada cuando los datos clínicos de un laboratorio
 * son corruptos, incompletos o violan las invariantes del negocio.
 *
 * <p>Extiende {@link RuntimeException} (unchecked) para evitar contaminar
 * las firmas de los métodos del dominio con checked exceptions, manteniendo
 * el código limpio e imperativo según las reglas de arquitectura.
 *
 * <p>Ejemplos de uso:
 * <ul>
 *   <li>Valores analíticos fuera de rango fisiológico plausible</li>
 *   <li>Tipo de examen no reconocido por el catálogo clínico</li>
 *   <li>Datos de laboratorio con formato corrupto desde el equipo emisor</li>
 *   <li>Violación de reglas de negocio en la evaluación de alertas críticas</li>
 * </ul>
 */
public class LabValidationException extends RuntimeException {

    private final String fieldName;

    /**
     * Crea una excepción de validación con un mensaje descriptivo.
     *
     * @param message descripción legible del error de validación
     */
    public LabValidationException(String message) {
        super(message);
        this.fieldName = null;
    }

    /**
     * Crea una excepción de validación asociada a un campo clínico específico.
     *
     * @param fieldName nombre del campo que falló la validación
     * @param message   descripción legible del error
     */
    public LabValidationException(String fieldName, String message) {
        super("Campo '%s': %s".formatted(fieldName, message));
        this.fieldName = fieldName;
    }

    /**
     * Crea una excepción de validación con causa raíz (para encadenar excepciones
     * de parseo, formato numérico, etc.).
     *
     * @param message descripción legible del error
     * @param cause   excepción original que provocó el fallo
     */
    public LabValidationException(String message, Throwable cause) {
        super(message, cause);
        this.fieldName = null;
    }

    /**
     * @return el nombre del campo clínico que provocó el error, o {@code null}
     *         si la excepción no está asociada a un campo específico.
     */
    public String getFieldName() {
        return fieldName;
    }
}
