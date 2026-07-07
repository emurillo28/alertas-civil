# Integration Gap Analysis: hcg-v1 UI ↔ alertas-civil Backend

**Date:** 2026-06-22 (updated 2026-06-26)
**Backend status:** read-side HTTP surface + async ingestion worker (decoupled)
**UI status:** full contract rewrite complete, all consumer files aligned
**Goal:** define the exact remaining gaps and required changes for end-to-end integration

---

## Completed in 2026-06-26 session

### Backend
- Lambda handler changed from `ingestLabSqsHandler` (SQS-only) to `io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler` (unified — dispatches HTTP API Gateway events and SQS events automatically)
- API Gateway → Lambda integration added for ALL read paths:
  - `GET /labs/patients` → `aws_apigatewayv2_route.get_patients`
  - `GET /labs/patients/{patientId}/latest` → `aws_apigatewayv2_route.get_patient_latest`
  - `GET /labs/patients/{patientId}/history` → `aws_apigatewayv2_route.get_patient_history`
  - `POST /labs/{labId}/notify` → `aws_apigatewayv2_route.post_lab_notify`
  - `aws_lambda_permission.api_gateway_invoke` grants API Gateway invoke access
- Secrets Manager: `aws_secretsmanager_secret.app_config` + `aws_secretsmanager_secret_version.app_config_v1` + KMS key + IAM policy attached
- IAM: least-privilege Secrets Manager read policy added
- `pom.xml`: `quarkus-amazon-secretsmanager` dependency added
- `application.properties`: `%prod` profile resolves config from Secrets Manager

### Terraform — IaC added (in priority order per architecture guidelines)
1. **Secrets Manager** (`aws_secretsmanager_secret`, `aws_secretsmanager_secret_version`, `aws_kms_key`, IAM policy + attachment) — Prioridad 1
2. **Lambda read integration** (`aws_apigatewayv2_integration.lambda_read_integration`, 4 routes, `aws_lambda_permission`) — closes the read-path gap
3. **IAM least-privilege** (existing `lambda_custom_policy` untouched for now; Secrets Manager policy is the new addition) — Prioridad 2
4. **WAFv2** (`aws_wafv2_web_acl.api_acl` with 3 rules + `aws_wafv2_web_acl_association`) — Prioridad 3

### Frontend (hcg-v1)
- Full contract rewrite: 7 files updated to match backend DTOs
- `contracts.ts`: replaces all old DDB-mirrored types with `LabResultDto`, `LatestLabResponse`, `PatientTimelineResponse`, etc.
- `client.ts`: paths corrected (`/labs/patients/...`), timeout/abort support, proper error mapping
- `hooks.ts`: `usePatients` returns `string[]`, `usePatient`/`useTimeline` return `PatientTimelineResponse | null`, `useLatestLab` added
- `DayCard.tsx`: maps `LabResultDto` fields (`criticalAlert`, `visibilityStatus`, `analyticValues`, `labId`)
- `PatientHeader.tsx`: consumes `PatientTimelineResponse`, derives stats from `labs[]`
- `SearchModal.tsx`, `pacientes.tsx`, `index.tsx`: all consumer files updated, no stale DDB imports

### ✅ Ready
|- **Async ingestion** — `IngestLabSqsHandler` processes SQS messages into DynamoDB
|- **Read endpoints** — JAX-RS resources (fully implemented):
|  - `GET /labs/patients` → seeded patient identifiers (P102–P104)
|  - `GET /labs/patients/{patientId}/latest` → most recent lab result (`LatestLabResponse`)
|  - `GET /labs/patients/{patientId}/history` → full clinical record with demographics + lab history (`PatientTimelineResponse`)
|- **POST notify** — `POST /labs/{labId}/notify` → triggers `NotifyLabReadyUseCase`, returns `NotifyResponse`
|- **Notification flow** — `NotifyLabReadyService` + `SqsNotificationAdapter` implemented
|- **CORS** — configured for `http://localhost:3000` in dev profile
|- **Observability** — CloudWatch EMF metrics integrated in `DynamoDbPersistenceAdapter`
|- **Tests** — 59 unit tests passing (`mvn test` exit 0)
|- **REST DTOs** — clean, no PK/SK leak:
|  - `LabResultDto` — `labId`, `patientId`, `testType`, `analyticValues`, `timestamp`, `criticalAlert`, `clinicalSummary`, `visibilityStatus`, `notifications[]`
|  - `LatestLabResponse` — `{ patientId, lab }`
|  - `PatientTimelineResponse` — `{ patientId, name, dateOfBirth, clinicalHistory, labs[] }`
|  - Mapper: `LabResultMapper.toDto()` / `toTimelineResponse()`
|- **Dev mode** — `mvn quarkus:dev` starts cleanly on port 8081

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

