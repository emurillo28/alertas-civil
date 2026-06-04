#!/usr/bin/env bash
set -euo pipefail

# Script de compilación y despliegue automatizado y predecible.
# Este script compila la aplicación Quarkus en una imagen nativa Linux usando Docker,
# y luego despliega la infraestructura y el código usando Terraform.

# Colores para salida limpia
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== Iniciando Despliegue de Alertas Hospital Civil ===${NC}"

# 1. Validar prerrequisitos
echo -e "${YELLOW}[1/5] Validando herramientas locales...${NC}"
for cmd in mvn terraform docker aws; do
  if ! command -v "$cmd" &> /dev/null; then
    echo -e "${RED}Error: La herramienta '$cmd' no está instalada o no está en el PATH.${NC}"
    exit 1
  fi
done
echo -e "${GREEN}Prerrequisitos OK.${NC}"

# Verificar que Docker está corriendo (necesario para compilación cruzada a Linux en Windows/macOS)
if ! docker info &> /dev/null; then
  echo -e "${RED}Error: Docker no está en ejecución. Es necesario para compilar el binario nativo Linux.${NC}"
  exit 1
fi

# 2. Compilar aplicación
# Usamos container-build=true para compilar un binario Linux ELF compatible con AWS Lambda al2023
echo -e "${YELLOW}[2/5] Compilando ejecutable nativo GraalVM (dentro de contenedor)...${NC}"
cd ../..
mvn clean package -Dnative -Dquarkus.native.container-build=true -DskipTests

# Validar que se generó el artefacto zip de Quarkus
ZIP_PATH="target/function.zip"
if [ ! -f "$ZIP_PATH" ]; then
  echo -e "${RED}Error: No se encontró el archivo $ZIP_PATH. Falló la compilación de Quarkus.${NC}"
  exit 1
fi
echo -e "${GREEN}Compilación exitosa. Artefacto listo en: $ZIP_PATH${NC}"

# 3. Preparar Terraform
echo -e "${YELLOW}[3/5] Inicializando Terraform...${NC}"
cd infra/terraform
terraform init

# Generar un archivo zip dummy si es la primera vez (para evitar fallos de inicialización de recursos en Terraform)
if [ ! -f "dummy-lambda.zip" ]; then
  echo "Dummy content" > dummy.txt
  zip dummy-lambda.zip dummy.txt
  rm dummy.txt
fi

# 4. Desplegar Infraestructura y Aplicación
echo -e "${YELLOW}[4/5] Aplicando plan de Terraform...${NC}"
# Pasamos la ruta del zip nativo real de Quarkus compilado
terraform apply -var="lambda_zip_path=../../$ZIP_PATH" -auto-approve

# 5. Mostrar resultados
echo -e "${GREEN}=== Despliegue completado con éxito ===${NC}"
API_URL=$(terraform output -raw api_gateway_url)
echo -e "${BLUE}Punto de entrada de la API:${NC} ${GREEN}${API_URL}api/v1/labs${NC}"
echo -e "Puedes enviar un POST JSON a esta URL usando el payload correspondiente."
