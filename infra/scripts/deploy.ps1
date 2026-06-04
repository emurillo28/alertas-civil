# PowerShell deployment script for Windows users.
# Compiles Quarkus into a native Linux binary inside a container, and deploys it using Terraform.

$ErrorActionPreference = "Stop"

Write-Host "=== Iniciando Despliegue de Alertas Hospital Civil ===" -ForegroundColor Blue

# 1. Validar herramientas
Write-Host "[1/5] Validando herramientas locales..." -ForegroundColor Yellow
$requiredCmds = @("mvn", "terraform", "docker", "aws")
foreach ($cmd in $requiredCmds) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Write-Error "Error: La herramienta '$cmd' no está instalada o no está en el PATH."
        exit 1
    }
}
Write-Host "Prerrequisitos OK." -ForegroundColor Green

# Verificar que Docker está corriendo
$dockerInfo = docker info 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "Error: Docker no está en ejecución. Es necesario para compilar el binario nativo Linux."
    exit 1
}

# 2. Compilar aplicación
Write-Host "[2/5] Compilando ejecutable nativo GraalVM (dentro de contenedor)..." -ForegroundColor Yellow
Push-Location "$PSScriptRoot\..\.."

# Compilar en limpio omitiendo pruebas
mvn clean package -Dnative -Dquarkus.native.container-build=true -DskipTests

$zipPath = "target\function.zip"
if (-not (Test-Path $zipPath)) {
    Write-Error "Error: No se encontró el archivo $zipPath. Falló la compilación de Quarkus."
    Pop-Location
    exit 1
}
Write-Host "Compilación exitosa. Artefacto listo en: $zipPath" -ForegroundColor Green

# 3. Preparar Terraform
Write-Host "[3/5] Inicializando Terraform..." -ForegroundColor Yellow
Set-Location "infra\terraform"

# Crear zip dummy si es la primera vez
if (-not (Test-Path "dummy-lambda.zip")) {
    New-Item -Path . -Name "dummy.txt" -ItemType "file" -Value "Dummy content" -Force | Out-Null
    Compress-Archive -Path "dummy.txt" -DestinationPath "dummy-lambda.zip" -Force
    Remove-Item -Path "dummy.txt" -Force
}

terraform init

# 4. Desplegar
Write-Host "[4/5] Aplicando plan de Terraform..." -ForegroundColor Yellow
terraform apply -var="lambda_zip_path=../../$zipPath" -auto-approve

# 5. Obtener Outputs
Write-Host "=== Despliegue completado con éxito ===" -ForegroundColor Green
$apiUrl = terraform output -raw api_gateway_url
Write-Host "Punto de entrada de la API: ${apiUrl}api/v1/labs" -ForegroundColor Green

Pop-Location
