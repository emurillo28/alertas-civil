# HCG Domain Models

## LabResult (Java Record)
```java
@RegisterForReflection(serialization = true)
public record LabResult(
    String resultId,           // UUID
    PatientId patientId,       // Value object
    String analyteCode,        // LOINC code
    String analyteName,        // Display name
    Double value,              // Numeric result
    String unit,               // UCUM unit
    LocalDateTime collectedAt, // Specimen collection time
    LocalDateTime resultedAt,  // Result verification time
    EstadoVisibilidad visibility, // PENDING | VISIBLE | CRITICAL_ALERT
    Integer version,           // OCC version for optimistic locking
    String clinicalSummary,    // Inline interpretive text
    String pdfPointer          // S3 URI for PDF report
) {}
```

## PatientId (Value Object)
```java
public record PatientId(String value) {
    public PatientId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PatientId cannot be blank");
        }
    }
}
```

## EstadoVisibilidad (Enum)
```java
public enum EstadoVisibilidad {
    PENDING,          // Result received, not yet reviewed
    VISIBLE,          // Reviewed, available to clinical staff
    CRITICAL_ALERT    // Exceeds thresholds, requires immediate action
}
```

## NotificacionEnviada (Record)
```java
public record NotificacionEnviada(
    String notificationId,
    String resultId,
    String destination,
    LocalDateTime sentAt,
    String status
) {}
```

## Rules
- All domain models are **immutable Java records**
- **OCC versioning** mandatory on LabResult (Integer version field)
- No setters, no mutable state
- Jackson/JSON-B annotations via `@RegisterForReflection(serialization=true)`
- Package: `com.hcg.alertas.domain.model`