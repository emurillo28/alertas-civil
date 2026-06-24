// API Contract Types for hcg-v1 UI
// Generated from API_CONTRACT.md - DO NOT EDIT MANUALLY
// Run: npm run generate:types (if you have openapi-typescript-codegen)

/** UUID v4 string */
export type UUID = string & { readonly _brand: unique symbol };

/** PatientId: alphanumeric, 1-64 chars */
export type PatientId = string & { readonly _brand: unique symbol };

/** ISO8601 timestamp string */
export type ISO8601 = string & { readonly _brand: unique symbol };

/** S3 URI */
export type S3Uri = string & { readonly _brand: unique symbol };

// ─── Ingestion Request ────────────────────────────────────────────────────────

export interface LabResultIngestRequest {
  /** UUID v4 - auto-generated if omitted */
  labId?: UUID;
  /** Required: alphanumeric 1-64 chars */
  patientId: PatientId;
  /** Required: display name of test panel */
  testType: string;
  /** Required: at least one analyte. Key = display name, Value = "value unit" */
  analyticValues: Record<string, string>;
  /** Required: specimen collection time (ISO8601) */
  timestamp: ISO8601;
  /** Optional: backend evaluates from thresholds if omitted/default false */
  criticalAlert?: boolean;
  /** Required: S3 path to PDF report */
  s3Path: S3Uri;
}

export interface LabResultIngestResponse {
  labId: UUID;
  status: 'ACCEPTED';
  message: string;
}

// ─── UI View Models (for query endpoints) ────────────────────────────────────

export interface AnalyticValueView {
  name: string;
  value: string;
  critical: boolean;
}

export type VisibilityStatus = 'PENDING' | 'NOTIFIED' | 'READ_CONFIRMED';

export type NotificationChannel = 'SMS' | 'WHATSAPP' | 'EMAIL' | 'PUSH' | 'IN_APP';

export type NotificationStatus = 'PENDING' | 'SENT' | 'READ' | 'FAILED';

export interface SentNotificationView {
  notificationId: string;
  recipient: string;
  channel: NotificationChannel;
  status: NotificationStatus;
  timestampSend: ISO8601;
  timestampRead: ISO8601 | null;
}

export interface LabResultView {
  labId: UUID;
  patientId: PatientId;
  testType: string;
  analyticValues: AnalyticValueView[];
  timestamp: ISO8601;
  criticalAlert: boolean;
  clinicalSummary: string | null;
  visibilityStatus: VisibilityStatus;
  version: number;
  notifications: SentNotificationView[];
}

// ─── Notification/Visibility Actions ──────────────────────────────────────────

export interface NotifyLabRequest {
  recipient: string;
  channel: NotificationChannel;
}

export interface ReadLabRequest {
  clinicianId: string;
}

// ─── Error Types ──────────────────────────────────────────────────────────────

export interface ApiError {
  code: string;
  message: string;
  field?: string;
  expectedVersion?: number;
  actualVersion?: number;
  retryAfterMs?: number;
}

export const ErrorCodes = {
  INVALID_INPUT: 'INVALID_INPUT',
  NOT_FOUND: 'NOT_FOUND',
  OCC_CONFLICT: 'OCC_CONFLICT',
  THROTTLED: 'THROTTLED',
} as const;

// ─── Environment Config ───────────────────────────────────────────────────────

export interface ApiConfig {
  baseUrl: string;
  timeoutMs: number;
}

export const ENV_CONFIG: Record<'local' | 'dev' | 'prod', ApiConfig> = {
  local: { baseUrl: 'http://localhost:8080', timeoutMs: 10000 },
  dev:   { baseUrl: 'https://dev-api.hcg.local', timeoutMs: 15000 },
  prod:  { baseUrl: 'https://api.hcg.gob.mx', timeoutMs: 15000 },
};

// ─── Helper: Build Ingestion Payload ──────────────────────────────────────────

export function buildIngestPayload(params: {
  patientId: string;
  testType: string;
  analytes: Record<string, { value: number; unit: string }>;
  timestamp: Date;
  s3Path: string;
  labId?: string;
}): LabResultIngestRequest {
  const analyticValues: Record<string, string> = {};
  for (const [name, { value, unit }] of Object.entries(params.analytes)) {
    analyticValues[name] = `${value} ${unit}`;
  }
  return {
    labId: params.labId as UUID | undefined,
    patientId: params.patientId as PatientId,
    testType: params.testType,
    analyticValues,
    timestamp: params.timestamp.toISOString() as ISO8601,
    s3Path: params.s3Path as S3Uri,
  };
}

// ─── Clinical Thresholds (for UI-side preview) ────────────────────────────────

export interface ThresholdRule {
  analyteKeys: string[];      // case-insensitive keys that match
  criticalLow?: number;
  criticalHigh?: number;
  unit: string;
  action: string;
}

export const CLINICAL_THRESHOLDS: ThresholdRule[] = [
  { analyteKeys: ['hemoglobina', 'hemoglobin'], criticalLow: 7.0, criticalHigh: 20.0, unit: 'g/dL', action: 'Immediate physician notification' },
  { analyteKeys: ['potasio', 'potassium'], criticalLow: 2.5, criticalHigh: 6.0, unit: 'mmol/L', action: 'Cardiac monitoring, STAT callback' },
  { analyteKeys: ['glucosa', 'glucose'], criticalLow: 50.0, criticalHigh: 400.0, unit: 'mg/dL', action: 'Insulin/Dextrose protocol activation' },
  { analyteKeys: ['sodio', 'sodium'], criticalLow: 120.0, criticalHigh: 160.0, unit: 'mmol/L', action: 'Neurology consult, fluid management' },
  { analyteKeys: ['creatinina', 'creatinine'], criticalHigh: 10.0, unit: 'mg/dL', action: 'Nephrology alert, contrast hold' },
  { analyteKeys: ['plaquetas', 'platelets'], criticalLow: 50.0, criticalHigh: 1000.0, unit: '×10³/µL', action: 'Hematology consult, transfusion eval' },
];

/** Check if a single analyte value triggers critical alert */
export function checkCritical(analyteName: string, valueStr: string): boolean {
  const rule = CLINICAL_THRESHOLDS.find(r =>
    r.analyteKeys.some(k => k.toLowerCase() === analyteName.toLowerCase().trim())
  );
  if (!rule) return false;

  // Extract numeric prefix: "14.5 g/dL" → 14.5
  const match = valueStr.trim().match(/^(-?\d+(?:\.\d+)?)/);
  if (!match) return false;
  const value = parseFloat(match[1]);

  if (rule.criticalLow !== undefined && value < rule.criticalLow) return true;
  if (rule.criticalHigh !== undefined && value > rule.criticalHigh) return true;
  return false;
}

/** Evaluate all analytes - mirrors backend logic */
export function evaluateCriticalAlerts(analyticValues: Record<string, string>): boolean {
  return Object.entries(analyticValues).some(([name, valueStr]) =>
    checkCritical(name, valueStr)
  );
}