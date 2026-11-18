# HCG Clinical Thresholds (Critical Values)

## Alert Rules
| Analyte | Critical Low | Critical High | Unit | Action |
|---------|-------------|---------------|------|--------|
| Hemoglobin | < 7.0 | > 18.0 | g/dL | Immediate physician notification |
| Potassium | < 3.0 | > 6.0 | mmol/L | Cardiac monitoring, STAT callback |
| Glucose | < 50 | > 400 | mg/dL | Insulin/Dextrose protocol activation |
| Sodium | < 125 | > 155 | mmol/L | Neurology consult, fluid management |
| Creatinine | — | > 3.0 | mg/dL | Nephrology alert, contrast hold |
| Platelets | < 50 | > 1000 | ×10³/µL | Hematology consult, transfusion eval |

## Logic (Pseudocode)
```java
public EstadoVisibilidad determineVisibility(LabResult result) {
    return switch (result.analyteCode()) {
        case "718-7"  -> checkRange(result.value(), 7.0, 18.0);   // Hemoglobin
        case "2823-3" -> checkRange(result.value(), 3.0, 6.0);    // Potassium
        case "2345-7" -> checkRange(result.value(), 50.0, 400.0); // Glucose
        case "2951-2" -> checkRange(result.value(), 125.0, 155.0); // Sodium
        case "2160-0" -> checkHigh(result.value(), 3.0);          // Creatinine
        case "777-3"  -> checkRange(result.value(), 50.0, 1000.0); // Platelets
        default -> EstadoVisibilidad.VISIBLE;
    };
}

private EstadoVisibilidad checkRange(Double value, double low, double high) {
    return (value < low || value > high) ? EstadoVisibilidad.CRITICAL_ALERT : EstadoVisibilidad.VISIBLE;
}

private EstadoVisibilidad checkHigh(Double value, double high) {
    return (value > high) ? EstadoVisibilidad.CRITICAL_ALERT : EstadoVisibilidad.VISIBLE;
}
```

## Notification Flow
1. Lab system → SQS (`LabResultIngest` queue)
2. Lambda (`IngestLabHandler`) → validates, persists to DynamoDB
3. If `CRITICAL_ALERT` → `NotifyLabReadyUseCase` triggers SNS/SES
4. CloudWatch metrics: `CriticalAlertsCount`, `IngestionLatencyMs`