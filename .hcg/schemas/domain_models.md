# HCG Domain Models

Package: `com.hospital.alertas.domain.model`

## LabResult (Java Record)
```java
@RegisterForReflection(serialization = true)
public record LabResult(
    String labId,
    PatientId patientId,
    String testType,
    Map<String, String> analyticValues,
    Instant timestamp,
    boolean criticalAlert,
    String s3Path,
    String clinicalSummary,
    long version,
    VisibilityStatus visibilityStatus,
    List<SentNotification> notifications
) {}
```

## PatientId (Value Object)
```java
public record PatientId(String value) {
    public PatientId {
        if (value == null || value.isBlank() || !value.matches("^[a-zA-Z0-9]{1,64}$")) {
            throw new LabValidationException("PatientId must be alphanumeric (1-64 characters)");
        }
    }
}
```

## VisibilityStatus (Enum)
```java
public enum VisibilityStatus {
    PENDING,
    NOTIFIED,
    READ_CONFIRMED
}
```

## SentNotification (Record)
```java
public record SentNotification(
    String notificationId,
    String recipient,
    NotificationChannel channel, // SMS, WHATSAPP, EMAIL, PUSH, IN_APP
    NotificationStatus status,   // PENDING, SENT, READ, FAILED
    Instant timestampSend,
    Instant timestampRead
) {}
```

## PatientTimeline (Record)
```java
public record PatientTimeline(
    PatientId patientId,
    String name,
    String dateOfBirth,
    String clinicalHistory,
    List<LabResult> labs
) {}
```

## Rules
- All domain models are **immutable Java records**.
- **OCC versioning** is mandatory on `LabResult` (`long version`).
- No framework annotations (Quarkus, CDI, Jackson) in `domain` except `@RegisterForReflection(serialization = true)` for GraalVM reflection configuration.
- Dependencies point inward only: `domain` never imports `application` or `infrastructure`.
