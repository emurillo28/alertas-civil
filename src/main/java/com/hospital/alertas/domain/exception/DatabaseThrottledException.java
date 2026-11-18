package com.hospital.alertas.domain.exception;

/**
 * Excepción de dominio lanzada cuando la base de datos está saturada
 * (throttling) y no puede procesar la solicitud.
 *
 * <p>Mapea {@code ProvisionedThroughputExceededException} de AWS SDK
 * a una excepción de dominio que la capa de aplicación puede manejar
 * sin conocer detalles de infraestructura.
 *
 * <p>El caso de uso típico es reintentar con back-off exponencial
 * o escalar a una cola de reintentos (DLQ).
 */
public class DatabaseThrottledException extends RuntimeException {

    /**
     * Crea una excepción con mensaje descriptivo.
     *
     * @param message descripción legible (ej. "Capacidad de escritura DynamoDB excedida")
     */
    public DatabaseThrottledException(String message) {
        super(message);
    }

    /**
     * Crea una excepción encadenando la causa original de AWS SDK.
     *
     * @param message descripción legible
     * @param cause   excepción original ({@code ProvisionedThroughputExceededException})
     */
    public DatabaseThrottledException(String message, Throwable cause) {
        super(message, cause);
    }
}