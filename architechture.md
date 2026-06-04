# Blueprint de Arquitectura: Backend Alertas Hospital Civil

## 1. Resumen Ejecutivo y Objetivos del Sistema
Este sistema es el "cerebro" pesado y de alta concurrencia encargado de procesar la ingesta masiva de laboratorios clínicos, evaluar criterios de alerta crítica en tiempo real y persistir los resúmenes médicos estructurados. 

El diseño está optimizado para:
1. **Latencia ultra-baja y alta eficiencia en costos** en entornos Serverless (AWS Lambda + GraalVM Native Image).
2. **Eliminación absoluta de la complejidad reactiva**, delegando la concurrencia masiva de I/O a la JVM mediante hilos virtuales.
3. **Persistencia NoSQL pura**, consolidando múltiples entidades médicas en una única tabla de Amazon DynamoDB para evitar joins de red.

---

## 2. Stack Tecnológico Estricto
* **Lenguaje:** Java 25 (Uso mandatorio de `records` para inmutabilidad y APIs modernas).
* **Framework:** Quarkus 3.31+ (Configurado en modo imperativo con soporte nativo para Project Loom).
* **Compilación:** GraalVM Native Executable (Para mitigar los *Cold Starts* de AWS Lambda a < 50ms).
* **Persistencia:** AWS SDK para DynamoDB v2 (Cliente puramente síncrono: `DynamoDbClient`).
* **Runtime Cloud:** AWS Lambda (Arquitectura multi-handler o función única optimizada).

---

## 3. Principios de Diseño Arquitectónico

### A. Arquitectura Hexagonal Estricta (Ports & Adapters)
El sistema se divide en tres capas desacopladas con reglas de dependencia unidireccionales de afuera hacia adentro:

1.  **Dominio (`domain`):** Contiene los modelos de negocio (`models`, `value objects`) y las excepciones empresariales. Es 100% Java puro. Tiene **estrictamente prohibido** importar cualquier anotación de frameworks (Quarkus, CDI) o SDKs de infraestructura (AWS).
2.  **Aplicación (`application`):** Define los casos de uso (orquestadores de lógica clínica) e interfaces de salida (Puertos). La inyección de dependencias se realiza mediante constructores estándar compatibles con CDI de Quarkus (`@ApplicationScoped`).
3.  **Infraestructura (`infrastructure`):** Implementa los adaptadores del mundo exterior. Aquí viven los controladores de entrada (AWS Lambda Handlers / Endpoints REST) y los adaptadores de persistencia (DynamoDB).

### B. Modelo de Concurrencia Imperativa (Project Loom)
Queda **completamente prohibido** el uso de programación reactiva tradicional (Mutiny, `Uni`, `Multi`, callbacks o código asíncrono explícito). 

* **Mecánica:** Todo el código se escribe de forma secuencial, síncrona y bloqueante.
* **Ejecución:** Los puntos de entrada en la capa de infraestructura se anotan con `@RunOnVirtualThread`. Al realizar I/O de red contra DynamoDB o S3 usando el cliente síncrono estándar, la JVM desmonta automáticamente el hilo virtual del hilo real del procesador (*Carrier Thread*), escalando a millones de peticiones concurrentes con una huella de memoria mínima.
* **Restricción de Bloqueo (Thread Pinning):** No se permite el uso de la palabra reservada `synchronized` en zonas de I/O crítico ni llamadas a código nativo que bloqueen el hilo transportador. Para exclusión mutua concurrente se debe optar exclusivamente por `ReentrantLock` o estructuras CAS como `ConcurrentHashMap` y `LongAdder`.

---

## 4. Diseño de Base de Datos: Single-Table Design (DynamoDB)

Toda la persistencia se consolida en una única tabla física de AWS llamada `hc-alertas`. Se eliminan las relaciones referenciales en favor de llaves genéricas de partición (`PK`) y ordenamiento (`SK`).

### A. Esquema de Llaves y Patrones de Acceso

| Entidad / Caso de Uso | PK (Partition Key) | SK (Sort Key) | Atributos Críticos Adjuntos |
| :--- | :--- | :--- | :--- |
| **Metadatos del Paciente** | `PATIENT#<PatientId>` | `METADATA` | Nombre, FechaNacimiento, HistorialClinico |
| **Resultado de Laboratorio** | `PATIENT#<PatientId>` | `LAB#<LabId>` | TipoExamen, Analiticas (JSON), EsAlertaCritica, `Resumen_Clinico` (Inline), `S3_Path_Completo`, `Version` (OCC) |
| **Rotación del Médico** | `DR#<DoctorId>` | `ROTATION#<Año-Mes>` | Especialidad actual (Ej: Pediatría), PisoAsignado |

### B. Reglas de Mapeo Clínico y Optimización NoSQL
1.  **Resumen Clínico Inline:** El resumen médico se almacena como un atributo enriquecido dentro del ítem del laboratorio (`LAB#<LabId>`). No se separa en un ítem aparte para garantizar una sola lectura atómica (1 RCU) cuando la aplicación móvil solicite el laboratorio con su respectiva interpretación clínica.
2.  **Inmutabilidad PDF:** El atributo `S3_Path_Completo` almacena la ruta exacta del binario PDF original emitido por los equipos del laboratorio. El backend nunca renderiza HTML para impresión médica; sirve directamente el puntero al archivo inmutable alojado en Amazon S3.
3.  **Control de Concurrencia Optimista (OCC):** Todo ítem de laboratorio posee un atributo numérico obligatorio llamado `Version`. Cualquier mutación debe validar la concurrencia mediante expresiones condicionales de DynamoDB (`ConditionExpression = "version = :expectedVersion"`) para evitar colisiones de datos si dos médicos intentan guardar notas de evolución concurrentemente.

---

## 5. Estructura del Código Fuente
El agente de IA debe generar y respetar rigurosamente la siguiente topología de paquetes:

```text
src/main/java/com/hospital/alertas/
├── domain/
│   ├── model/                  # Records y Entidades inmutables puras.
│   └── exception/              # Excepciones de negocio (ej. LabValidationException).
├── application/
│   ├── ports/
│   │   ├── inbound/            # Interfaces UseCase (Entrada).
│   │   └── outbound/           # Interfaces PersistencePort (Salida).
│   └── service/                # Orquestadores de la lógica de negocio.
└── infrastructure/
    ├── adapters/
    │   ├── inbound/            # Lambda Handlers anotados con @RunOnVirtualThread.
    │   └── persistence/        # Mapeadores de DynamoDbClient y control de OCC.
    └── config/                 # Productores CDI y configuraciones globales de Quarkus.