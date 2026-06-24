#!/usr/bin/env bash

# Exit immediately if a command exits with a non-zero status
set -e


LOCALSTACK_URL="http://localhost:4566"
AWS_REGION="us-east-1"


# Helper for AWS CLI calls targeting LocalStack
alias local-aws="aws --endpoint-url=${LOCALSTACK_URL} --region ${AWS_REGION}"


echo "====================================================="
echo "🚀 Starting Alertas-Civil Local Setup & Seeding"
echo "====================================================="


# ---------------------------------------------------------
# 1. Infrastructure Provisioning
# ---------------------------------------------------------


echo "📥 Creating SQS Queues..."


# Ingestion Queue
local-aws sqs create-queue \
  --queue-name hcg-lab-ingestion-queue \
  --attributes VisibilityTimeout=30 \
  > /dev/null


# Critical Alerts Queue (Isolated to prevent Head-of-Line blocking)
local-aws sqs create-queue \
  --queue-name hcg-critical-alerts-queue \
  --attributes VisibilityTimeout=30 \
  > /dev/null


echo "✅ SQS Queues created."


echo "🗄️ Creating DynamoDB Table..."
# NOTE: Adjust the table name and partition/sort key attributes 
# to match your DynamoDbPersistenceAdapter configuration exactly.
local-aws dynamodb create-table \
  --table-name lab-results-table \
  --attribute-definitions \
      AttributeName=PK,AttributeType=S \
      AttributeName=SK,AttributeType=S \
  --key-schema \
      AttributeName=PK,KeyType=HASH \
      AttributeName=SK,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  > /dev/null


echo "✅ DynamoDB table created."


# Wait a brief moment for infra lines to settle
sleep 2


# Get Ingestion Queue URL for messaging
QUEUE_URL=$(local-aws sqs get-queue-url --queue-name hcg-lab-ingestion-queue --query "QueueUrl" --output text)


# ---------------------------------------------------------
# 2. Multi-Scenario Data Seeding
# ---------------------------------------------------------
echo "🌾 Seeding test payloads into ingestion path..."


# SCENARIO A: The Happy Path (Standardized analytes, normal values)
PATIENT_A="PATIENT-9921"
LAB_A="LAB-fa7b6cde-1"
PAYLOAD_A=$(cat <<EOF
{
  "labId": "${LAB_A}",
  "patientId": "${PATIENT_A}",
  "testType": "QUIMICA_SANGUINEA",
  "analyticValues": {
    "glucosa": "90",
    "sodio": "140"
  },
  "timestamp": "2026-06-22T10:00:00Z",
  "criticalAlert": false,
  "s3Path": "none"
}
EOF
)


local-aws sqs send-message --queue-url "${QUEUE_URL}" --message-body "${PAYLOAD_A}" > /dev/null
echo "  -> Seeded Scenario A (Normal): Patient ${PATIENT_A}"




# SCENARIO B: The Critical Threshold (Triggers notification use case)
PATIENT_B="PATIENT-4412"
LAB_B="LAB-bc8e21a4-2"
PAYLOAD_B=$(cat <<EOF
{
  "labId": "${LAB_B}",
  "patientId": "${PATIENT_B}",
  "testType": "BIOMETRIA_HEMATICA",
  "analyticValues": {
    "plaquetas": "35000",
    "hemoglobina": "8.2"
  },
  "timestamp": "2026-06-22T10:05:00Z",
  "criticalAlert": true,
  "s3Path": "none"
}
EOF
)


local-aws sqs send-message --queue-url "${QUEUE_URL}" --message-body "${PAYLOAD_B}" > /dev/null
echo "  -> Seeded Scenario B (CRITICAL): Patient ${PATIENT_B}"




# SCENARIO C: The Chaos Test (Messy casing, unexpected units to test parser/logs)
PATIENT_C="PATIENT-7789"
LAB_C="LAB-de319fbc-3"
PAYLOAD_C=$(cat <<EOF
{
  "labId": "${LAB_C}",
  "patientId": "${PATIENT_C}",
  "testType": "QUIMICA_SANGUINEA",
  "analyticValues": {
    "GLUCOSA": "410 mg/dL",
    "POTASIO": "6.1 mEq/L",
    "unknown_analyte_xyz": "99.9"
  },
  "timestamp": "2026-06-22T10:10:00Z",
  "criticalAlert": true,
  "s3Path": "none"
}
EOF
)


local-aws sqs send-message --queue-url "${QUEUE_URL}" --message-body "${PAYLOAD_C}" > /dev/null
echo "  -> Seeded Scenario C (Chaos/Dirty Data): Patient ${PATIENT_C}"


# ---------------------------------------------------------
# 3. Execution Verification Hooks
# ---------------------------------------------------------
echo "====================================================="
echo "🎉 Bootstrap Complete. Verify Read APIs via Quarkus Dev Server:"
echo "====================================================="
echo "1. Start your backend server if it isn't running: mvn quarkus:dev"
echo ""
echo "2. Check Timeline for Scenario A (Normal Data):"
echo "   curl http://localhost:8081/api/v1/patients/${PATIENT_A}/labs/history"
echo ""
echo "3. Check Latest for Scenario B (Critical Data):"
echo "   curl http://localhost:8081/api/v1/patients/${PATIENT_B}/labs/latest"
echo ""
echo "4. Check Logs for Scenario C Warning Filters (Unknown Analytes):"
echo "   curl http://localhost:8081/api/v1/patients/${PATIENT_C}/labs/history"
echo "====================================================="
