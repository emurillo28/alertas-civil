package com.hospital.alertas.application.service;

import com.hospital.alertas.domain.exception.LabValidationException;
import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.LabResultTestBuilder;
import com.hospital.alertas.domain.model.PatientId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de comportamiento clínico para {@link IngestLabService}.
 *
 * <p>Usa {@link InMemoryLabPersistence} como fake (no mocks).
 * Cada test verifica un escenario de negocio observable, no una línea de código.
 *
 * <p>Convención de nombres: cuando<Accion>_con<Condicion>_entonces<Resultado>
 */
class IngestLabServiceTest {

    private InMemoryLabPersistence persistence;
    private IngestLabService service;

    @BeforeEach
    void setUp() {
        persistence = new InMemoryLabPersistence();
        service = new IngestLabService(persistence);
    }

    // ─── Flujo exitoso (Happy Path) ─────────────────────────────────────

    @Nested
    @DisplayName("Ingesta exitosa de laboratorios")
    class IngestaExitosa {

        @Test
        @DisplayName("cuando ingiero un lab con valores normales, se persiste sin alerta crítica")
        void cuandoIngieroLab_conValoresNormales_entoncesSePersisteYNoEsAlertaCritica() {
            LabResult lab = LabResultTestBuilder.unLabNormal();

            service.execute(lab);

            assertEquals(1, persistence.count());
            Optional<LabResult> saved = persistence.getLastSaved();
            assertTrue(saved.isPresent());
            assertFalse(saved.get().alertaCritica(),
                    "Un lab con Hemoglobina 14.5 g/dL NO debería ser alerta crítica");
        }

        @Test
        @DisplayName("cuando ingiero un lab, se puede recuperar por paciente")
        void cuandoIngieroLab_entoncesPuedoRecuperarloPorPaciente() {
            LabResult lab = new LabResultTestBuilder()
                    .withPatientId("PAC999")
                    .build();

            service.execute(lab);

            Optional<LabResult> found = persistence.obtenerUltimoLaboratorio(new PatientId("PAC999"));
            assertTrue(found.isPresent());
            assertEquals("PAC999", found.get().patientId().value());
        }

        @Test
        @DisplayName("cuando ingiero múltiples labs del mismo paciente, todos se persisten")
        void cuandoIngieroMultiplesLabs_delMismoPaciente_entoncesTodosSeGuardan() {
            LabResult lab1 = new LabResultTestBuilder()
                    .withLabId("LAB001")
                    .withPatientId("PAC100")
                    .build();
            LabResult lab2 = new LabResultTestBuilder()
                    .withLabId("LAB002")
                    .withPatientId("PAC100")
                    .withTipoExamen("Quimica Sanguinea")
                    .withSoloAnalito("Glucosa", "95 mg/dL")
                    .build();

            service.execute(lab1);
            service.execute(lab2);

            assertEquals(2, persistence.findAllByPatient(new PatientId("PAC100")).size());
        }
    }

    // ─── Evaluación de alertas críticas ──────────────────────────────────

    @Nested
    @DisplayName("Evaluación automática de alertas críticas clínicas")
    class AlertasCriticas {

        @Test
        @DisplayName("cuando ingiero lab con Hemoglobina < 7, entonces alerta crítica es true")
        void cuandoIngieroLab_conHemoglobinaBaja_entoncesAlertaEsTrue() {
            LabResult lab = LabResultTestBuilder.unLabConHemoglobinaCritica();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.alertaCritica(),
                    "Hemoglobina 5.2 g/dL está por debajo del umbral de 7.0 → alerta crítica");
        }

