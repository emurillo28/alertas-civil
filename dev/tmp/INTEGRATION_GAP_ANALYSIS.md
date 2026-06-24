# Integration Gap Analysis: hcg-v1 UI ↔ alertas-civil Backend

**Date:** 2026-06-22  
**Backend status:** read-side HTTP surface + async ingestion worker (decoupled)  
**UI status:** read-only API integration, no write or notify flow implemented  
**Goal:** define the exact remaining gaps and required changes for end-to-end integration

---

## Current Backend Capabilities

### ✅ Ready
- **Async ingestion** — `IngestLabSqsHandler` processes SQS messages into DynamoDB
- **Read endpoints** — JAX-RS resources exposed:
  - `GET /api/v1/patients/{patientId}/labs/latest`
  - `GET /api/v1/patients/{patientId}/labs/history`
  - `POST /api/v1/labs/{labId}/notify`
- **Notification flow** — `NotifyLabReadyService` + `SqsNotificationAdapter` implemented
- **CORS** — configured for `http://localhost:3000` in dev profile
- **Observability** — CloudWatch EMF metrics integrated in `DynamoDbPersistenceAdapter`
- **Tests** — 59 unit tests passing (`mvn test` exit 0)
- **Dev mode** — `mvn quarkus:dev` starts cleanly on port 8081
- **DTOs are already clean on the backend** — `LabResultResource` returns REST DTOs without PK/SK

### ⚠️ Not Ready (by design)
- **No HTTP write endpoint** — ingestion must go through API Gateway → SQS direct integration (no Lambda proxy)
- **No API Gateway / Terraform** — infrastructure mapping not yet defined
- **No production IAM / WAF / Secrets** — security hardening still pending

---

## Current UI (hcg-v1) State

### ✅ Ready
- Read API client (`frontend/src/api/client.ts`) with GET methods
- Hooks (`frontend/src/api/hooks.ts`) for patients, patient profile, timeline
- React Native Expo app with patient list, timeline, detail screens

### ❌ Still Missing / Requires cleanup
- **Contracts leak database concepts** — `frontend/src/types/contracts.ts` still contains PK, SK, and DDB-style keys. This must be removed to preserve the Anti-Corruption Layer boundary.
- **No ingestion client** — no POST method, no API method for submitting labs
- **No client-side UUID generation** — `expo-crypto` not installed, no `labId` creation
- **No notify client** — no UI action to trigger notifications
- **No environment config for writes** — `EXPO_PUBLIC_BACKEND_URL` only covers reads

---

## Integration Architecture (Agreed)

```
Write:  [UI] --POST /labs--> [API Gateway] --Direct SQS--> [IngestLabSqsHandler] --> [DynamoDB]
Read:   [UI] --GET /labs---> [API Gateway] --Lambda Proxy--> [Quarkus JAX-RS] --> [DynamoDbPersistencePort]
Notify: [UI] --POST /notify-> [Quarkus JAX-RS] --> [SqsNotificationAdapter] --> [SQS]
```

---

## Critical Integration Landmines

### 1. Client-Generated `labId` (Paradox)
- **Problem:** API Gateway → SQS direct integration cannot return a backend-generated business ID
- **Solution:** UI must generate UUID v4 client-side before POST
- **Impact:** UI needs `expo-crypto` or similar for UUID generation; payload must include `labId`

### 2. API Gateway VTL Mapping for SQS
- **Problem:** SQS expects `Action=SendMessage&MessageBody=...` format, not raw JSON
- **Solution:** Terraform must define `aws_api_gateway_integration` with `type = "AWS"` and VTL template:
  ```vtl
  Action=SendMessage&MessageBody=$util.urlEncode($input.body)
  ```
- **Impact:** Without this, SQS receives malformed payloads; `IngestLabSqsHandler` cannot parse them

### 3. LocalStack Service Integration Parity
- **Problem:** API Gateway → SQS direct integration may not work in LocalStack 3.4.0 without v2 provider
- **Solution:** Ensure LocalStack container uses `EDGE_PORT` and API Gateway v2 provider
- **Impact:** Local testing of write path requires this configuration

### 4. Backend Write-Payload Contract
- **Current DTO:** `IngestLabRequest` (Java record) expects:
  ```java
  String labId, String patientId, String testType,
  Map<String, String> analyticValues, String timestamp,
  Boolean criticalAlert, String s3Path
  ```
- **UI must send:** JSON matching this structure exactly
- **Critical field:** `analyticValues` must be `Record<string, string>` (NOT nested objects)

### 5. Read-Path Error Contract
- **Empty history (patient exists, no labs):** `200 OK` with empty array `[]`
- **Invalid UUID format:** `400 Bad Request` — backend returns clear validation error before hitting DynamoDB
- **Patient not found:** `404 Not Found` — patient does not exist in the system
- **Downstream DynamoDB timeout / unavailable:** `503 Service Unavailable` — Quarkus fault tolerance triggers; CloudWatch alarm should also fire
- **Implication for UI:** React Native must handle all four states explicitly (empty list, validation error, not found, service unavailable). Do not assume 200 = data present.

