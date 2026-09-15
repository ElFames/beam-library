#!/usr/bin/env bash
# Compila BeamCryptoKit.swift (CryptoKit) como librería estática + cabecera Objective-C
# generada, para cada target Apple que consume `beam/src/iosMain` vía cinterop. No usa
# Xcode project ni SwiftPM: `swiftc -emit-objc-header-path` genera todo lo necesario con
# una sola invocación por target, sin más infraestructura de build.
#
# Se ejecuta automáticamente desde beam/build.gradle.kts antes de cada cinterop de iOS
# (ver la tarea `compileBeamCryptoKit*` allí) — normalmente no hace falta correrlo a mano,
# solo para depurar el propio módulo Swift de forma aislada.
set -euo pipefail
cd "$(dirname "$0")"

build_target() {
    local name="$1" triple="$2" sdk_name="$3"
    local out="build/$name"
    mkdir -p "$out"
    local sdk_path
    sdk_path=$(xcrun --sdk "$sdk_name" --show-sdk-path)
    echo "== BeamCryptoKit: $name ($triple) =="
    xcrun swiftc -emit-library -emit-module -static \
        -module-name BeamCryptoKit \
        -emit-objc-header-path "$out/BeamCryptoKit-Swift.h" \
        -emit-module-path "$out/BeamCryptoKit.swiftmodule" \
        -target "$triple" \
        -sdk "$sdk_path" \
        BeamCryptoKit.swift \
        -o "$out/libBeamCryptoKit.a"
}

build_target "iosArm64" "arm64-apple-ios17.0" "iphoneos"
build_target "iosSimulatorArm64" "arm64-apple-ios17.0-simulator" "iphonesimulator"
build_target "macosArm64" "arm64-apple-macos14" "macosx"

echo "OK: librerías + cabeceras generadas en beam/native/ios/build/<target>/"
