package com.hospital.alertas.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para la entidad de dominio {@link LabResult}.
 * Verifica la creación, validaciones, inmutabilidad y métodos with*.
 */
class LabResultTest {

    private static final PatientId PATIENT_ID = new PatientId("PAC001");
    private static final String TIPO_EXAMEN = "Biometria Hematica";
    private static final Map<String, String> VALORES = Map.of(
            "Hemoglobina", "14.5 g/dL",
            "Leucocitos", "7500 /uL"
    );
    private static final Instant TIMESTAMP = Instant.parse("2026-06-01T10:30:00Z");
    private static final String S3_PATH = "s3://hc-laboratorios/2026/06/01/PAC001/BH-001.pdf";

    private LabResult crearLabValido() {
        return new LabResult(
                "LAB001", PATIENT_ID, TIPO_EXAMEN, VALORES,
                TIMESTAMP, false, S3_PATH, null, 0L
        );
    }

    @Nested
    @DisplayName("Creación válida")
    class CreacionValida {

        @Test
        @DisplayName("crea LabResult con todos los campos")
        void creaLabCompleto() {
            var lab = crearLabValido();

            assertEquals("LAB001", lab.labId());
            assertEquals(PATIENT_ID, lab.patientId());
            assertEquals(TIPO_EXAMEN, lab.tipoExamen());
            assertEquals(VALORES, lab.valoresAnaliticos());
            assertEquals(TIMESTAMP, lab.timestamp());
            assertFalse(lab.alertaCritica());
            assertEquals(S3_PATH, lab.s3PathCompleto());
            assertNull(lab.resumenClinico());
            assertEquals(0L, lab.version());
        }

        @Test
        @DisplayName("crea LabResult con alerta crítica activa")
        void creaLabConAlertaCritica() {
            var lab = new LabResult(
                    "LAB002", PATIENT_ID, "Glucosa", Map.of("Glucosa", "450 mg/dL"),
                    TIMESTAMP, true, S3_PATH, null, 0L
            );

            assertTrue(lab.alertaCritica());
        }

        @Test
        @DisplayName("crea LabResult con resumen clínico")
        void creaLabConResumen() {
            var lab = new LabResult(
                    "LAB003", PATIENT_ID, TIPO_EXAMEN, VALORES,
                    TIMESTAMP, false, S3_PATH, "Valores dentro de rangos normales", 1L
            );

            assertEquals("Valores dentro de rangos normales", lab.resumenClinico());
            assertEquals(1L, lab.version());
        }
    }

    @Nested
    @DisplayName("Factory method create()")
    class FactoryMethod {

        @Test
        @DisplayName("genera UUID automático y versión 0")
        void createGeneraUuidYVersionCero() {
            var lab = LabResult.create(
                    PATIENT_ID, TIPO_EXAMEN, VALORES,
                    TIMESTAMP, false, S3_PATH
            );

            assertNotNull(lab.labId());
            assertFalse(lab.labId().isBlank());
            assertNull(lab.resumenClinico());
            assertEquals(0L, lab.version());
        }

        @Test
        @DisplayName("cada invocación genera un UUID distinto")
        void createGeneraUuidsDistintos() {
            var lab1 = LabResult.create(PATIENT_ID, TIPO_EXAMEN, VALORES, TIMESTAMP, false, S3_PATH);
            var lab2 = LabResult.create(PATIENT_ID, TIPO_EXAMEN, VALORES, TIMESTAMP, false, S3_PATH);

            assertNotEquals(lab1.labId(), lab2.labId());
        }
    }

    @Nested
    @DisplayName("Validación de rechazo")
    class ValidacionRechazo {

        @Test
        @DisplayName("rechaza labId nulo")
        void rechazaLabIdNulo() {
            assertThrows(NullPointerException.class, () -> new LabResult(
                    null, PATIENT_ID, TIPO_EXAMEN, VALORES, TIMESTAMP, false, S3_PATH, null, 0L
            ));
        }

        @Test
        @DisplayName("rechaza labId vacío")
        void rechazaLabIdVacio() {
            assertThrows(IllegalArgumentException.class, () -> new LabResult(
                    "", PATIENT_ID, TIPO_EXAMEN, VALORES, TIMESTAMP, false, S3_PATH, null, 0L
            ));
        }

        @Test
        @DisplayName("rechaza patientId nulo")
        void rechazaPatientIdNulo() {
            assertThrows(NullPointerException.class, () -> new LabResult(
                    "LAB001", null, TIPO_EXAMEN, VALORES, TIMESTAMP, false, S3_PATH, null, 0L
            ));
        }

        @Test
        @DisplayName("rechaza tipoExamen nulo")
        void rechazaTipoExamenNulo() {
            assertThrows(NullPointerException.class, () -> new LabResult(
                    "LAB001", PATIENT_ID, null, VALORES, TIMESTAMP, false, S3_PATH, null, 0L
            ));
        }

