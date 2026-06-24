# API Contract: HCG Clinical Alerts System (hcg-v1)

**Version:** 1.0  
**Last Updated:** 2026-06-10  
**Backend:** Quarkus 3.36 + AWS Lambda (SQS) + DynamoDB Single-Table  
**UI:** hcg-v1 (React/TypeScript)

---

## Overview

This contract defines the **exact JSON shapes** exchanged between the UI (hcg-v1) and the backend. The backend is an **event-driven SQS Lambda** — no REST endpoints. The UI communicates via:

1. **Ingestion** — POST to API Gateway → SQS → Lambda (async, fire-and-forget)
2. **Query** — Direct DynamoDB reads via backend query endpoints (if added) or AppSync
3. **Notifications** — WebSocket / SSE from AppSync or polling

> ⚠️ **Current backend only implements SQS ingestion.** Query/notification APIs need to be added for hcg-v1.

---

## 1. Lab Result Ingestion (UI → Backend)

### Endpoint
```
POST https://{api-id}.execute-api.{region}.amazonaws.com/api/v1/labs
```
> Implemented via **API Gateway → SQS direct integration** (no Lambda authorizer, auth via Cognito JWT in headers)

### Request: `LabResultIngestRequest`
```json
{
  "labId": "uuid-v4",                    // optional: auto-generated if omitted
  "patientId": "PAC001",                 // required: alphanumeric 1-64 chars
  "testType": "Complete Blood Count",    // required: display name
  "analyticValues": {                    // required: at least 1 analyte
    "Hemoglobin": "14.5 g/dL",
    "Leukocytes": "7500 /uL",
    "Platelets": "250000 /uL"
  },
  "timestamp": "2026-06-10T10:30:00Z",   // required: ISO8601 specimen collection
  "criticalAlert": false,                // optional: backend evaluates if omitted
  "s3Path": "s3://hc-laboratories/2026/06/10/PAC001/BH-001.pdf" // required
}
```

### Response
```json
{
  "labId": "uuid-v4",
  "status": "ACCEPTED",                  // always ACCEPTED (async processing)
  "message": "Lab result queued for processing"
}
```
> HTTP 202 on success. Validation errors return 400 synchronously (before SQS).

---

## 2. Domain Models (Backend Internal)

> These differ from the HCG `.hcg/schemas/domain_models.md`. The backend uses a **Map-based analytic values** model; UI sends flat key-value pairs.

### LabResult (Java Record - Internal)
```java
public record LabResult(
    String labId,                    // UUID (sort key: LAB#<labId>)
    PatientId patientId,             // value object (partition key: PATIENT#<value>)
    String testType,                 // e.g., "Complete Blood Count"
    Map<String, String> analyticValues, // "Hemoglobin" → "14.5 g/dL"
    Instant timestamp,
    boolean criticalAlert,
    String s3Path,
    String clinicalSummary,          // null initially, populated by AI flow
    long version,                    // OCC (0 = new)
    VisibilityStatus visibilityStatus, // PENDING | NOTIFIED | READ_CONFIRMED
    List<SentNotification> notifications
) {}
```

### PatientId (Value Object)
```java
public record PatientId(String value) {
    // alphanumeric, 1-64 chars, strictly [A-Za-z0-9]
}
```

### VisibilityStatus (Enum)
| Value | Meaning |
|-------|---------|
| `PENDING` | Ingested, awaiting notification |
| `NOTIFIED` | Notification queued/sent to clinician |
| `READ_CONFIRMED` | Clinician acknowledged reading |

### SentNotification (Record)
```java
public record SentNotification(
    String notificationId,
    String recipient,           // e.g., "DR001", "+15551234567"
    NotificationChannel channel, // SMS | WHATSAPP | EMAIL | PUSH | IN_APP
    NotificationStatus status,  // PENDING | SENT | READ | FAILED
    Instant timestampSend,
    Instant timestampRead       // null unless status == READ
) {}
```

---

## 3. DynamoDB Single-Table Schema (Internal)

| Item Type | PK | SK | Notes |
|-----------|-----|-----|-------|
| LabResult | `PATIENT#<patientId>` | `LAB#<labId>` | version for OCC |
| LabResult (GSI1) | `STATUS#<visibilityStatus>` | `TIMESTAMP#<timestamp>` | query pending/critical |
| LabResult (GSI2) | `PATIENT#<patientId>` | `ANALYTE#<analyteKey>` | analyte-specific history |
| Notification | `PATIENT#<patientId>` | `NOTIF#<notificationId>` | inline in LabResult item |

