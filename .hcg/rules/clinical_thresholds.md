# HCG Clinical Thresholds (Critical Values)

Critical alert detection is performed during lab ingestion by evaluating `analyticValues` (key-value strings) against predefined emergency ranges.

## Critical Alert Thresholds

| Analyte | Critical Low | Critical High | Unit | Clinical Action |
|---------|-------------|---------------|------|-----------------|
| Hemoglobin | < 7.0 | > 20.0 | g/dL | Immediate physician notification |
| Potassium | < 2.5 | > 6.0 | mmol/L | Cardiac monitoring, STAT callback |
| Glucose | < 50.0 | > 400.0 | mg/dL | Insulin/Dextrose protocol activation |
| Sodium | < 120.0 | > 160.0 | mmol/L | Neurology consult, fluid management |
| Creatinine | — | > 10.0 | mg/dL | Nephrology alert, urgent dialysis evaluation |
| Platelets | < 50.0 | > 1000.0 | ×10³/µL | Hematology consult, transfusion eval |

## Parsing & Matching Behavior

- **Case-Insensitive Normalization:** Analyte keys are trimmed and converted to lower-case (e.g. `hemoglobina`, `hemoglobin`, `HEMOGLOBIN` all match).
- **Numeric Extraction:** Leading numeric portion extracted via regex (e.g., `"14.5 g/dL"` → `14.5`).
- **Critical Flag:** If any single analyte crosses its critical threshold, `criticalAlert` is marked `true` on the `LabResult`.
- **Unknown Analytes:** Unrecognized analytes log a warning and default to non-critical without rejecting the ingestion batch.

## Ingestion Flow
1. Laboratory machine / UI → `POST /api/v1/labs` → API Gateway → SQS (`hc-alertas-ingest-queue`).
2. Lambda (`IngestLabSqsHandler`) processes batch via Virtual Threads.
3. `IngestLabService` evaluates thresholds and saves to DynamoDB (`hc-alertas`) with OCC.
4. If `criticalAlert == true`, alert is staged for dispatch (`VisibilityStatus.PENDING`).
