# PowerShell script to seed development data into LocalStack DynamoDB for Windows users.
# Fix encoding for UTF-8 output in PowerShell console
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Continue"

$ENDPOINT = "http://localhost:4566"
$REGION = "us-east-1"
$TABLE_NAME = "hc-alertas"

$hasAwsCli = [bool](Get-Command aws -ErrorAction SilentlyContinue)
$TmpFile = [System.IO.Path]::GetTempFileName()

function Invoke-DynamoPut {
    param(
        [string]$ItemJson,
        [string]$ConditionExpression = $null
    )
    # Write JSON to temp file to avoid AWS CLI comma/special-char parsing issues
    # UTF8Encoding($false) = UTF-8 without BOM; AWS CLI rejects BOM files
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($TmpFile, $ItemJson, $utf8NoBom)
    $fileArg = "file://$($TmpFile.Replace('\','/'))"

    if ($hasAwsCli) {
        if ($ConditionExpression) {
            aws --endpoint-url="$ENDPOINT" --region "$REGION" dynamodb put-item `
                --table-name "$TABLE_NAME" --item "$fileArg" --condition-expression "$ConditionExpression" 2>&1 | Where-Object { $_ -notmatch "^Note:" } | Out-Null
        } else {
            aws --endpoint-url="$ENDPOINT" --region "$REGION" dynamodb put-item `
                --table-name "$TABLE_NAME" --item "$fileArg" 2>&1 | Where-Object { $_ -notmatch "^Note:" } | Out-Null
        }
    } else {
        $containerPath = "/tmp/seed-item.json"
        docker cp $TmpFile "hc-alertas-localstack:$containerPath" | Out-Null
        if ($ConditionExpression) {
            docker exec hc-alertas-localstack awslocal dynamodb put-item `
                --table-name "$TABLE_NAME" --item "file://$containerPath" --condition-expression "$ConditionExpression" | Out-Null
        } else {
            docker exec hc-alertas-localstack awslocal dynamodb put-item `
                --table-name "$TABLE_NAME" --item "file://$containerPath" | Out-Null
        }
    }
}

function Put-Metadata {
    param(
        [string]$PatientId,
        [string]$Name,
        [string]$DateOfBirth,
        [string]$ClinicalHistory
    )
    $pk = "PATIENT#$PatientId"
    $itemJson = @{
        PK = @{ S = $pk }
        SK = @{ S = "METADATA" }
        Name = @{ S = $Name }
        DateOfBirth = @{ S = $DateOfBirth }
        ClinicalHistory = @{ S = $ClinicalHistory }
    } | ConvertTo-Json -Compress

    Invoke-DynamoPut -ItemJson $itemJson
}

function Put-Lab {
    param(
        [string]$PatientId,
        [string]$LabId,
        [string]$TestType,
        [string]$Timestamp,
        [bool]$Critical,
        [int]$Version,
        [hashtable]$AnalyticValues,
        [string]$VisibilityStatus
    )
    $pk = "PATIENT#$PatientId"
    $sk = "LAB#$LabId"

    $avMap = @{}
    foreach ($k in $AnalyticValues.Keys) {
        $avMap[$k] = @{ S = $AnalyticValues[$k] }
    }

    $itemJson = @{
        PK = @{ S = $pk }
        SK = @{ S = $sk }
        TestType = @{ S = $TestType }
        AnalyticValues = @{ M = $avMap }
        Timestamp = @{ S = $Timestamp }
        IsCriticalAlert = @{ BOOL = $Critical }
        S3PathComplete = @{ S = "s3://hc-alertas-clinical-docs-dev-local/labs/$LabId.pdf" }
        Version = @{ N = "$Version" }
        VisibilityStatus = @{ S = $VisibilityStatus }
    } | ConvertTo-Json -Depth 5 -Compress

    Invoke-DynamoPut -ItemJson $itemJson -ConditionExpression "attribute_not_exists(PK) AND attribute_not_exists(SK)"
}

Write-Host "=== Esperando que LocalStack esté listo ===" -ForegroundColor Yellow
while ($true) {
    try {
        $health = Invoke-RestMethod -Uri "$ENDPOINT/_localstack/health" -Method Get -ErrorAction SilentlyContinue
        if ($health.services.dynamodb -eq "available" -or $health.services.dynamodb -eq "running") {
            break
        }
    } catch {}
    Start-Sleep -Seconds 2
}
Write-Host "LocalStack listo." -ForegroundColor Green
Write-Host "=== Sembrando datos de prueba en la tabla '$TABLE_NAME' ===" -ForegroundColor Cyan

