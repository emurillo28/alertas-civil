terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

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

# ─── DATA SOURCES ─────────────────────────────────────────────────────────────
data "aws_caller_identity" "current" {}

# ─── DYNAMODB SINGLE-TABLE DESIGN ─────────────────────────────────────────────
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

resource "aws_s3_bucket_public_access_block" "clinical_docs_block" {
  bucket = aws_s3_bucket.clinical_docs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "clinical_docs_versioning" {
  bucket = aws_s3_bucket.clinical_docs.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "clinical_docs_encryption" {
  bucket = aws_s3_bucket.clinical_docs.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# ─── SQS: DLQ + COLA DE INGESTA + COLA DE NOTIFICACIONES + DLQ NOTIFICACIONES ─
resource "aws_sqs_queue" "dlq_ingest_queue" {
  name                      = var.sqs_dlq_name
  message_retention_seconds = 1209600 # 14 días
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "ingest_queue" {
  name                      = var.sqs_ingest_name
  message_retention_seconds = 86400  # 1 día
  visibility_timeout_seconds = 60    # Suficiente para procesar el lote
  sqs_managed_sse_enabled   = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq_ingest_queue.arn
    maxReceiveCount     = 4
  })
}

resource "aws_sqs_queue" "notification_dlq_queue" {
  name                      = "hc-alertas-notification-dlq"
  message_retention_seconds = 1209600 # 14 días
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "notification_queue" {
  name                      = "hc-alertas-notification-queue"
  message_retention_seconds = 1209600 # 14 días
  sqs_managed_sse_enabled   = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.notification_dlq_queue.arn
    maxReceiveCount     = 4
  })
}

# ══════════════════════════════════════════════════════════════════════════════
# IAM: ROLES LEAST-PRIVILEGE SEPARADOS (HTTP vs SQS Worker)
# ══════════════════════════════════════════════════════════════════════════════

# ─── ROL HTTP (SOLO LECTURA) ──────────────────────────────────────────────────
resource "aws_iam_role" "lambda_http_role" {
  name = "hc-alertas-lambda-http-role-${var.environment}"

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

resource "aws_iam_role_policy_attachment" "http_basic_execution" {
  role       = aws_iam_role.lambda_http_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_policy" "http_policy" {
  name        = "hc-alertas-lambda-http-policy-${var.environment}"
  description = "Permisos de SOLO LECTURA para la Lambda HTTP (API Gateway)"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:Query",
        ]
        Resource = [
          aws_dynamodb_table.hc_alertas.arn,
          "${aws_dynamodb_table.hc_alertas.arn}/index/*",
        ]
      },
      {
        Effect = "Allow"
        Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = aws_secretsmanager_secret.app_config.arn
      },
      {
        Effect = "Allow"
        Action   = ["kms:Decrypt", "kms:DescribeKey"]
        Resource = aws_kms_key.app_secrets.arn
      },
      {
        Effect = "Allow"
        Action   = ["sqs:SendMessage"]
        Resource = aws_sqs_queue.notification_queue.arn
      },
    ]
  })
}

resource "aws_iam_role_policy_attachment" "http_custom" {
  role       = aws_iam_role.lambda_http_role.name
  policy_arn = aws_iam_policy.http_policy.arn
}

# ─── ROL SQS WORKER (ESCRITURA + CONSUMO SQS) ────────────────────────────────
resource "aws_iam_role" "lambda_sqs_role" {
  name = "hc-alertas-lambda-sqs-role-${var.environment}"

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

resource "aws_iam_role_policy_attachment" "sqs_basic_execution" {
  role       = aws_iam_role.lambda_sqs_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_policy" "sqs_policy" {
  name        = "hc-alertas-lambda-sqs-policy-${var.environment}"
  description = "Permisos de ESCRITURA y consumo SQS para la Lambda Worker"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:Query",
        ]
        Resource = [
          aws_dynamodb_table.hc_alertas.arn,
          "${aws_dynamodb_table.hc_alertas.arn}/index/*",
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
        ]
        Resource = aws_sqs_queue.ingest_queue.arn
      },
      {
        Effect = "Allow"
        Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = aws_secretsmanager_secret.app_config.arn
      },
      {
        Effect = "Allow"
        Action   = ["kms:Decrypt", "kms:DescribeKey"]
        Resource = aws_kms_key.app_secrets.arn
      },
    ]
  })
}

resource "aws_iam_role_policy_attachment" "sqs_custom" {
  role       = aws_iam_role.lambda_sqs_role.name
  policy_arn = aws_iam_policy.sqs_policy.arn
}

