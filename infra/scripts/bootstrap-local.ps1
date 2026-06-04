# PowerShell script to bootstrap LocalStack resources (DynamoDB + S3 + SQS + DLQ) for Windows users.

$ErrorActionPreference = "Stop"

$ENDPOINT = "http://localhost:4566"
$REGION = "us-east-1"
$TABLE_NAME = "hc-alertas"
$BUCKET_NAME = "hc-alertas-clinical-docs-dev-local"
$INGEST_QUEUE = "hc-alertas-ingest-queue"
$DLQ_QUEUE = "hc-alertas-dlq"

Write-Host "=== Configurando Recursos Locales en LocalStack ===" -ForegroundColor Blue

# 1. Esperar a que LocalStack esté listo
Write-Host "Esperando que LocalStack esté listo..." -ForegroundColor Yellow
while ($true) {
    try {
        $health = Invoke-RestMethod -Uri "$ENDPOINT/_localstack/health" -Method Get -ErrorAction SilentlyContinue
        $dynamoReady = $health.services.dynamodb -eq "available" -or $health.services.dynamodb -eq "running"
        $sqsReady = $health.services.sqs -eq "available" -or $health.services.sqs -eq "running"
        if ($dynamoReady -and $sqsReady) {
            break
        }
    } catch {}
    Start-Sleep -Seconds 2
    Write-Host -NoNewline "."
}
Write-Host ""
Write-Host "Servicios de LocalStack listos." -ForegroundColor Green

# 2. Crear Tabla DynamoDB
Write-Host "Creando tabla DynamoDB '$TABLE_NAME'..." -ForegroundColor Yellow
$tableExists = $false
try {
    aws --endpoint-url="$ENDPOINT" dynamodb describe-table --table-name "$TABLE_NAME" --region "$REGION" *> $null
    $tableExists = $true
} catch {}

if ($tableExists) {
    Write-Host "La tabla '$TABLE_NAME' ya existe. Omitiendo..." -ForegroundColor Gray
} else {
    aws --endpoint-url="$ENDPOINT" dynamodb create-table `
      --table-name "$TABLE_NAME" `
      --attribute-definitions `
        AttributeName=PK,AttributeType=S `
        AttributeName=SK,AttributeType=S `
      --key-schema `
        AttributeName=PK,KeyType=HASH `
        AttributeName=SK,KeyType=RANGE `
      --billing-mode PAY_PER_REQUEST `
      --region "$REGION" | Out-Null
    Write-Host "Tabla '$TABLE_NAME' creada exitosamente." -ForegroundColor Green
}

# 3. Crear Bucket S3
Write-Host "Creando Bucket S3 '$BUCKET_NAME'..." -ForegroundColor Yellow
$bucketExists = $false
try {
    aws --endpoint-url="$ENDPOINT" s3 ls "s3://$BUCKET_NAME" --region "$REGION" *> $null
    $bucketExists = $true
} catch {}

if ($bucketExists) {
    Write-Host "El bucket '$BUCKET_NAME' ya existe. Omitiendo..." -ForegroundColor Gray
} else {
    aws --endpoint-url="$ENDPOINT" s3 mb "s3://$BUCKET_NAME" --region "$REGION" | Out-Null
    Write-Host "Bucket '$BUCKET_NAME' creado exitosamente." -ForegroundColor Green
}

# 4. Crear Colas SQS
Write-Host "Creando cola DLQ '$DLQ_QUEUE'..." -ForegroundColor Yellow
$dlqExists = $false
try {
    aws --endpoint-url="$ENDPOINT" sqs get-queue-url --queue-name "$DLQ_QUEUE" --region "$REGION" *> $null
    $dlqExists = $true
} catch {}

if ($dlqExists) {
    Write-Host "La cola DLQ '$DLQ_QUEUE' ya existe. Omitiendo..." -ForegroundColor Gray
} else {
    aws --endpoint-url="$ENDPOINT" sqs create-queue --queue-name "$DLQ_QUEUE" --region "$REGION" | Out-Null
    Write-Host "Cola DLQ '$DLQ_QUEUE' creada exitosamente." -ForegroundColor Green
}

Write-Host "Creando cola de ingesta '$INGEST_QUEUE'..." -ForegroundColor Yellow
$ingestExists = $false
try {
    aws --endpoint-url="$ENDPOINT" sqs get-queue-url --queue-name "$INGEST_QUEUE" --region "$REGION" *> $null
    $ingestExists = $true
} catch {}

if ($ingestExists) {
    Write-Host "La cola de ingesta '$INGEST_QUEUE' ya existe. Omitiendo..." -ForegroundColor Gray
} else {
    $dlqArn = "arn:aws:sqs:$REGION:000000000000:$DLQ_QUEUE"
    $redrivePolicy = '{"deadLetterTargetArn":"' + $dlqArn + '","maxReceiveCount":4}'
    aws --endpoint-url="$ENDPOINT" sqs create-queue `
      --queue-name "$INGEST_QUEUE" `
      --attributes '{"VisibilityTimeout":"60","RedrivePolicy":' + "'" + $redrivePolicy + "'" + '}' `
      --region "$REGION" | Out-Null
    Write-Host "Cola de ingesta '$INGEST_QUEUE' (con DLQ vinculada) creada exitosamente." -ForegroundColor Green
}

Write-Host "=== Inicialización Local Completada ===" -ForegroundColor Green
Write-Host "Para correr la aplicación Quarkus apuntando a LocalStack ejecute:" -ForegroundColor Cyan
Write-Host "`$env:QUARKUS_DYNAMODB_ENDPOINT_OVERRIDE='$ENDPOINT'; `$env:AWS_S3_ENDPOINT_OVERRIDE='$ENDPOINT'; mvn quarkus:dev" -ForegroundColor White
