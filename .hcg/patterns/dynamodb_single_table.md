# HCG DynamoDB Single Table Pattern

## Core Concept
All entities in one table (`hc-alertas`, configurable via `app.dynamodb.table-name`). Relationships via PK/SK/GSI.

## Key Design
| Entity | PK | SK |
|--------|-----|-----|
| LabResult | `PATIENT#{patientId}` | `LAB#{labId}` |
| Patient Metadata | `PATIENT#{patientId}` | `METADATA` |
| Critical Alerts (GSI1) | `STATUS#{estadoVisibilidad}` | `TIMESTAMP#{resultedAt}` |
| Patient History by Analyte (GSI2) | `PATIENT#{patientId}` | `ANALYTE#{analyteCode}` |

## GSI Definitions
| Index | PK | SK | Purpose |
|-------|-----|-----|---------|
| GSI1 | `STATUS#{estadoVisibilidad}` | `TIMESTAMP#{resultedAt}` | Critical alerts by visibility status |
| GSI2 | `PATIENT#{patientId}` | `ANALYTE#{analyteCode}` | Patient history by analyte |

## Access Patterns
| Query | PK | SK Range | Index |
|-------|-----|----------|-------|
| LabResult by ID | `PATIENT#{patientId}` | `LAB#{labId}` | — |
| Patient's results | `PATIENT#{patientId}` | `LAB#*` | — |
| Patient metadata | `PATIENT#{patientId}` | `METADATA` | — |
| Critical alerts by status | `STATUS#{estado}` | `TIMESTAMP#*` | GSI1 |
| Patient history by analyte | `PATIENT#{patientId}` | `ANALYTE#*` | GSI2 |

## Quarkus Integration (Actual Implementation)

The real adapter is **`DynamoDbPersistenceAdapter`** (not `DynamoDbLabAdapter`), implementing `LabPersistencePort` with **synchronous `DynamoDbClient` + Project Loom virtual threads** — no reactive APIs (Mutiny/Uni).

```java
@ApplicationScoped
public class DynamoDbPersistenceAdapter implements LabPersistencePort {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoDbPersistenceAdapter(
            DynamoDbClient dynamoDbClient,
            @ConfigProperty(name = "app.dynamodb.table-name", defaultValue = "hc-alertas") String tableName
    ) {
        this.dynamoDbClient = Objects.requireNonNull(dynamoDbClient);
        this.tableName = Objects.requireNonNull(tableName);
    }

    @Override
    public void saveLab(LabResult lab) {
        // Uses UpdateItem with OCC via ConditionExpression on Version
        // PK = PATIENT#{patientId}, SK = LAB#{labId}
        // For new items (version=0): conditionExpression = "attribute_not_exists(PK) AND attribute_not_exists(SK)"
        // For updates: conditionExpression = "Version = :expectedVersion"
    }

    @Override
    public Optional<LabResult> obtenerUltimoLaboratorio(PatientId patientId) {
        // Query by PK = PATIENT#{patientId}, SK begins_with "LAB#"
        // Returns max by timestamp
    }

    @Override
    public Optional<PatientTimeline> obtenerExpedienteCompleto(PatientId patientId) {
        // Single query by PK = PATIENT#{patientId}
        // Filters SK = METADATA (demographics) and SK starting with LAB# (labs)
    }

    @Override
    public void actualizarEstadoVisibilidad(PatientId patientId, String labId,
                                             EstadoVisibilidad nuevoEstado, long expectedVersion) {
        // UpdateItem with SET EstadoVisibilidad = :estado, Version = :version
        // ConditionExpression: Version = :expectedVersion
    }

    @Override
    public void registrarNotificacion(PatientId patientId, String labId,
                                       NotificacionEnviada notificacion,
                                       EstadoVisibilidad nuevoEstado, long expectedVersion) {
        // Atomic: appends to NotificacionesEnviadas list, updates EstadoVisibilidad, increments Version
        // All in one UpdateItem with ConditionExpression on Version
    }
}
```

**Key architectural decisions:**
- **Synchronous blocking calls** — virtual threads (Project Loom) unpark efficiently during network I/O, no thread pinning
- **No `synchronized` blocks** — DynamoDB client is thread-safe; using `java.util.logging` avoids carrier thread pinning
- **OCC via `ConditionExpression`** — `Version` field enforced on every write
- **No reactive types** — port contract explicitly forbids `Uni`, `Multi`, or Mutiny in application layer

## OCC Versioning Pattern
```java
// On update (version > 0)
UpdateItemRequest.builder()
    .tableName(tableName)
    .key(key)
    .updateExpression("SET #v = #v + :inc, #data = :data, #estado = :estado")
    .conditionExpression("#v = :expected")
    .expressionAttributeNames(Map.of(
        "#v", "Version",
        "#data", "ValoresAnaliticos",
        "#estado", "EstadoVisibilidad"
    ))
    .expressionAttributeValues(Map.of(
        ":inc", AttributeValue.fromN("1"),
        ":expected", AttributeValue.fromN(String.valueOf(currentVersion)),
        ":data", AttributeValue.fromM(newData),
        ":estado", AttributeValue.fromS(nuevoEstado.name())
    ))
    .build();

// On insert (version == 0)
UpdateItemRequest.builder()
    .conditionExpression("attribute_not_exists(PK) AND attribute_not_exists(SK)")
    ...
```

## Attribute Reference
| Constant | DynamoDB Name | Type | Purpose |
|----------|---------------|------|---------|
| `ATTR_PK` | `PK` | String | Partition key (`PATIENT#{id}`) |
| `ATTR_SK` | `SK` | String | Sort key (`LAB#{id}` or `METADATA`) |
| `ATTR_VERSION` | `Version` | Number | OCC version counter |
| `ATTR_TIPO_EXAMEN` | `TipoExamen` | String | Exam type code |
| `ATTR_VALORES_ANALITICOS` | `ValoresAnaliticos` | Map | Analyte → value map |
| `ATTR_TIMESTAMP` | `Timestamp` | String | ISO-8601 instant |
| `ATTR_ES_ALERTA_CRITICA` | `EsAlertaCritica` | Boolean | Critical alert flag |
| `ATTR_S3_PATH` | `S3_Path_Completo` | String | S3 PDF pointer |
| `ATTR_RESUMEN_CLINICO` | `Resumen_Clinico` | String | Clinical summary (optional) |
| `ATTR_ESTADO_VISIBILIDAD` | `EstadoVisibilidad` | String | `PENDIENTE`/`NOTIFICADO`/`CONFIRMADO_LECTURA` |
| `ATTR_NOTIFICACIONES` | `NotificacionesEnviadas` | List | Notification history |