        @Test
        @DisplayName("cuando ingiero lab con Potasio > 6.0, entonces alerta crítica es true")
        void cuandoIngieroLab_conPotasioAlto_entoncesAlertaEsTrue() {
            LabResult lab = LabResultTestBuilder.unLabConPotasioCritico();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.alertaCritica(),
                    "Potasio 7.8 mEq/L supera el umbral de 6.0 → alerta crítica");
        }

        @Test
        @DisplayName("cuando ingiero lab con Glucosa > 400, entonces alerta crítica es true")
        void cuandoIngieroLab_conGlucosaAlta_entoncesAlertaEsTrue() {
            LabResult lab = LabResultTestBuilder.unLabConGlucosaCritica();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.alertaCritica(),
                    "Glucosa 520 mg/dL supera el umbral de 400 → alerta crítica");
        }

        @Test
        @DisplayName("cuando ingiero lab con Glucosa < 50 (hipoglucemia), entonces alerta crítica es true")
        void cuandoIngieroLab_conGlucosaMuyBaja_entoncesAlertaEsTrue() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("Glucosa", "38 mg/dL")
                    .withTipoExamen("Quimica Sanguinea")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.alertaCritica(),
                    "Glucosa 38 mg/dL está por debajo de 50 → hipoglucemia crítica");
        }

        @Test
        @DisplayName("cuando ingiero lab con Sodio > 160 (hipernatremia), entonces alerta crítica es true")
        void cuandoIngieroLab_conSodioAlto_entoncesAlertaEsTrue() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("Sodio", "172 mEq/L")
                    .withTipoExamen("Electrolitos Sericos")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.alertaCritica(),
                    "Sodio 172 mEq/L supera el umbral de 160 → hipernatremia crítica");
        }

        @Test
        @DisplayName("cuando ingiero lab con Creatinina > 10 (falla renal), entonces alerta crítica es true")
        void cuandoIngieroLab_conCreatininaAlta_entoncesAlertaEsTrue() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("Creatinina", "12.3 mg/dL")
                    .withTipoExamen("Quimica Sanguinea")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.alertaCritica(),
                    "Creatinina 12.3 mg/dL supera el umbral de 10.0 → falla renal crítica");
        }

        @Test
        @DisplayName("cuando ingiero lab con valores justo en el límite, NO activa alerta")
        void cuandoIngieroLab_conValoresEnElLimite_entoncesNoEsAlertaCritica() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("Hemoglobina", "7.0 g/dL")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertFalse(saved.alertaCritica(),
                    "Hemoglobina exactamente en 7.0 está DENTRO del rango [7.0, 20.0] → no es alerta");
        }

        @Test
        @DisplayName("cuando lab ya viene con alerta crítica true, se respeta y no se reevalúa")
        void cuandoLabYaTieneAlertaCritica_entoncesSeRespeta() {
            LabResult lab = new LabResultTestBuilder()
                    .withAlertaCritica(true)
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.alertaCritica(),
                    "La alerta crítica preexistente debe respetarse");
        }

        @Test
        @DisplayName("cuando ingiero lab con analito desconocido, no activa alerta")
        void cuandoIngieroLab_conAnalitoSinUmbral_entoncesNoActivaAlerta() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("VitaminaB12", "350 pg/mL")
                    .withTipoExamen("Vitaminas")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertFalse(saved.alertaCritica(),
                    "Un analito sin umbral definido no debe disparar alerta");
        }

        @Test
        @DisplayName("cuando un lab tiene múltiples analitos y solo uno es crítico, activa alerta")
        void cuandoIngieroLab_conUnAnalitoCriticoEntreVarios_entoncesAlertaEsTrue() {
            LabResult lab = new LabResultTestBuilder()
                    .withValoresAnaliticos(Map.of(
                            "Hemoglobina", "14.0 g/dL",  // Normal
                            "Leucocitos", "7500 /uL",    // Sin umbral
                            "Potasio", "6.5 mEq/L"       // CRÍTICO (> 6.0)
                    ))
                    .withTipoExamen("Panel Completo")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.alertaCritica(),
                    "Potasio 6.5 supera el umbral de 6.0 aunque los demás son normales");
        }

        @Test
        @DisplayName("la comparación de nombres de analitos es case-insensitive")
        void cuandoIngieroLab_conNombreAnalitoEnMayusculas_entoncesEvalua() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("HEMOGLOBINA", "5.0 g/dL")
                    .build();

            service.execute(lab);

            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.alertaCritica(),
                    "La evaluación debe ser case-insensitive: 'HEMOGLOBINA' == 'hemoglobina'");
        }
    }

    // ─── Validaciones de rechazo ─────────────────────────────────────────

    @Nested
    @DisplayName("Rechazo de datos clínicos corruptos")
    class DatosCorruptos {

        @Test
        @DisplayName("cuando ingiero lab con valor analítico no numérico para analito evaluable, lanza excepción")
        void cuandoIngieroLab_conValorNoNumerico_entoncesLanzaExcepcion() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("Hemoglobina", "INVALIDO")
                    .build();

            LabValidationException ex = assertThrows(LabValidationException.class,
                    () -> service.execute(lab));
            assertTrue(ex.getMessage().contains("Hemoglobina"),
                    "El mensaje de error debe indicar qué analito falló");
        }

        @Test
        @DisplayName("cuando ingiero lab con valor analítico vacío para analito evaluable, lanza excepción")
        void cuandoIngieroLab_conValorVacio_entoncesLanzaExcepcion() {
            LabResult lab = new LabResultTestBuilder()
                    .withSoloAnalito("Potasio", "")
                    .build();

            assertThrows(LabValidationException.class, () -> service.execute(lab));
        }

        @Test
        @DisplayName("cuando el servicio se construye con puerto nulo, lanza excepción")
        void cuandoConstruyoServicio_conPuertoNulo_entoncesLanzaExcepcion() {
            assertThrows(NullPointerException.class, () -> new IngestLabService(null));
        }
    }

    // ─── Verificación de inmutabilidad del flujo ─────────────────────────

    @Nested
    @DisplayName("Inmutabilidad en el flujo de ingesta")
    class Inmutabilidad {

        @Test
        @DisplayName("el LabResult original no se modifica cuando se activa alerta crítica")
        void cuandoSeActivaAlerta_entoncesLabOriginalNoSeMuta() {
            LabResult labOriginal = new LabResultTestBuilder()
                    .withSoloAnalito("Hemoglobina", "5.0 g/dL")
                    .build();

            assertFalse(labOriginal.alertaCritica(), "El lab original no tiene alerta");

            service.execute(labOriginal);

            // El original sigue intacto (inmutabilidad del Record)
            assertFalse(labOriginal.alertaCritica(),
                    "El Record original no debe mutarse — se crea una nueva instancia");

            // Pero lo persistido SÍ tiene alerta
            LabResult saved = persistence.getLastSaved().orElseThrow();
            assertTrue(saved.alertaCritica());
        }
    }
}
