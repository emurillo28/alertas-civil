package com.hospital.alertas.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value Object que representa el identificador único de un paciente.
 * Aplica validación alfanumérica estricta según el modelo Single-Table:
 * PK = PATIENT#<ID_ALFANUMERICO_ESTRICTO>
 *
 * <p>Inmutable por diseño (Java Record). No permite valores nulos, vacíos
 * ni caracteres especiales fuera del rango [A-Za-z0-9].
 */
public record PatientId(String value) {

    /**
     * Patrón alfanumérico estricto: solo letras (mayúsculas/minúsculas) y dígitos.
     * Mínimo 1 carácter, máximo 64 caracteres.
     */
    private static final Pattern ALPHANUMERIC_STRICT = Pattern.compile("^[A-Za-z0-9]{1,64}$");

    /**
     * Constructor compacto con validación defensiva.
     *
     * @param value identificador alfanumérico del paciente
     * @throws IllegalArgumentException si el valor es nulo, vacío o contiene caracteres no alfanuméricos
     */
    public PatientId {
        Objects.requireNonNull(value, "PatientId no puede ser nulo");
        if (value.isBlank()) {
            throw new IllegalArgumentException("PatientId no puede estar vacío");
        }
        if (!ALPHANUMERIC_STRICT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "PatientId debe ser alfanumérico estricto (solo letras y dígitos, 1-64 caracteres). Recibido: '%s'"
                            .formatted(value));
        }
    }

    /**
     * Genera la clave de partición (PK) para el modelo Single-Table de DynamoDB.
     *
     * @return cadena con formato PATIENT#<value>
     */
    public String toDynamoPartitionKey() {
        return "PATIENT#" + value;
    }

    @Override
    public String toString() {
        return value;
    }
}