# ── Paciente HCG12345: Manuel Rodríguez ───────────────────────────────────────
Put-Metadata -PatientId "HCG12345" -Name "Manuel Rodríguez" -DateOfBirth "1978-03-15T00:00:00Z" -ClinicalHistory "Hipertensión arterial · Alergia a Sulfas"

Put-Lab -PatientId "HCG12345" -LabId "lab-001" -TestType "QUIMICA_SANGUINEA" -Timestamp "2026-06-22T08:00:00Z" -Critical $false -Version 1 `
    -AnalyticValues @{ glucosa = "90 mg/dL"; sodio = "140 mEq/L"; creatinina = "1.0 mg/dL" } -VisibilityStatus "NOTIFIED"

Put-Lab -PatientId "HCG12345" -LabId "lab-002" -TestType "PERFIL_LIPIDOS" -Timestamp "2026-06-22T10:30:00Z" -Critical $true -Version 1 `
    -AnalyticValues @{ colesterol_total = "240 mg/dL"; trigliceridos = "180 mg/dL"; cHDL = "38 mg/dL" } -VisibilityStatus "READ_CONFIRMED"

Put-Lab -PatientId "HCG12345" -LabId "lab-003" -TestType "BIOMETRIA_HEMATICA" -Timestamp "2026-06-23T07:15:00Z" -Critical $false -Version 1 `
    -AnalyticValues @{ hemoglobina = "13.8 g/dL"; plaquetas = "210000 /uL"; leucocitos = "7200 /uL" } -VisibilityStatus "PENDING"
Write-Host "  Paciente HCG12345: 3 análisis registrados." -ForegroundColor Gray

# ── Paciente HCG78901: Ana García ─────────────────────────────────────────────
Put-Metadata -PatientId "HCG78901" -Name "Ana García" -DateOfBirth "1992-11-08T00:00:00Z" -ClinicalHistory "Diabetes Tipo 2 · Embarazo (32 sem)"

Put-Lab -PatientId "HCG78901" -LabId "lab-101" -TestType "BIOMETRIA_HEMATICA" -Timestamp "2026-06-22T09:00:00Z" -Critical $true -Version 1 `
    -AnalyticValues @{ plaquetas = "35000 /uL"; hemoglobina = "8.2 g/dL"; leucocitos = "4500 /uL" } -VisibilityStatus "READ_CONFIRMED"

Put-Lab -PatientId "HCG78901" -LabId "lab-102" -TestType "QUIMICA_SANGUINEA" -Timestamp "2026-06-22T14:00:00Z" -Critical $false -Version 1 `
    -AnalyticValues @{ glucosa = "110 mg/dL"; potasio = "4.2 mEq/L"; creatinina = "0.8 mg/dL" } -VisibilityStatus "NOTIFIED"
Write-Host "  Paciente HCG78901: 2 análisis registrados." -ForegroundColor Gray

# ── Paciente HCG44218: Roberto Méndez ─────────────────────────────────────────
Put-Metadata -PatientId "HCG44218" -Name "Roberto Méndez" -DateOfBirth "1965-07-22T00:00:00Z" -ClinicalHistory "Insuficiencia renal crónica · HTA"

Put-Lab -PatientId "HCG44218" -LabId "lab-201" -TestType "QUIMICA_SANGUINEA" -Timestamp "2026-06-23T06:45:00Z" -Critical $true -Version 1 `
    -AnalyticValues @{ glucosa = "410 mg/dL"; potasio = "6.1 mEq/L"; creatinina = "3.2 mg/dL" } -VisibilityStatus "NOTIFIED"

Put-Lab -PatientId "HCG44218" -LabId "lab-202" -TestType "BIOMETRIA_HEMATICA" -Timestamp "2026-06-23T11:20:00Z" -Critical $true -Version 1 `
    -AnalyticValues @{ hemoglobina = "7.8 g/dL"; plaquetas = "90000 /uL"; leucocitos = "6100 /uL" } -VisibilityStatus "READ_CONFIRMED"
Write-Host "  Paciente HCG44218: 2 análisis registrados." -ForegroundColor Gray

Write-Host "=== Sembrado completado exitosamente: 3 pacientes, 7 análisis ===" -ForegroundColor Green