# ══════════════════════════════════════════════════════════════════════════════
# LAMBDAS: MISMO BINARIO, HANDLER ÚNICO, ROLES DIFERENTES
# Ambas usan: handler = "io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler"
# La diferenciación se hace mediante la variable de entorno QUARKUS_LAMBDA_HANDLER:
#   - HTTP: sin QUARKUS_LAMBDA_HANDLER → Quarkus despacha a Vert.x/JAX-RS
#   - SQS:  QUARKUS_LAMBDA_HANDLER=ingestLabSqsHandler → despacha a ese bean CDI
# ══════════════════════════════════════════════════════════════════════════════

# ─── LAMBDA HTTP (API Gateway → REST JAX-RS) ──────────────────────────────────
resource "aws_lambda_function" "http_lambda" {
  function_name = "hcg-alertas-backend-http-${var.environment}"
  role          = aws_iam_role.lambda_http_role.arn
  runtime       = "provided.al2023"
  handler       = "io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler"

  filename         = var.lambda_zip_path != "" ? var.lambda_zip_path : "${path.module}/dummy-lambda.zip"
  source_code_hash = var.lambda_zip_path != "" ? filebase64sha256(var.lambda_zip_path) : null

  memory_size = var.lambda_memory
  timeout     = var.lambda_timeout

  environment {
    variables = {
      QUARKUS_PROFILE          = var.environment
      APP_DYNAMODB_TABLE_NAME  = aws_dynamodb_table.hc_alertas.name
      AWS_S3_BUCKET_NAME       = aws_s3_bucket.clinical_docs.id
      "app.sqs.notification-queue-url" = aws_sqs_queue.notification_queue.url
      # Sin QUARKUS_LAMBDA_HANDLER → Quarkus usa el router HTTP por defecto
    }
  }

  lifecycle {
    ignore_changes = [filename, source_code_hash]
  }
}

# ─── LAMBDA WORKER (SQS → ingestLabSqsHandler) ────────────────────────────────
resource "aws_lambda_function" "sqs_lambda" {
  function_name = "hcg-alertas-backend-worker-${var.environment}"
  role          = aws_iam_role.lambda_sqs_role.arn
  runtime       = "provided.al2023"
  handler       = "io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler"

  filename         = var.lambda_zip_path != "" ? var.lambda_zip_path : "${path.module}/dummy-lambda.zip"
  source_code_hash = var.lambda_zip_path != "" ? filebase64sha256(var.lambda_zip_path) : null

  memory_size = var.lambda_memory
  timeout     = var.lambda_timeout

  environment {
    variables = {
      QUARKUS_PROFILE          = var.environment
      QUARKUS_LAMBDA_HANDLER   = "ingestLabSqsHandler"
      APP_DYNAMODB_TABLE_NAME  = aws_dynamodb_table.hc_alertas.name
      AWS_S3_BUCKET_NAME       = aws_s3_bucket.clinical_docs.id
    }
  }

  lifecycle {
    ignore_changes = [filename, source_code_hash]
  }
}

