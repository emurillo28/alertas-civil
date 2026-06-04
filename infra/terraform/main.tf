provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Environment = var.environment
      Project     = "AlertasHospitalCivil"
      ManagedBy   = "Terraform"
    }
  }
}

# ─── DYNAMODB SINGLE-TABLE DESIGN ────────────────────────────────────────────
resource "aws_dynamodb_table" "hc_alertas" {
  name         = var.dynamodb_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

  attribute {
    name = "PK"
    type = "S"
  }

  attribute {
    name = "SK"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  # Control de eliminación accidental en producción
  deletion_protection_enabled = var.environment == "prod" ? true : false

  tags = {
    Name = var.dynamodb_table_name
  }
}

# ─── S3 BUCKET PARA DOCUMENTOS CLÍNICOS (PDFs) ────────────────────────────────
resource "aws_s3_bucket" "clinical_docs" {
  bucket        = "${var.s3_bucket_prefix}-${var.environment}-${data.aws_caller_identity.current.account_id}"
  force_destroy = var.environment == "prod" ? false : true
}

# Bloquear todo el acceso público por defecto (Seguridad de datos clínicos / HIPAA)
resource "aws_s3_bucket_public_access_block" "clinical_docs_block" {
  bucket = aws_s3_bucket.clinical_docs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Habilitar versionado para recuperar PDFs en caso de sobreescritura accidental
resource "aws_s3_bucket_versioning" "clinical_docs_versioning" {
  bucket = aws_s3_bucket.clinical_docs.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Encriptación del lado del servidor (SSE-S3)
resource "aws_s3_bucket_server_side_encryption_configuration" "clinical_docs_encryption" {
  bucket = aws_s3_bucket.clinical_docs.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# ─── QUEUES SQS Y DLQ NATIVA ──────────────────────────────────────────────────
# Cola de Descarte (Dead Letter Queue) para mensajes fallidos tras agotar reintentos
resource "aws_sqs_queue" "dlq_queue" {
  name                      = var.sqs_dlq_name
  message_retention_seconds = 1209600 # Retiene mensajes por 14 días para análisis forense
  sqs_managed_sse_enabled   = true    # Encriptación del lado del servidor activa
}

# Cola de Ingesta Principal
resource "aws_sqs_queue" "ingest_queue" {
  name                      = var.sqs_ingest_name
  message_retention_seconds = 86400  # Retiene mensajes por 1 día
  visibility_timeout_seconds = 60    # Suficiente tiempo para que la Lambda procese el lote
  sqs_managed_sse_enabled   = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq_queue.arn
    maxReceiveCount     = 4 # Reintenta hasta 4 veces antes de mover a la DLQ
  })
}

# ─── ROL IAM PARA LA FUNCIÓN LAMBDA (QUARKUS) ──────────────────────────────────
resource "aws_iam_role" "lambda_execution_role" {
  name = "hc-alertas-lambda-role-${var.environment}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "lambda.amazonaws.com"
        }
      }
    ]
  })
}

