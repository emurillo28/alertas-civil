package com.hospital.alertas.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para el Value Object {@link PatientId}.
 * Verifica la validación alfanumérica estricta y la generación de PK de DynamoDB.
 */
class PatientIdTest {

    @Nested
    @DisplayName("Creación válida")
    class CreacionValida {

        @Test
        @DisplayName("acepta ID alfanumérico simple")
        void aceptaIdAlfanumericoSimple() {
            var id = new PatientId("ABC123");
            assertEquals("ABC123", id.value());
        }

        @Test
        @DisplayName("acepta ID solo con letras")
        void aceptaIdSoloLetras() {
            var id = new PatientId("paciente");
            assertEquals("paciente", id.value());
        }

        @Test
        @DisplayName("acepta ID solo con dígitos")
        void aceptaIdSoloDigitos() {
            var id = new PatientId("00112233");
            assertEquals("00112233", id.value());
        }

        @Test
        @DisplayName("acepta ID de un solo carácter")
        void aceptaIdUnCaracter() {
            var id = new PatientId("X");
            assertEquals("X", id.value());
        }

        @Test
        @DisplayName("acepta ID de 64 caracteres (límite máximo)")
        void aceptaIdLimiteMaximo() {
            String maxId = "A".repeat(64);
            var id = new PatientId(maxId);
            assertEquals(maxId, id.value());
        }
    }

    @Nested
    @DisplayName("Validación de rechazo")
    class ValidacionRechazo {

        @Test
        @DisplayName("rechaza nulo")
        void rechazaNulo() {
            assertThrows(NullPointerException.class, () -> new PatientId(null));
        }

        @Test
        @DisplayName("rechaza cadena vacía")
        void rechazaVacio() {
            assertThrows(IllegalArgumentException.class, () -> new PatientId(""));
        }

        @Test
        @DisplayName("rechaza cadena con solo espacios")
        void rechazaSoloEspacios() {
            assertThrows(IllegalArgumentException.class, () -> new PatientId("   "));
        }

        @Test
        @DisplayName("rechaza caracteres especiales")
        void rechazaCaracteresEspeciales() {
            assertThrows(IllegalArgumentException.class, () -> new PatientId("PAC-123"));
        }

        @Test
        @DisplayName("rechaza guion bajo")
        void rechazaGuionBajo() {
            assertThrows(IllegalArgumentException.class, () -> new PatientId("PAC_123"));
        }

        @Test
        @DisplayName("rechaza espacios internos")
        void rechazaEspaciosInternos() {
            assertThrows(IllegalArgumentException.class, () -> new PatientId("PAC 123"));
        }

        @Test
        @DisplayName("rechaza ID que excede 64 caracteres")
        void rechazaIdDemasiadoLargo() {
            String tooLong = "A".repeat(65);
            assertThrows(IllegalArgumentException.class, () -> new PatientId(tooLong));
        }
    }

    @Nested
    @DisplayName("Generación de PK DynamoDB")
    class DynamoPartitionKey {

        @Test
        @DisplayName("genera PK con formato PATIENT#<value>")
        void generaPkCorrecta() {
            var id = new PatientId("ABC123");
            assertEquals("PATIENT#ABC123", id.toDynamoPartitionKey());
        }
    }

    @Nested
    @DisplayName("Igualdad y toString")
    class IgualdadYToString {

        @Test
        @DisplayName("dos PatientId con mismo valor son iguales")
        void igualdadPorValor() {
            var id1 = new PatientId("ABC123");
            var id2 = new PatientId("ABC123");
            assertEquals(id1, id2);
            assertEquals(id1.hashCode(), id2.hashCode());
        }

        @Test
        @DisplayName("dos PatientId con distinto valor son diferentes")
        void desigualdadPorValor() {
            var id1 = new PatientId("ABC");
            var id2 = new PatientId("XYZ");
            assertNotEquals(id1, id2);
        }

        @Test
        @DisplayName("toString retorna solo el valor")
        void toStringRetornaValor() {
            var id = new PatientId("PAC001");
            assertEquals("PAC001", id.toString());
        }
    }
}