### 6. Backend `analyticValues` Parsing Behavior
- **Standardization:** Backend accepts free-form strings, not a fixed enum of analyte keys
- **Matching:** Case-insensitive lookup against known thresholds lowercased at startup
- **Numeric extraction:** Uses regex `^(-?\d+(?:\.\d+)?)` to extract the leading numeric portion (e.g. `"14.5 g/dL"` → `14.5`)
- **Unknown analytes:** Treated as `0.0` with a warning log; no hard rejection
- **Implication for UI:** Machines can send unexpected keys/units; backend will not crash, but critical alert detection is only accurate for the known threshold set

### 6. Backend Response DTOs Are Already Clean
- **Clarification:** `LabResultResource` maps domain objects to REST DTOs before serialization. It does **not** expose PK/SK.
- **Leak location:** The PK/SK leak is only in `frontend/src/types/contracts.ts`, not in the backend.
- **Required action:** Update `contracts.ts` to remove DynamoDB concepts. This has **not** been applied yet.

---

## Architecture Decisions (Confirmed)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Write path for ingestion | API Gateway → SQS direct integration | Avoids Lambda proxy overhead and keeps worker lean |
| API Gateway type | Regional | Lowest latency for local Jalisco traffic; no CloudFront hop |
| SQS queues | Separate queues: ingestion + notifications | Prevents head-of-line blocking between lab data and critical alerts |
| Client ID generation | `expo-crypto` UUIDv4 | Native-backed, cryptographically sound, works in RN |
| Contract boundary | Backend DTOs are clean; UI types must be cleaned | ACL preserved; DB concepts stay server-side |

---

## Implementation Checklist

### Backend (Complete)
- [x] JAX-RS read endpoints (`LabResultResource`)
- [x] Notification use case + SQS adapter
- [x] CORS configuration
- [x] All tests passing
- [x] Clean REST DTOs (no PK/SK)

### Infrastructure (Terraform) — Phase 2
- [ ] Define API Gateway REST API (regional)
- [ ] `POST /api/v1/labs` → SQS direct integration with VTL mapping
- [ ] `GET /api/v1/patients/{id}/labs/**` → Lambda proxy to Quarkus
- [ ] `POST /api/v1/labs/{labId}/notify` → Lambda proxy
- [ ] Custom domain: `dev-api.hcg.local`
- [ ] CORS headers on API Gateway responses
- [ ] Two SQS queues: ingestion + notifications
- [ ] Deployment stage mappings
- [ ] Create CloudWatch Metric Filter and Alarm on 'Unknown analyte' warning logs to ensure you detect lab machine configuration drift immediately

### UI (hcg-v1) — Required Changes
- [ ] **Add `expo-crypto`** for UUID generation
- [ ] **Clean `contracts.ts`** — remove PK, SK, and DDB concepts; align with backend REST DTOs
- [ ] **Add ingestion client method** — `POST /api/v1/labs` to API Gateway
- [ ] **Build ingestion payload** — include client-generated `labId`, `analyticValues` as flat map
- [ ] **Handle 202 Accepted + MessageId** — UI should treat `MessageId` as receipt, not `labId`
- [ ] **Add notify client method** — `POST /api/v1/labs/{labId}/notify`
- [ ] **Validate contract alignment** — ensure contract matches backend REST responses

### Security & Production (Pending)
- [ ] AWS Secrets Manager for credentials
- [ ] SSL/TLS enforcement
- [ ] WAF rules for Lambda protection
- [ ] Least-privilege IAM policies (Terraform)
- [ ] SQS queue encryption
- [ ] DynamoDB encryption at rest

---

## File Paths Reference

| Component | Path | Status |
|-----------|------|--------|
| Backend gap analysis | `C:\dev\alertas-civil\dev\tmp\INTEGRATION_GAP_ANALYSIS.md` | ✅ This file |
| REST resource | `C:\dev\alertas-civil\src/main/java/.../adapters/inbound/LabResultResource.java` | ✅ Clean DTOs |
| Notification service | `C:\dev\alertas-civil\src/main/java/.../service/NotifyLabReadyService.java` | ✅ Ready |
| SQS adapter | `C:\dev\alertas-civil\src/main/java/.../adapters/outbound/SqsNotificationAdapter.java` | ✅ Ready |
| Ingestion DTO | `C:\dev\alertas-civil\src/main/java/.../adapters/inbound/IngestLabRequest.java` | ✅ Ready |
| UI client | `/c/dev/hcg-v1/frontend/src/api/client.ts` | ⚠️ Read-only |
| UI types | `/c/dev/hcg-v1/frontend/src/types/contracts.ts` | ⚠️ Needs PK/SK removal |
| UI hooks | `/c/dev/hcg-v1/frontend/src/api/hooks.ts` | ⚠️ No write hooks |
| Dev profile | `C:\dev\alertas-civil/src/main/resources/application-dev.properties` | ✅ CORS + SQS config |
| Prod profile | `C:\dev\alertas-civil/src/main/resources/application-prod.properties` | ✅ Env var injection |

---

## Next Actions (No Immediate UI Code Changes)

1. Review analytics-variations paragraph in Section 5 and confirm whether stricter schema validation is required
2. Decide whether UI team should clean `contracts.ts` now or after verifying read endpoints
3. Confirm acceptance of two-SQS-queue topology and regional API Gateway
4. Schedule Terraform/API Gateway work (Phase 2) after read-path verification
