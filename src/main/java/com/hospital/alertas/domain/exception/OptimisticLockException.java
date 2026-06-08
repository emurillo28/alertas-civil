package com.hospital.alertas.domain.exception;

/**
 * Excepción de dominio lanzada cuando falla el Control de Concurrencia Optimista (OCC).
 *
 * <p>Mapea {@code ConditionalCheckFailedException} de AWS SDK
 * a una excepción de dominio semánticamente significativa.
 *
 * <p>Indica que la entidad fue modificada por otro proceso concurrente
 * entre la lectura y la escritura. El llamador debe re-leer la entidad
 * y reintentar la operación con la versión actualizada.
 */
public class OptimisticLockException extends RuntimeException {

    private final String entityId;
    private final long expectedVersion;

    /**
     * Crea una excepción con contexto de la entidad afectada.
     *
     * @param entityId       identificador de la entidad (ej. labId)
     * @param expectedVersion versión que se esperaba encontrar en base de datos
     * @param message        descripción legible
     */
    public OptimisticLockException(String entityId, long expectedVersion, String message) {
        super(message);
        this.entityId = entityId;
        this.expectedVersion = expectedVersion;
    }

    /**
     * Crea una excepción encadenando la causa original de AWS SDK.
     *
     * @param entityId       identificador de la entidad
     * @param expectedVersion versión esperada
     * @param message        descripción legible
     * @param cause          excepción original ({@code ConditionalCheckFailedException})
     */
    public OptimisticLockException(String entityId, long expectedVersion, String message, Throwable cause) {
        super(message, cause);
        this.entityId = entityId;
        this.expectedVersion = expectedVersion;
    }

    /**
     * @return identificador de la entidad que sufrió el conflicto
     */
    public String getEntityId() {
        return entityId;
    }

    /**
     * @return versión que el proceso esperaba encontrar
     */
    public long getExpectedVersion() {
        return expectedVersion;
    }
}