package com.hospital.alertas.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para {@link LabValidationException}.
 * Verifica los distintos constructores y la propagación de mensajes.
 */
class LabValidationExceptionTest {

    @Test
    @DisplayName("constructor con mensaje simple")
    void constructorConMensaje() {
        var ex = new LabValidationException("Datos de laboratorio corruptos");

        assertEquals("Datos de laboratorio corruptos", ex.getMessage());
        assertNull(ex.getFieldName());
        assertNull(ex.getCause());
    }

    @Test
    @DisplayName("constructor con campo y mensaje formatea correctamente")
    void constructorConCampoYMensaje() {
        var ex = new LabValidationException("Hemoglobina", "valor fuera de rango fisiológico: 999 g/dL");

        assertEquals("Campo 'Hemoglobina': valor fuera de rango fisiológico: 999 g/dL", ex.getMessage());
        assertEquals("Hemoglobina", ex.getFieldName());
    }

    @Test
    @DisplayName("constructor con causa raíz preserva la cadena de excepciones")
    void constructorConCausa() {
        var cause = new NumberFormatException("For input string: \"abc\"");
        var ex = new LabValidationException("Error parseando valor analítico", cause);

        assertEquals("Error parseando valor analítico", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertNull(ex.getFieldName());
    }

    @Test
    @DisplayName("es una RuntimeException (unchecked)")
    void esRuntimeException() {
        var ex = new LabValidationException("test");
        assertInstanceOf(RuntimeException.class, ex);
    }
}