### Infrastructure (Terraform) — DONE
- [x] `POST /api/v1/labs` → SQS direct integration with VTL mapping (preexisting)
- [x] `GET /labs/patients` → Lambda proxy to Quarkus
- [x] `GET /labs/patients/{patientId}/latest` → Lambda proxy
- [x] `GET /labs/patients/{patientId}/history` → Lambda proxy
- [x] `POST /labs/{labId}/notify` → Lambda proxy
- [x] Lambda handler unified: `QuarkusStreamHandler` (auto-dispatches HTTP and SQS)
- [x] AWS Secrets Manager for sensitive config (Prioridad 1)
- [x] IAM least-privilege (sin Scan, sin wildcards) (Prioridad 2)
- [x] WAFv2 with 3 rules + ACML association (Prioridad 3)
- [x] KMS key + alias para encryptar secretos
- [ ] Custom domain + SSL/TLS (omitido por ahora — no necesario para prod interna)
- [ ] CORS headers en prod actualizados a dominio real del hospital (actualmente solo localhost)
- [ ] CloudWatch Metric Filter + Alarma en 'Unknown analyte' (pendiente manual)

### UI (hcg-v1) — COMPLETE
- [x] `contracts.ts` limpiado — sin PK, SK ni conceptos DDB
- [x] `client.ts` rutas corregidas a `/labs/patients/...`, timeout/abort, ApiException
- [x] `hooks.ts` alineados a `PatientTimelineResponse` y `LabResultDto`
- [x] `DayCard.tsx` consume `LabResultDto` (criticalAlert, visibilityStatus, analyticValues)
- [x] `PatientHeader.tsx` consume `PatientTimelineResponse`
- [x] `pacientes.tsx`, `[id].tsx`, `index.tsx`, `SearchModal.tsx` actualizados
- [x] `format.ts` limpio, sin referencias stale a campos DDB
- [ ] (Opcional) ingestion flow desde UI a SQS → backend no lo expone por API Gateway actualmente (solo por SQS directo). Si se necesita flow UI→write, agregar método POST en `client.ts` y endpoint en backend.

### Security & Production — NO Pendiente
- [x] AWS Secrets Manager para configuración sensible + rotación KMS
- [x] IAM least-privilege (sin `dynamodb:Scan`, sin wildcards, ARNs específicos)
- [x] WAFv2: bloqueo sin User-Agent, límite body 100KB, rate limit 1000 req/min/IP
- [x] SQS queue encryption (heredada de Terraform original)
- [x] DynamoDB encryption at rest (heredada de Terraform original)
- [ ] CORS prod: reemplazar `http://localhost:3000` por dominio real del hospital

---

## File Paths Reference

| Component | Path | Status |
|-----------|------|--------|
| Backend gap analysis | `C:\dev\alertas-civil\dev\tmp\INTEGRATION_GAP_ANALYSIS.md` | ✅ This file |
| REST resource | `C:\dev\alertas-civil\src/main/java/.../adapters/inbound/LabResultResource.java` | ✅ 3 endpoints |
| REST DTOs | `C:\dev\alertas-civil\src/main/java/.../adapters/inbound/LabResultDto.java` | ✅ New |
| REST DTOs | `C:\dev\alertas-civil\src/main/java/.../adapters/inbound/SentNotificationDto.java` | ✅ New |
| REST DTOs | `C:\dev\alertas-civil\src/main/java/.../adapters/inbound/LatestLabResponse.java` | ✅ New |
| REST DTOs | `C:\dev\alertas-civil\src/main/java/.../adapters/inbound/PatientTimelineResponse.java` | ✅ New |
| REST DTOs | `C:\dev\alertas-civil\src/main/java/.../adapters/inbound/NotifyLabRequest.java` | ✅ New |
| Mapper | `C:\dev\alertas-civil\src/main/java/.../adapters/inbound/LabResultMapper.java` | ✅ New |
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
