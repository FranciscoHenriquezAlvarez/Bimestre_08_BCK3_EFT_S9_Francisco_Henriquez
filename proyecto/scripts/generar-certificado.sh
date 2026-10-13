#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
KEYSTORE="$PROJECT_DIR/src/main/resources/keystore.p12"
STORE_PASSWORD="${SSL_KEYSTORE_PASSWORD:-changeit}"

rm -f "$KEYSTORE"

keytool -genkeypair \
  -alias bff-local \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore "$KEYSTORE" \
  -validity 365 \
  -storepass "$STORE_PASSWORD" \
  -keypass "$STORE_PASSWORD" \
  -dname "CN=localhost, OU=BackendIII, O=DuocUC, L=VinaDelMar, ST=Valparaiso, C=CL" \
  -ext "SAN=dns:localhost,ip:127.0.0.1"

echo "Certificado de laboratorio generado en src/main/resources/keystore.p12"