# Política básica de ejecución de Lambda (CloudWatch Logs)
resource "aws_iam_role_policy_attachment" "lambda_basic_execution" {
  role       = aws_iam_role.lambda_execution_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# Política en línea para permisos específicos de DynamoDB, S3 y SQS
resource "aws_iam_policy" "lambda_custom_policy" {
  name        = "hc-alertas-lambda-policy-${var.environment}"
  description = "Permisos necesarios para el backend de Alertas Médicas (DynamoDB, S3 y SQS)"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # DynamoDB: Permisos sobre la Single-Table
      {
        Effect = "Allow"
        Action = [
          "dynamodb:PutItem",
          "dynamodb:GetItem",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem",
          "dynamodb:Query",
          "dynamodb:Scan"
        ]
        Resource = [
          aws_dynamodb_table.hc_alertas.arn,
          "${aws_dynamodb_table.hc_alertas.arn}/index/*"
        ]
      },
      # S3: Permisos sobre el bucket de PDFs clínicos
      {
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:DeleteObject",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.clinical_docs.arn,
          "${aws_s3_bucket.clinical_docs.arn}/*"
        ]
      },
      # SQS: Permisos para consumir de la cola de ingesta
      {
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes"
        ]
        Resource = aws_sqs_queue.ingest_queue.arn
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "lambda_custom" {
  role       = aws_iam_role.lambda_execution_role.name
  policy_arn = aws_iam_policy.lambda_custom_policy.arn
}

# ─── INFRAESTRUCTURA DE DEPLOYMENT: AWS LAMBDA + GRAALVM NATIVE IMAGE ─────────
resource "aws_lambda_function" "quarkus_lambda" {
  function_name = "hc-alertas-backend-${var.environment}"
  role          = aws_iam_role.lambda_execution_role.arn
  
  # Usar custom.al2023 para binarios nativos auto-contenidos (GraalVM)
  runtime       = "provided.al2023"
  handler       = "ingestLabSqsHandler"
  
  filename         = var.lambda_zip_path != "" ? var.lambda_zip_path : "${path.module}/dummy-lambda.zip"
  source_code_hash = var.lambda_zip_path != "" ? filebase64sha256(var.lambda_zip_path) : null

  memory_size = var.lambda_memory
  timeout     = var.lambda_timeout

  environment {
    variables = {
      QUARKUS_PROFILE          = var.environment
      APP_DYNAMODB_TABLE_NAME  = aws_dynamodb_table.hc_alertas.name
      AWS_S3_BUCKET_NAME       = aws_s3_bucket.clinical_docs.id
    }
  }

  lifecycle {
    ignore_changes = [filename, source_code_hash]
  }
}

# Event Source Mapping para gatillar Lambda desde SQS
resource "aws_lambda_event_source_mapping" "sqs_mapping" {
  event_source_arn = aws_sqs_queue.ingest_queue.arn
  function_name    = aws_lambda_function.quarkus_lambda.arn
  batch_size       = 10 # Procesa lotes de hasta 10 mensajes concurrentes
  enabled          = true
}

# ─── API GATEWAY (HTTP API - V2) CON INTEGRACIÓN SQS DIRECTA ──────────────────
resource "aws_apigatewayv2_api" "http_api" {
  name          = "hc-alertas-api-${var.environment}"
  protocol_type = "HTTP"
  description   = "API Gateway de Alertas Médicas con integración directa a SQS"
}

resource "aws_apigatewayv2_stage" "api_stage" {
  api_id      = aws_apigatewayv2_api.http_api.id
  name        = "$default"
  auto_deploy = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api_gw_logs.arn
    format          = jsonencode({
      requestId      = "$context.requestId"
      ip             = "$context.identity.sourceIp"
      requestTime    = "$context.requestTime"
      httpMethod     = "$context.httpMethod"
      routeKey       = "$context.routeKey"
      status         = "$context.status"
      protocol       = "$context.protocol"
      responseLength = "$context.responseLength"
      latency        = "$context.integrationLatency"
    })
  }
}

# Rol IAM para que API Gateway pueda escribir en SQS
resource "aws_iam_role" "api_gateway_role" {
  name = "hc-alertas-apigw-role-${var.environment}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "apigateway.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_policy" "apigw_sqs_policy" {
  name = "hc-alertas-apigw-sqs-policy-${var.environment}"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "sqs:SendMessage"
        Resource = aws_sqs_queue.ingest_queue.arn
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "apigw_sqs_attachment" {
  role       = aws_iam_role.api_gateway_role.name
  policy_arn = aws_iam_policy.apigw_sqs_policy.arn
}

# Integración nativa API Gateway → SQS SendMessage
resource "aws_apigatewayv2_integration" "sqs_integration" {
  api_id             = aws_apigatewayv2_api.http_api.id
  integration_type   = "AWS_PROXY"
  integration_subtype = "SQS-SendMessage"
  credentials_arn    = aws_iam_role.api_gateway_role.arn

  parameter_mapping = {
    "QueueUrl"    = aws_sqs_queue.ingest_queue.url
    "MessageBody" = "$request.body"
  }
}

# Ruta que mapea POST /api/v1/labs a SQS
resource "aws_apigatewayv2_route" "ingest_route" {
  api_id    = aws_apigatewayv2_api.http_api.id
  route_key = "POST /api/v1/labs"
  target    = "integrations/${aws_apigatewayv2_integration.sqs_integration.id}"
}

# ─── CLOUDWATCH LOG GROUPS ────────────────────────────────────────────────────
resource "aws_cloudwatch_log_group" "api_gw_logs" {
  name              = "/aws/apigateway/hc-alertas-api-${var.environment}"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "lambda_logs" {
  name              = "/aws/lambda/${aws_lambda_function.quarkus_lambda.function_name}"
  retention_in_days = var.log_retention_days
}

# ─── DATA SOURCES ─────────────────────────────────────────────────────────────
data "aws_caller_identity" "current" {}
