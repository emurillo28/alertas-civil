# HCG Database Schema (DynamoDB Single Table)

## Table
- **Name**: `ALERTA_CIVIL_MASTER`
- **Partition Key**: `PK` (String)
- **Sort Key**: `SK` (String)

## Key Structure

### LabResult
```
PK: PROJECT#alertas-civil
SK: LABRESULT#{patientId}#{resultedAt:ISO8601}
GSI1PK: STATUS#{estadoVisibilidad}
GSI1SK: TIMESTAMP#{resultedAt}
GSI2PK: PATIENT#{patientId}
GSI2SK: ANALYTE#{analyteCode}
```

### NotificacionEnviada
```
PK: PROJECT#alertas-civil
SK: NOTIFICACION#{notificationId}
GSI1PK: RESULT#{resultId}
GSI1SK: TIMESTAMP#{sentAt}
```

## Global Secondary Indexes
| Index | PK | SK | Use Case |
|-------|-----|-----|----------|
| GSI1 | `STATUS#{estadoVisibilidad}` | `TIMESTAMP#{resultedAt}` | Query critical alerts by status + time |
| GSI2 | `PATIENT#{patientId}` | `ANALYTE#{analyteCode}` | Patient's lab history by analyte |

## Access Patterns
| Query | Method |
|-------|--------|
| Get LabResult by ID | `GetItem` PK=PROJECT#alertas-civil, SK=LABRESULT#{patientId}#{timestamp} |
| Get patient's latest results | `Query` PK=PATIENT#{patientId}, SK begins_with LABRESULT# |
| Get all versions of result | `Query` PK=LABRESULT#{studyId}, SK begins_with VERSION# |
| List critical alerts | `Query` GSI1 PK=STATUS#CRITICAL_ALERT |
| Get notifications for result | `Query` GSI1 PK=RESULT#{resultId} |

## OCC Versioning
- On UPDATE: `SET version = version + 1` with condition `version = :expected`
- Fail if version mismatch → retry with fresh read

## TTL
- NotificacionEnviada: TTL = sentAt + 90 days (auto-cleanup)