        @Test
        @DisplayName("rechaza tipoExamen vacío")
        void rechazaTipoExamenVacio() {
            assertThrows(IllegalArgumentException.class, () -> new LabResult(
                    "LAB001", PATIENT_ID, "", VALORES, TIMESTAMP, false, S3_PATH, null, 0L
            ));
        }

        @Test
        @DisplayName("rechaza valoresAnaliticos nulo")
        void rechazaValoresNulo() {
            assertThrows(NullPointerException.class, () -> new LabResult(
                    "LAB001", PATIENT_ID, TIPO_EXAMEN, null, TIMESTAMP, false, S3_PATH, null, 0L
            ));
        }

        @Test
        @DisplayName("rechaza valoresAnaliticos vacío")
        void rechazaValoresVacio() {
            assertThrows(IllegalArgumentException.class, () -> new LabResult(
                    "LAB001", PATIENT_ID, TIPO_EXAMEN, Map.of(), TIMESTAMP, false, S3_PATH, null, 0L
            ));
        }

        @Test
        @DisplayName("rechaza timestamp nulo")
        void rechazaTimestampNulo() {
            assertThrows(NullPointerException.class, () -> new LabResult(
                    "LAB001", PATIENT_ID, TIPO_EXAMEN, VALORES, null, false, S3_PATH, null, 0L
            ));
        }

        @Test
        @DisplayName("rechaza s3PathCompleto nulo")
        void rechazaS3PathNulo() {
            assertThrows(NullPointerException.class, () -> new LabResult(
                    "LAB001", PATIENT_ID, TIPO_EXAMEN, VALORES, TIMESTAMP, false, null, null, 0L
            ));
        }

        @Test
        @DisplayName("rechaza s3PathCompleto vacío")
        void rechazaS3PathVacio() {
            assertThrows(IllegalArgumentException.class, () -> new LabResult(
                    "LAB001", PATIENT_ID, TIPO_EXAMEN, VALORES, TIMESTAMP, false, "", null, 0L
            ));
        }

        @Test
        @DisplayName("rechaza versión negativa")
        void rechazaVersionNegativa() {
            assertThrows(IllegalArgumentException.class, () -> new LabResult(
                    "LAB001", PATIENT_ID, TIPO_EXAMEN, VALORES, TIMESTAMP, false, S3_PATH, null, -1L
            ));
        }
    }

    @Nested
    @DisplayName("Inmutabilidad")
    class Inmutabilidad {

        @Test
        @DisplayName("el mapa de valores analíticos es inmutable")
        void mapaValoresInmutable() {
            var mutableMap = new java.util.HashMap<String, String>();
            mutableMap.put("Glucosa", "120 mg/dL");

            var lab = new LabResult(
                    "LAB001", PATIENT_ID, TIPO_EXAMEN, mutableMap,
                    TIMESTAMP, false, S3_PATH, null, 0L
            );

            // Modificar el mapa original no afecta al record
            mutableMap.put("Extra", "valor");
            assertFalse(lab.valoresAnaliticos().containsKey("Extra"));

            // El mapa del record no se puede modificar
            assertThrows(UnsupportedOperationException.class,
                    () -> lab.valoresAnaliticos().put("Hack", "value"));
        }
    }

    @Nested
    @DisplayName("Métodos with*")
    class MetodosWith {

        @Test
        @DisplayName("withIncrementedVersion incrementa la versión en 1")
        void withIncrementedVersion() {
            var lab = crearLabValido();
            var updated = lab.withIncrementedVersion();

            assertEquals(1L, updated.version());
            // Todos los demás campos son iguales
            assertEquals(lab.labId(), updated.labId());
            assertEquals(lab.patientId(), updated.patientId());
            assertEquals(lab.tipoExamen(), updated.tipoExamen());
        }

        @Test
        @DisplayName("withResumenClinico establece el resumen")
        void withResumenClinico() {
            var lab = crearLabValido();
            assertNull(lab.resumenClinico());

            var conResumen = lab.withResumenClinico("Paciente estable, valores normales");

            assertEquals("Paciente estable, valores normales", conResumen.resumenClinico());
            assertEquals(lab.version(), conResumen.version());
        }

        @Test
        @DisplayName("withResumenClinico rechaza nulo")
        void withResumenClinicoRechazaNulo() {
            var lab = crearLabValido();
            assertThrows(NullPointerException.class, () -> lab.withResumenClinico(null));
        }
    }

    @Nested
    @DisplayName("Clave DynamoDB")
    class ClaveDynamo {

        @Test
        @DisplayName("genera SK con formato LAB#<labId>")
        void generaSortKey() {
            var lab = crearLabValido();
            assertEquals("LAB#LAB001", lab.toDynamoSortKey());
        }
    }
}
