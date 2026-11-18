# AWS Infrastructure Build Plan — alertas-civil

**Source of truth:** `infra/terraform/main.tf` (verified read), `variables.tf`, backend config files.
**Status as of this run:** Terraform code is mostly complete but has **4 hard blockers** that will make `terraform apply` fail, and **1 runtime gap** that will make the backend fail when handling notifications.

---

## Phase 0 — Fix Terraform before applying (REQUIRED)

### Fix 0-1. Remove stale `outputs.tf`
**File:** `infra/terraform/outputs.tf`
**Problem:** It references resources that no longer exist after the split into HTTP + SQS Lambdas:
- `aws_lambda_function.quarkus_lambda` → actual resource is `aws_lambda_function.http_lambda` and `aws_lambda_function.sqs_lambda`
- `aws_iam_role.lambda_execution_role` → actual roles are `lambda_http_role` and `lambda_sqs_role`
- It also defines `s3_bucket_name`, which duplicates the output already at the bottom of `main.tf`.

**Action:** Delete `infra/terraform/outputs.tf`. The bottom of `main.tf` already emits all needed outputs.

### Fix 0-2. Add the missing Notification SQS queue
**File:** `infra/terraform/main.tf`
**Problem:** The backend's `SqsNotificationAdapter` injects `app.sqs.notification-queue-url`. In dev this points to `localhost:4566/000000000000/notification-queue`, but the Terraform only creates `ingest_queue` and `dlq_queue`. There is no `notification-queue` resource. In production, the Lambda will fail at runtime when trying to send a notification.

**Action:** Add a third SQS queue after `ingest_queue` (~line 155):

```hcl
resource "aws_sqs_queue" "notification_queue" {
  name                      = "hc-alertas-notification-queue"
  message_retention_seconds = 1209600 # 14 días
  sqs_managed_sse_enabled   = true
}
```

### Fix 0-3. Wire the notification queue into HTTP Lambda
**File:** `infra/terraform/main.tf` (~line 313, inside `aws_lambda_function.http_lambda.environment`)
**Problem:** HTTP Lambda handles `POST /labs/{labId}/notify` → `NotifyLabReadyService` → `SqsNotificationAdapter`. The Lambda has no `app.sqs.notification-queue-url` env var.

**Action:** Add this line inside `environment { variables = { ... } }`:

```hcl
app.sqs.notification-queue-url = aws_sqs_queue.notification_queue.url
```

### Fix 0-4. Grant HTTP Lambda permission to send to the notification queue
**File:** `infra/terraform/main.tf` (~line 184, inside `aws_iam_policy.http_policy`)
**Problem:** The HTTP Lambda's IAM policy only allows `dynamodb:GetItem`, `dynamodb:Query`, `secretsmanager:GetSecretValue`, `kms:Decrypt/DescribeKey`. It cannot call `sqs:SendMessage` on the notification queue.

**Action:** Add inside `policy = jsonencode({ ... })`:

```hcl
{
  Effect   = "Allow"
  Action   = ["sqs:SendMessage"]
  Resource = aws_sqs_queue.notification_queue.arn
}
```

### Fix 0-5. (Optional but recommended) Make WAF rate limit actually block
**File:** `infra/terraform/main.tf` (~line 662)
**Problem:** The rate-limit rule uses `action { count {} }`. In WAFv2, rate-based rules with `count` only log and don't block. If the intent is enforcement, the action must be `block`.

**Action:** Change:
```hcl
action {
  count {}
}
```
to:
```hcl
action {
  block {}
}
```

---

## Phase 1 — Build the artifact to deploy

The Lambdas use `filename = var.lambda_zip_path` with a `dummy-lambda.zip` fallback. You must build a real zip. Terraform will deploy it during `apply`.

### Step 1-1. Build native Lambda artifact
```bash
cd C:/dev/alertas-civil

# Native build (recommended for Lambda cold starts)
mvn package -Pprod -Dnative -DskipTests

# The artifact path is typically:
# target/alertas-civil-0.1.0-SNAPSHOT-runner.zip
# or the Quarkus Lambda zip:
# target/function.zip
```

**Actual path depends on your `pom.xml` Quarkus Lambda config.** Verify which zip the build produces and use that path.

### Step 1-2. Verify zip contents
```bash
unzip -l target/*.zip | grep -E "bootstrap|function.jar|quarkus"
```
The Lambda handler is `io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler`, so the zip must contain a `bootstrap` native binary or a `lib/` directory suitable for `provided.al2023` runtime.

---

## Phase 2 — Terraform deployment

### Step 2-1. Prepare terraform.tfvars
Create `infra/terraform/terraform.tfvars`:

```hcl
aws_region   = "us-east-1"
environment  = "dev"
lambda_zip_path = "C:/dev/alertas-civil/target/function.zip"   # or runner.zip, match your build
lambda_memory = 512
lambda_timeout = 30
log_retention_days = 30
```

### Step 2-2. Init + validate
```bash
cd C:/dev/alertas-civil/infra/terraform

# terraform not installed in current environment — run this on your local machine:
terraform init
terraform fmt -check
terraform validate
```

**Expected result:** `Success! The configuration is valid.`

### Step 2-3. Plan
```bash
terraform plan -out=tfplan
```