**Table name:** `hc-alertas` (dev) / `hc-alertas-prod` (prod)  
**Region:** `us-east-1`

---

## 4. Clinical Thresholds (Backend Evaluation)

> Backend auto-evaluates `criticalAlert` from `analyticValues` keys (case-insensitive).

| Analyte Key | Critical Low | Critical High | Unit |
|-------------|--------------|---------------|------|
| `hemoglobina` / `hemoglobin` | < 7.0 | > 20.0 | g/dL |
| `potasio` / `potassium` | < 2.5 | > 6.0 | mmol/L |
| `glucosa` / `glucose` | < 50.0 | > 400.0 | mg/dL |
| `sodio` / `sodium` | < 120.0 | > 160.0 | mmol/L |
| `creatinina` / `creatinine` | — | > 10.0 | mg/dL |
| `plaquetas` / `platelets` | < 50.0 | > 1000.0 | ×10³/µL |

> Keys matched via `.toLowerCase().trim()` on Map keys.

---

## 5. Query APIs (Needed for hcg-v1)

> **Not yet implemented.** Add these REST endpoints for UI:

### Get Latest Lab for Patient
```
GET /api/v1/patients/{patientId}/labs/latest
```
**Response:** `LabResultView` (see below)

### Get Patient Lab History
```
GET /api/v1/patients/{patientId}/labs?limit=20&analyte=Hemoglobin
```
**Response:** `LabResultView[]`

### Get Lab by ID
```
GET /api/v1/labs/{labId}
```
**Response:** `LabResultView`

### LabResultView (UI-friendly projection)
```json
{
  "labId": "uuid",
  "patientId": "PAC001",
  "testType": "Complete Blood Count",
  "analyticValues": [
    { "name": "Hemoglobin", "value": "14.5 g/dL", "critical": false },
    { "name": "Leukocytes", "value": "7500 /uL", "critical": false }
  ],
  "timestamp": "2026-06-10T10:30:00Z",
  "criticalAlert": false,
  "clinicalSummary": null,
  "visibilityStatus": "PENDING",
  "version": 0,
  "notifications": []
}
```

---

## 6. Notification/Visibility Flow (UI Actions)

### Mark as Notified (Backend → UI)
```json
POST /api/v1/labs/{labId}/notify
{
  "recipient": "DR001",
  "channel": "PUSH"
}
```

### Confirm Read (UI → Backend)
```json
POST /api/v1/labs/{labId}/read
{
  "clinicianId": "DR001"
}
```
> Updates `visibilityStatus` to `READ_CONFIRMED` with OCC version check.

---

## 7. Error Responses

| Code | Scenario | Body |
|------|----------|------|
| 400 | Invalid `patientId` format | `{ "field": "patientId", "message": "must be alphanumeric 1-64 chars" }` |
| 400 | Empty `analyticValues` | `{ "field": "analyticValues", "message": "at least one analyte required" }` |
| 400 | Missing `s3Path` | `{ "field": "s3Path", "message": "required for PDF storage" }` |
| 404 | Lab not found (query) | `{ "code": "NOT_FOUND", "message": "LabResult not found" }` |
| 409 | OCC version conflict | `{ "code": "OCC_CONFLICT", "expectedVersion": 3, "actualVersion": 4 }` |
| 503 | DynamoDB throttled | `{ "code": "THROTTLED", "retryAfterMs": 1000 }` |

---

## 8. Environments

| Env | API Base URL | DynamoDB Table | LocalStack |
|-----|--------------|----------------|------------|
| Local | `http://localhost:8080` | `hc-alertas-dev` | Required |
| Dev | `https://dev-api.hcg.local` | `hc-alertas-dev` | Optional |
| Prod | `https://api.hcg.gob.mx` | `hc-alertas-prod` | N/A |

---

## 9. Versioning & Compatibility

- **Request/Response:** Additive only (new optional fields OK)
- **Enum values:** New values treated as `UNKNOWN` by older clients
- **OCC version:** Never reset; always increments

---

## 10. OpenAPI Spec

Auto-generated at `/q/openapi` (dev mode only).  
Production: extract during build, publish to API Gateway.

```bash
# Generate locally
mvn quarkus:dev
# Browse: http://localhost:8080/q/swagger-ui
```