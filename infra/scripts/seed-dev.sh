#!/usr/bin/env bash
set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
ENDPOINT="http://localhost:4566"
REGION="us-east-1"
TABLE_NAME="hc-alertas"

# ── Helpers ───────────────────────────────────────────────────────────────────
iso() { date -u -d "$1" +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || echo "$1"; }

put_lab() {
  # Usage: put_lab <patientId> <labId> <testType> <timestamp> <critical> <version> <key1> <val1> [<key2> <val2> ...]
  local patient_id="$1" lab_id="$2" test_type="$3" timestamp="$4" critical="$5" version="$6"
  shift 6
  local pk="PATIENT#${patient_id}"
  local sk="LAB#${lab_id}"

  local av_json="{}"
  while [ $# -gt 0 ]; do
    local k="$1" v="$2"; shift 2
    av_json=$(echo "$av_json" | jq --arg k "$k" --arg v "$v" '. + {($k): $v}')
  done

  local av_str
  av_str=$(echo "$av_json" | jq -c .)

  aws --endpoint-url="$ENDPOINT" --region "$REGION" dynamodb put-item \
    --table-name "$TABLE_NAME" \
    --item "{
      \"PK\": {\"S\": \"$pk\"},
      \"SK\": {\"S\": \"$sk\"},
      \"TestType\": {\"S\": \"$test_type\"},
      \"AnalyticValues\": {\"M\": $av_str},
      \"Timestamp\": {\"S\": \"$timestamp\"},
      \"IsCriticalAlert\": {\"BOOL\": $critical},
      \"S3PathComplete\": {\"S\": \"s3://hc-alertas-clinical-docs-dev-local/labs/$lab_id.pdf\"},
      \"Version\": {\"N\": \"$version\"},
      \"VisibilityStatus\": {\"S\": \"$7\"}
    }" \
    --condition-expression "attribute_not_exists(PK) AND attribute_not_exists(SK)" \
    > /dev/null
}

put_metadata() {
  # Usage: put_metadata <patientId> <name> <dateOfBirth> <clinicalHistory>
  local patient_id="$1" name="$2" dob="$3" history="$4"
  local pk="PATIENT#${patient_id}"

  aws --endpoint-url="$ENDPOINT" --region "$REGION" dynamodb put-item \
    --table-name "$TABLE_NAME" \
    --item "{
      \"PK\": {\"S\": \"$pk\"},
      \"SK\": {\"S\": \"METADATA\"},
      \"Name\": {\"S\": \"$name\"},
      \"DateOfBirth\": {\"S\": \"$dob\"},
      \"ClinicalHistory\": {\"S\": \"$history\"}
    }" \
    > /dev/null
}

# ── Pre-flight ───────────────────────────────────────────────────────────────
echo "=== Waiting for LocalStack ==="
until curl -sf "$ENDPOINT/_localstack/health" | grep -q '"dynamodb": "available"'; do
  sleep 2
done
echo "LocalStack ready."

echo "=== Seeding DynamoDB table '$TABLE_NAME' ==="

# ── Patient HCG12345: Manuel Rodríguez ───────────────────────────────────────
put_metadata "HCG12345" "Manuel Rodríguez" "$(iso '1978-03-15T00:00:00Z')" "Hipertensión arterial · Alergia a Sulfas"

put_lab "HCG12345" "lab-001" "QUIMICA_SANGUINEA" "$(iso '2026-06-22T08:00:00Z')" "false" "1" \
  "glucosa" "90 mg/dL" \
  "sodio" "140 mEq/L" \
  "creatinina" "1.0 mg/dL" \
  "NOTIFIED"

put_lab "HCG12345" "lab-002" "PERFIL_LIPIDOS" "$(iso '2026-06-22T10:30:00Z')" "true" "1" \
  "colesterol_total" "240 mg/dL" \
  "trigliceridos" "180 mg/dL" \
  "cHDL" "38 mg/dL" \
  "READ_CONFIRMED"

put_lab "HCG12345" "lab-003" "BIOMETRIA_HEMATICA" "$(iso '2026-06-23T07:15:00Z')" "false" "1" \
  "hemoglobina" "13.8 g/dL" \
  "plaquetas" "210000 /uL" \
  "leucocitos" "7200 /uL" \
  "PENDING"
echo "  Patient HCG12345: 3 labs seeded."

# ── Patient HCG78901: Ana García ─────────────────────────────────────────────
put_metadata "HCG78901" "Ana García" "$(iso '1992-11-08T00:00:00Z')" "Diabetes Tipo 2 · Embarazo (32 sem)"

put_lab "HCG78901" "lab-101" "BIOMETRIA_HEMATICA" "$(iso '2026-06-22T09:00:00Z')" "true" "1" \
  "plaquetas" "35000 /uL" \
  "hemoglobina" "8.2 g/dL" \
  "leucocitos" "4500 /uL" \
  "READ_CONFIRMED"

put_lab "HCG78901" "lab-102" "QUIMICA_SANGUINEA" "$(iso '2026-06-22T14:00:00Z')" "false" "1" \
  "glucosa" "110 mg/dL" \
  "potasio" "4.2 mEq/L" \
  "creatinina" "0.8 mg/dL" \
  "NOTIFIED"
echo "  Patient HCG78901: 2 labs seeded."

# ── Patient HCG44218: Roberto Méndez ─────────────────────────────────────────
put_metadata "HCG44218" "Roberto Méndez" "$(iso '1965-07-22T00:00:00Z')" "Insuficiencia renal crónica · HTA"

put_lab "HCG44218" "lab-201" "QUIMICA_SANGUINEA" "$(iso '2026-06-23T06:45:00Z')" "true" "1" \
  "glucosa" "410 mg/dL" \
  "potasio" "6.1 mEq/L" \
  "creatinina" "3.2 mg/dL" \
  "NOTIFIED"

put_lab "HCG44218" "lab-202" "BIOMETRIA_HEMATICA" "$(iso '2026-06-23T11:20:00Z')" "true" "1" \
  "hemoglobina" "7.8 g/dL" \
  "plaquetas" "90000 /uL" \
  "leucocitos" "6100 /uL" \
  "READ_CONFIRMED"
echo "  Patient HCG44218: 2 labs seeded."

echo "=== Seeding complete: 3 patients, 7 lab records ==="