# ─── CLOUDWATCH LOG GROUPS SEPARADOS ──────────────────────────────────────────
resource "aws_cloudwatch_log_group" "http_lambda_logs" {
  name              = "/aws/lambda/${aws_lambda_function.http_lambda.function_name}"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "sqs_lambda_logs" {
  name              = "/aws/lambda/${aws_lambda_function.sqs_lambda.function_name}"
  retention_in_days = var.log_retention_days
}

# ─── PERMISOS: API Gateway invoca solo la Lambda HTTP ─────────────────────────
resource "aws_lambda_permission" "http_api_gateway_invoke" {
  statement_id  = "AllowAPIGatewayInvokeHTTP"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.http_lambda.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.http_api.execution_arn}/*/*"
}

# ─── EVENT SOURCE MAPPING: SQS → Lambda Worker ────────────────────────────────
resource "aws_lambda_event_source_mapping" "sqs_mapping" {
  event_source_arn = aws_sqs_queue.ingest_queue.arn
  function_name    = aws_lambda_function.sqs_lambda.arn
  batch_size       = 10
  enabled          = true
}

# ══════════════════════════════════════════════════════════════════════════════
# API GATEWAY (HTTP API v2) + INTEGRACIONES
# ══════════════════════════════════════════════════════════════════════════════
resource "aws_apigatewayv2_api" "http_api" {
  name          = "hc-alertas-api-${var.environment}"
  protocol_type = "HTTP"
  description   = "API Gateway de Alertas Médicas"
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

resource "aws_cloudwatch_log_group" "api_gw_logs" {
  name              = "/aws/apigateway/hc-alertas-api-${var.environment}"
  retention_in_days = var.log_retention_days
}

# ─── INTEGRACIÓN: API Gateway → Lambda HTTP (proxy v2.0) ─────────────────────
resource "aws_apigatewayv2_integration" "lambda_read_integration" {
  api_id                 = aws_apigatewayv2_api.http_api.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.http_lambda.invoke_arn
  payload_format_version = "2.0"
}

# ─── RUTAS DE LECTURA (apuntan a Lambda HTTP) ─────────────────────────────────
resource "aws_apigatewayv2_route" "get_patients" {
  api_id    = aws_apigatewayv2_api.http_api.id
  route_key = "GET /labs/patients"
  target    = "integrations/${aws_apigatewayv2_integration.lambda_read_integration.id}"
}

resource "aws_apigatewayv2_route" "get_patient_latest" {
  api_id    = aws_apigatewayv2_api.http_api.id
  route_key = "GET /labs/patients/{patientId}/latest"
  target    = "integrations/${aws_apigatewayv2_integration.lambda_read_integration.id}"
}

resource "aws_apigatewayv2_route" "get_patient_history" {
  api_id    = aws_apigatewayv2_api.http_api.id
  route_key = "GET /labs/patients/{patientId}/history"
  target    = "integrations/${aws_apigatewayv2_integration.lambda_read_integration.id}"
}

resource "aws_apigatewayv2_route" "post_lab_notify" {
  api_id    = aws_apigatewayv2_api.http_api.id
  route_key = "POST /labs/{labId}/notify"
  target    = "integrations/${aws_apigatewayv2_integration.lambda_read_integration.id}"
}

# ─── RUTA POST /api/v1/labs → SQS Directa (sin Lambda) ───────────────────────
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

resource "aws_apigatewayv2_integration" "sqs_integration" {
  api_id             = aws_apigatewayv2_api.http_api.id
  integration_type   = "AWS_PROXY"
  integration_subtype = "SQS-SendMessage"
  credentials_arn    = aws_iam_role.api_gateway_role.arn

  request_parameters = {
    QueueUrl    = aws_sqs_queue.ingest_queue.url
    MessageBody = "$request.body"
  }
}

resource "aws_apigatewayv2_route" "ingest_route" {
  api_id    = aws_apigatewayv2_api.http_api.id
  route_key = "POST /api/v1/labs"
  target    = "integrations/${aws_apigatewayv2_integration.sqs_integration.id}"
}

# ══════════════════════════════════════════════════════════════════════════════
# SECRETS MANAGER + KMS (Prioridad 1)
# ══════════════════════════════════════════════════════════════════════════════
resource "aws_secretsmanager_secret" "app_config" {
  name_prefix             = "alertas-civil/${var.environment}/"
  description             = "Configuración sensible del backend Alertas Civil"
  kms_key_id              = aws_kms_key.app_secrets.arn
  recovery_window_in_days = 7

  tags = {
    Name      = "alertas-civil-app-config-${var.environment}"
    ManagedBy = "Terraform"
  }
}

resource "aws_secretsmanager_secret_version" "app_config_v1" {
  secret_id = aws_secretsmanager_secret.app_config.id

  secret_string = jsonencode({
    dynamodb_table_name = "hc-alertas"
    s3_bucket_name      = var.s3_bucket_prefix
    jwt_signature_key   = "REPLACE_WITH_ACTUAL_JWT_SIGNING_KEY_64chars_min"
    jwt_issuer          = "alertas-civil-${var.environment}"
    jwt_ttl_seconds     = "3600"
  })

  lifecycle {
    ignore_changes = [secret_string]
  }
}

resource "aws_kms_key" "app_secrets" {
  description         = "Clave KMS para encriptar secretos de Alertas Civil"
  enable_key_rotation = true

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "Enable IAM User Permissions"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "Allow Lambda HTTP to use the key"
        Effect = "Allow"
        Principal = {
          AWS = aws_iam_role.lambda_http_role.arn
        }
        Action = [
          "kms:Decrypt",
          "kms:DescribeKey",
        ]
        Resource = "*"
      },
      {
        Sid    = "Allow Lambda SQS to use the key"
        Effect = "Allow"
        Principal = {
          AWS = aws_iam_role.lambda_sqs_role.arn
        }
        Action = [
          "kms:Decrypt",
          "kms:DescribeKey",
        ]
        Resource = "*"
      },
    ]
  })
}

resource "aws_kms_alias" "app_secrets" {
  name          = "alias/alertas-civil-${var.environment}-secrets"
  target_key_id = aws_kms_key.app_secrets.key_id
}

# ─── WAFv2 (Prioridad 3) ──────────────────────────────────────────────────────
resource "aws_wafv2_ip_set" "allowed_ips" {
  name               = "alertas-civil-allowed-ips-${var.environment}"
  scope              = "REGIONAL"
  ip_address_version = "IPV4"
  addresses = var.environment == "prod" ? [] : []

  tags = {
    ManagedBy = "Terraform"
  }
}

resource "aws_wafv2_web_acl" "api_acl" {
  name  = "alertas-civil-api-acl-${var.environment}"
  scope = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "BlockNoUserAgent"
    priority = 1

    action {
      block {}
    }

    statement {
      byte_match_statement {
        field_to_match {
          single_header {
            name = "user-agent"
          }
        }
        positional_constraint = "EXACTLY"
        search_string         = "MISSING_USER_AGENT"
        text_transformation {
          priority = 0
          type     = "NONE"
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "BlockNoUserAgent"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "LimitBodySize"
    priority = 2

    action {
      block {}
    }

    statement {
      size_constraint_statement {
        comparison_operator = "GT"
        size                = 102400 # 100 KB

        field_to_match {
          body {
            oversize_handling = "CONTINUE"
          }
        }
        text_transformation {
          priority = 0
          type     = "NONE"
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "LimitBodySize"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "RateLimitPerIP"
    priority = 3

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = 1000
        evaluation_window_sec = 60
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "RateLimitPerIP"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "AlertasCivilApiACL"
    sampled_requests_enabled   = true
  }

  tags = {
    ManagedBy = "Terraform"
  }
}

# Asociar WAF al STAGE (no al API ARN con wildcard)
resource "aws_wafv2_web_acl_association" "api_gateway" {
  resource_arn = aws_apigatewayv2_stage.api_stage.arn
  web_acl_arn  = aws_wafv2_web_acl.api_acl.arn
}

# ══════════════════════════════════════════════════════════════════════════════
# CLOUDWATCH ALARMS: OPERATIONAL VISIBILITY (DLQ & LAMBDA FAILURES)
# ══════════════════════════════════════════════════════════════════════════════

resource "aws_cloudwatch_metric_alarm" "ingest_dlq_alarm" {
  alarm_name          = "alertas-ingest-dlq-messages-visible-${var.environment}"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Sum"
  threshold           = 1
  alarm_description   = "Critical: Messages are accumulating in the ingest DLQ."
  dimensions = {
    QueueName = aws_sqs_queue.dlq_ingest_queue.name
  }
}

resource "aws_cloudwatch_metric_alarm" "notification_dlq_alarm" {
  alarm_name          = "alertas-notification-dlq-messages-visible-${var.environment}"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Sum"
  threshold           = 1
  alarm_description   = "Critical: Messages are accumulating in the notification DLQ."
  dimensions = {
    QueueName = aws_sqs_queue.notification_dlq_queue.name
  }
}

resource "aws_cloudwatch_metric_alarm" "lambda_error_alarm" {
  alarm_name          = "alertas-sqs-lambda-execution-errors-${var.environment}"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  metric_name         = "Errors"
  namespace           = "AWS/Lambda"
  period              = 60
  statistic           = "Sum"
  threshold           = 1
  alarm_description   = "Critical: Unhandled exception spike in alert processing lambda."
  dimensions = {
    FunctionName = aws_lambda_function.sqs_lambda.function_name
  }
}

resource "aws_cloudwatch_metric_alarm" "http_lambda_error_alarm" {
  alarm_name          = "alertas-http-lambda-execution-errors-${var.environment}"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  metric_name         = "Errors"
  namespace           = "AWS/Lambda"
  period              = 60
  statistic           = "Sum"
  threshold           = 1
  alarm_description   = "Critical: Unhandled exception spike in HTTP API lambda."
  dimensions = {
    FunctionName = aws_lambda_function.http_lambda.function_name
  }
}

# ─── OUTPUTS ──────────────────────────────────────────────────────────────────
output "api_endpoint" {
  description = "API Gateway endpoint URL"
  value       = aws_apigatewayv2_api.http_api.api_endpoint
}

output "http_lambda_name" {
  description = "Nombre de la Lambda HTTP"
  value       = aws_lambda_function.http_lambda.function_name
}

output "sqs_lambda_name" {
  description = "Nombre de la Lambda SQS Worker"
  value       = aws_lambda_function.sqs_lambda.function_name
}

output "dynamodb_table_name" {
  description = "Nombre de la tabla DynamoDB"
  value       = aws_dynamodb_table.hc_alertas.name
}

output "s3_bucket_name" {
  description = "Nombre del bucket S3 de documentos clínicos"
  value       = aws_s3_bucket.clinical_docs.id
}

output "sqs_queue_url" {
  description = "URL de la cola SQS de ingesta"
  value       = aws_sqs_queue.ingest_queue.id
}

output "secrets_manager_secret_arn" {
  description = "ARN del secreto de configuración"
  value       = aws_secretsmanager_secret.app_config.arn
}
