#!/usr/bin/env bash
# Compila handshake_test.cpp usando mbedTLS y cJSON de Homebrew.
# La primera vez: brew install mbedtls cjson
set -euo pipefail

cd "$(dirname "$0")"

# mbedtls@3, no mbedtls (4.x): ESP-IDF bundla la línea 3.x, y su API de bajo
# nivel (ctr_drbg, ecdh, pk_ec...) cambió de sitio/forma en la 4.x.
MBEDTLS_PREFIX="$(brew --prefix mbedtls@3)"
CJSON_PREFIX="$(brew --prefix cjson)"

g++ -std=c++17 \
  -I"$MBEDTLS_PREFIX/include" -I"$CJSON_PREFIX/include" \
  handshake_test.cpp \
  -L"$MBEDTLS_PREFIX/lib" -L"$CJSON_PREFIX/lib" \
  -lmbedtls -lmbedx509 -lmbedcrypto -lcjson \
  -o handshake_test

echo "Compilado correctamente: ./handshake_test"
