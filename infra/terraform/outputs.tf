output "api_gateway_url" {
  value       = aws_apigatewayv2_stage.api_stage.invoke_url
  description = "URL del punto de enlace HTTP API Gateway v2 para consumir la API"
}

output "dynamodb_table_arn" {
  value       = aws_dynamodb_table.hc_alertas.arn
  description = "ARN de la tabla DynamoDB Single-Table"
}

output "s3_bucket_name" {
  value       = aws_s3_bucket.clinical_docs.id
  description = "Nombre del bucket S3 creado para el almacenamiento de PDFs"
}

output "sqs_ingest_queue_url" {
  value       = aws_sqs_queue.ingest_queue.url
  description = "URL de la cola SQS de ingesta principal"
}

output "sqs_dlq_queue_url" {
  value       = aws_sqs_queue.dlq_queue.url
  description = "URL de la cola SQS de descarte (DLQ)"
}

output "lambda_function_arn" {
  value       = aws_lambda_function.quarkus_lambda.arn
  description = "ARN de la función AWS Lambda de Quarkus"
}

output "lambda_execution_role_arn" {
  value       = aws_iam_role.lambda_execution_role.arn
  description = "ARN del rol de ejecución IAM asignado a la Lambda"
}