Verify the plan contains:
- `aws_sqs_queue.notification_queue` and `aws_sqs_queue.notification_dlq_queue`
- `aws_iam_policy.http_policy` includes `sqs:SendMessage` on notification queue
- 2 Lambda functions: `hcg-alertas-backend-http-dev` and `hcg-alertas-backend-worker-dev`
- API Gateway HTTP API with 5 routes
- WAFv2 ACL associated to stage
- **No** `aws_lambda_function.quarkus_lambda` or `aws_iam_role.lambda_execution_role` references (stale outputs.tf removed)

### Step 2-4. Apply
```bash
terraform apply tfplan
```

Terraform will deploy infrastructure **and** push the Lambda zip in this single operation. Do NOT run `aws lambda update-function-code` afterward — that causes state drift.

---

## Phase 3 — Rotate secrets (post-apply, safe)

### Step 3-1. Rotate the JWT signing key
The Secrets Manager secret `app_config_v1` is created with a placeholder value AND has `lifecycle { ignore_changes = [secret_string] }`, so future Terraform runs will not overwrite this value. It is safe to populate the real key now.

```bash
aws secretsmanager update-secret-version \
  --secret-id alertas-civil/dev \
  --secret-string '{"dynamodb_table_name":"hc-alertas","s3_bucket_name":"hc-alertas","jwt_signature_key":"<REAL_64_CHAR_KEY>","jwt_issuer":"alertas-civil-dev","jwt_ttl_seconds":"3600"}'
```

Generate a real key:
```bash
openssl rand -base64 48 | tr -d '\n' | head -c 64
```

**Why this is safe now:** The `ignore_changes` directive tells Terraform to stop managing `secret_string`. Future `terraform apply` runs for any other resource will leave this secret untouched.

---

## Phase 4 — Local testing

### Step 4-1. Create notification queue in LocalStack (if testing locally)
```bash
awslocal sqs create-queue --queue-name notification-queue
```

### Step 4-2. Update `application-dev.properties`
The existing dev profile has:
```properties
app.sqs.notification-queue-url=http://localhost:4566/000000000000/notification-queue
```
This matches the queue name above. If you change the queue name in Terraform, update this too.

### Step 4-3. Start backend
```bash
cd C:/dev/alertas-civil
mvn quarkus:dev
# Verify health:
curl http://localhost:8081/health
curl http://localhost:8081/q/health
```

### Step 4-4. Test read endpoints
```bash
BASE="http://localhost:8081/labs"
curl "$BASE/patients" | jq
curl "$BASE/patients/P102/latest" | jq
curl "$BASE/patients/P102/history" | jq
```

### Step 4-5. Test notify endpoint
```bash
curl -X POST "$BASE/labs/L001/notify" -H "Content-Type: application/json" -d '{"patientId":"P102","recipient":"test@example.com"}'
```

If this returns 200, the notification queue wiring is correct.

---

## Phase 5 — UI wiring (after infra is live)

Set these in your hcg-v1 Expo environment:
```env
EXPO_PUBLIC_BACKEND_URL=<terraform output api_endpoint>
```

Delete any mock data files. The UI should hit:
- `GET /labs/patients`
- `GET /labs/patients/{patientId}/latest`
- `GET /labs/patients/{patientId}/history`
- `POST /labs/{labId}/notify`

---

## Summary: Exact execution order

| Order | Action | Command / File |
|-------|--------|----------------|
| 1 | Delete `infra/terraform/outputs.tf` | `rm infra/terraform/outputs.tf` ✅ done |
| 2 | Patch `main.tf`: notification DLQ + queue, IAM sqs:SendMessage, HTTP Lambda env, WAF block, secret lifecycle ignore_changes | edit file manually or via patch ✅ done |
| 3 | Build Lambda zip | `mvn package -Pprod -DskipTests` or `mvn package -Pprod -Dnative -DskipTests` |
| 4 | Create `infra/terraform/terraform.tfvars` with `lambda_zip_path` | new file in `infra/terraform/` |
| 5 | Init + plan + apply | `terraform init && terraform plan -out=tfplan && terraform apply tfplan` |
| 6 | Rotate JWT secret via AWS CLI | `aws secretsmanager update-secret-version ...` |
| 7 | Verify endpoints | `curl <api_endpoint>/labs/patients` |
| 8 | Wire UI | set `EXPO_PUBLIC_BACKEND_URL=<terraform output api_endpoint>` |

**Do not run `aws lambda update-function-code` after terraform apply.** Terraform owns the code state via `lambda_zip_path`.

**Do not skip `lifecycle { ignore_changes = [secret_string] }`.** Without it, Terraform will overwrite your rotated JWT key back to the placeholder on the next apply.

---

## Critical checks before marking as PROD

- [ ] `jwt_signature_key` is not the placeholder value
- [ ] `lifecycle { ignore_changes = [secret_string] }` is present on `aws_secretsmanager_secret_version.app_config_v1`
- [ ] WAFv2 rate-limit action is `block` (enforcement)
- [ ] `aws_sqs_queue.notification_dlq_queue` exists with `redrive_policy`
- [ ] `aws_sqs_queue.notification_queue` IAM allows `sqs:SendMessage` from HTTP Lambda
- [ ] `environment` variable is set to `"prod"` in `terraform.tfvars`
- [ ] Deletion protection is enabled on DynamoDB (`var.environment == "prod"` already does this)
- [ ] CloudWatch alarm on `Unknown analyte` metric filter (manual; see gap analysis)
- [ ] CORS origins updated in `application-prod.properties` from localhost to real hospital domain
- [ ] No `aws_lambda_function.quarkus_lambda` or `aws_iam_role.lambda_execution_role` references remain in Terraform
