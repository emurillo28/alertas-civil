# HCG Database Schema (DynamoDB Single Table)

## Table
- **Config Property**: `app.dynamodb.table-name` (Default: `hc-alertas`)
- **Partition Key**: `PK` (String)
- **Sort Key**: `SK` (String)

## Key Structure

### Patient Profile Metadata
```
PK: PATIENT#{patientId}
SK: METADATA
```
- **Attributes**: `Name`, `DateOfBirth`, `ClinicalHistory`

### LabResult Item
```
PK: PATIENT#{patientId}
SK: LAB#{labId}
```
- **Attributes**:
  - `TestType` (String)
  - `AnalyticValues` (Map<String, String>)
  - `Timestamp` (String ISO-8601)
  - `IsCriticalAlert` (Boolean)
  - `S3PathComplete` (String)
  - `ClinicalSummary` (String, nullable)
  - `Version` (Number, OCC counter)
  - `VisibilityStatus` (String: `PENDING` | `NOTIFIED` | `READ_CONFIRMED`)
  - `SentNotifications` (List of Maps: `NotificationId`, `Recipient`, `Channel`, `Status`, `TimestampSend`, `TimestampRead`)

---

## Secondary Indexes (Planned / Optional GSI)

| Index | PK | SK | Purpose |
|-------|-----|-----|---------|
| GSI1 | `STATUS#{visibilityStatus}` | `TIMESTAMP#{timestamp}` | Query pending or critical alerts across patients |

---

## Access Patterns

| Query / Operation | Access Specification | Details |
|-------------------|----------------------|---------|
| Get Patient Timeline & Metadata | `Query` PK=`PATIENT#{patientId}` | 1 network round-trip retrieves `METADATA` and all `LAB#` records |
| Get Latest Lab for Patient | `Query` PK=`PATIENT#{patientId}`, SK begins_with `LAB#` | Evaluated in-memory by latest timestamp or sorted SK |
| Write / Update LabResult (OCC) | `PutItem` / `UpdateItem` with `ConditionExpression` on `Version` | Version 0 uses `attribute_not_exists(PK) AND attribute_not_exists(SK)`; updates assert `#v = :expected` |
| Register Notification / Update Status | Atomic `UpdateItem` on `LAB#{labId}` | Appends to `SentNotifications` list, updates `VisibilityStatus`, increments `Version` |
