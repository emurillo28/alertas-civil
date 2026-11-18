#!/usr/bin/env bash
set -euo pipefail

# Script para inicializar recursos (DynamoDB, S3, SQS y DLQ) en LocalStack local.
# Útil para entorno de desarrollo y pruebas de integración locales.

ENDPOINT="http://localhost:4566"
REGION="us-east-1"
TABLE_NAME="hc-alertas"
BUCKET_NAME="hc-alertas-clinical-docs-dev-local"
INGEST_QUEUE="hc-alertas-ingest-queue"
DLQ_QUEUE="hc-alertas-dlq"

echo "=== Configurando Recursos Locales en LocalStack ==="

# 1. Esperar a que LocalStack esté listo
echo "Esperando que LocalStack esté listo..."
until curl -s "$ENDPOINT/_localstack/health" | grep -q '"dynamodb": "available"' && curl -s "$ENDPOINT/_localstack/health" | grep -q '"sqs": "available"'; do
  sleep 2
  echo -n "."
done
echo -e "\nServicios de LocalStack listos."

# 2. Crear Tabla DynamoDB Single-Table
echo "Creando tabla DynamoDB '$TABLE_NAME'..."
if aws --endpoint-url="$ENDPOINT" dynamodb describe-table --table-name "$TABLE_NAME" --region "$REGION" &>/dev/null; then
  echo "La tabla '$TABLE_NAME' ya existe. Omitiendo..."
else
  aws --endpoint-url="$ENDPOINT" dynamodb create-table \
    --table-name "$TABLE_NAME" \
    --attribute-definitions \
      AttributeName=PK,AttributeType=S \
      AttributeName=SK,AttributeType=S \
    --key-schema \
      AttributeName=PK,KeyType=HASH \
      AttributeName=SK,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST \
    --region "$REGION" > /dev/null
  echo "Tabla '$TABLE_NAME' creada exitosamente."
fi

# 3. Crear Bucket S3
echo "Creando Bucket S3 '$BUCKET_NAME'..."
if aws --endpoint-url="$ENDPOINT" s3 ls "s3://$BUCKET_NAME" --region "$REGION" &>/dev/null; then
  echo "El bucket '$BUCKET_NAME' ya existe. Omitiendo..."
else
  aws --endpoint-url="$ENDPOINT" s3 mb "s3://$BUCKET_NAME" --region "$REGION" > /dev/null
  echo "Bucket '$BUCKET_NAME' creado exitosamente."
fi

# 4. Crear Colas SQS
echo "Creando cola DLQ '$DLQ_QUEUE'..."
if aws --endpoint-url="$ENDPOINT" sqs get-queue-url --queue-name "$DLQ_QUEUE" --region "$REGION" &>/dev/null; then
  echo "La cola DLQ '$DLQ_QUEUE' ya existe. Omitiendo..."
else
  aws --endpoint-url="$ENDPOINT" sqs create-queue --queue-name "$DLQ_QUEUE" --region "$REGION" > /dev/null
  echo "Cola DLQ '$DLQ_QUEUE' creada exitosamente."
fi

echo "Creando cola de ingesta '$INGEST_QUEUE'..."
if aws --endpoint-url="$ENDPOINT" sqs get-queue-url --queue-name "$INGEST_QUEUE" --region "$REGION" &>/dev/null; then
  echo "La cola de ingesta '$INGEST_QUEUE' ya existe. Omitiendo..."
else
  DLQ_ARN="arn:aws:sqs:$REGION:000000000000:$DLQ_QUEUE"
  ATTRIBUTES_JSON="{\"VisibilityTimeout\":\"60\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":4}\"}"
  aws --endpoint-url="$ENDPOINT" sqs create-queue \
    --queue-name "$INGEST_QUEUE" \
    --attributes "$ATTRIBUTES_JSON" \
    --region "$REGION" > /dev/null
  echo "Cola de ingesta '$INGEST_QUEUE' (con DLQ vinculada) creada exitosamente."
fi

echo "=== Inicialización Local Completada ==="
echo "Para correr la aplicación Quarkus apuntando a Localstack:"
echo "QUARKUS_DYNAMODB_ENDPOINT_OVERRIDE=$ENDPOINT AWS_S3_ENDPOINT_OVERRIDE=$ENDPOINT mvn quarkus:dev"
