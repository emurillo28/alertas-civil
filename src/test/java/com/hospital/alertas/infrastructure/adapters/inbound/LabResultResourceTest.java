package com.hospital.alertas.infrastructure.adapters.inbound;

import com.hospital.alertas.application.ports.inbound.IngestLabUseCase;
import com.hospital.alertas.application.ports.inbound.NotifyLabReadyUseCase;
import com.hospital.alertas.application.service.InMemoryLabPersistence;
import com.hospital.alertas.application.service.NotifyLabReadyService;
import com.hospital.alertas.domain.model.LabResult;
import com.hospital.alertas.domain.model.PatientId;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LabResultResource - Tests del adaptador REST")
class LabResultResourceTest {

    private InMemoryLabPersistence persistencePort;
    private NotifyLabReadyUseCase notifyUseCase;
    private IngestLabUseCase ingestUseCase;
    private LabResultResource resource;

    @BeforeEach
    void setUp() {
        persistencePort = new InMemoryLabPersistence();
        notifyUseCase = new NotifyLabReadyService(persistencePort, (patientId, labId, notification) -> {
            // No-op for unit test
        });
        ingestUseCase = command -> {}; // Stub para tests de lectura/notificación
        resource = new LabResultResource(ingestUseCase, notifyUseCase, persistencePort);
    }

    @Nested
    @DisplayName("Directorio de Pacientes")
    class DirectorioPacientes {

        @Test
        @DisplayName("cuandoExistenPacientesEnPersistencia_entoncesRetornaListaDeIdsComoStrings()")
        void cuandoExistenPacientesEnPersistencia_entoncesRetornaListaDeIdsComoStrings() {
            // Given
            PatientId p1 = new PatientId("HCG12345");
            PatientId p2 = new PatientId("HCG78901");
            persistencePort.saveLab(LabResult.create(p1, "Panel 1", Map.of("Glucosa", "90 mg/dL"), Instant.now(), false, "s3://b/1.pdf"));
            persistencePort.saveLab(LabResult.create(p2, "Panel 2", Map.of("Hemoglobina", "14 g/dL"), Instant.now(), false, "s3://b/2.pdf"));

            // When
            List<String> patients = resource.getPatients();

            // Then
            assertEquals(2, patients.size());
            assertTrue(patients.contains("HCG12345"));
            assertTrue(patients.contains("HCG78901"));
        }

        @Test
        @DisplayName("cuandoNoHayPacientesEnPersistencia_entoncesRetornaListaVacia()")
        void cuandoNoHayPacientesEnPersistencia_entoncesRetornaListaVacia() {
            // When
            List<String> patients = resource.getPatients();

            // Then
            assertNotNull(patients);
            assertTrue(patients.isEmpty());
        }
    }

    @Nested
    @DisplayName("Consulta de Último Laboratorio")
    class UltimoLaboratorio {

        @Test
        @DisplayName("cuandoPacienteExisteConLabs_entoncesRetorna200ConDto()")
        void cuandoPacienteExisteConLabs_entoncesRetorna200ConDto() {
            // Given
            PatientId pid = new PatientId("HCG12345");
            persistencePort.saveLab(LabResult.create(pid, "QUIMICA", Map.of("Glucosa", "95 mg/dL"), Instant.now(), false, "s3://b/1.pdf"));

            // When
            Response response = resource.getLatestLab("HCG12345");

            // Then
            assertEquals(200, response.getStatus());
            assertInstanceOf(LatestLabResponse.class, response.getEntity());
            LatestLabResponse dto = (LatestLabResponse) response.getEntity();
            assertEquals("HCG12345", dto.patientId());
            assertEquals("QUIMICA", dto.lab().testType());
        }

        @Test
        @DisplayName("cuandoPacienteNoTieneLabs_entoncesRetorna404()")
        void cuandoPacienteNoTieneLabs_entoncesRetorna404() {
            // When
            Response response = resource.getLatestLab("HCG99999");

            // Then
            assertEquals(404, response.getStatus());
        }

        @Test
        @DisplayName("cuandoPatientIdEsInvalido_entoncesRetorna400()")
        void cuandoPatientIdEsInvalido_entoncesRetorna400() {
            // When
            Response response = resource.getLatestLab("ID-INVALIDO!");

            // Then
            assertEquals(400, response.getStatus());
        }
    }

    @Nested
    @DisplayName("Flujo de Notificación")
    class Notificacion {

        @Test
        @DisplayName("cuandoLabExiste_entoncesNotificaYRetorna200()")
        void cuandoLabExiste_entoncesNotificaYRetorna200() {
            // Given
            PatientId pid = new PatientId("HCG12345");
            LabResult lab = LabResult.create(pid, "PANEL", Map.of("Glucosa", "90 mg/dL"), Instant.now(), false, "s3://b/1.pdf");
            persistencePort.saveLab(lab);

            // When
            NotifyLabRequest req = new NotifyLabRequest("HCG12345", "DR001");
            Response response = resource.notifyLabReady(lab.labId(), req);

            // Then
            assertEquals(200, response.getStatus());
            assertInstanceOf(LabResultResource.NotifyResponse.class, response.getEntity());
        }

        @Test
        @DisplayName("cuandoLabNoExiste_entoncesRetorna404()")
        void cuandoLabNoExiste_entoncesRetorna404() {
            // When
            NotifyLabRequest req = new NotifyLabRequest("HCG12345", "DR001");
            Response response = resource.notifyLabReady("lab-inexistente", req);

            // Then
            assertEquals(404, response.getStatus());
        }
    }
}
