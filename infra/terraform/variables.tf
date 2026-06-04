variable "aws_region" {
  type        = string
  description = "Región de AWS donde se desplegarán los recursos"
  default     = "us-east-1"
}

variable "environment" {
  type        = string
  description = "Ambiente de ejecución (dev, staging, prod)"
  default     = "dev"
}

variable "dynamodb_table_name" {
  type        = string
  description = "Nombre de la tabla DynamoDB Single-Table"
  default     = "hc-alertas"
}

variable "s3_bucket_prefix" {
  type        = string
  description = "Prefijo para el bucket de S3 de documentos clínicos"
  default     = "hc-alertas-clinical-docs"
}

variable "sqs_ingest_name" {
  type        = string
  description = "Nombre de la cola SQS de ingesta principal"
  default     = "hc-alertas-ingest-queue"
}

variable "sqs_dlq_name" {
  type        = string
  description = "Nombre de la cola SQS de descarte (DLQ)"
  default     = "hc-alertas-dlq"
}

variable "lambda_zip_path" {
  type        = string
  description = "Ruta al archivo ZIP de distribución compilado para Lambda"
  default     = ""
}

variable "lambda_memory" {
  type        = number
  description = "Tamaño de memoria asignada a la función Lambda (en MB)"
  default     = 1024
}

variable "lambda_timeout" {
  type        = number
  description = "Timeout en segundos para la ejecución de la Lambda"
  default     = 15
}

variable "log_retention_days" {
  type        = number
  description = "Días de retención para los logs en CloudWatch"
  default     = 7
}
