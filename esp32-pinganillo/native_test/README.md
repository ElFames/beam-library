# native_test

Valida el handshake criptográfico del firmware (`beam_crypto.cpp` +
`beam_protocol.cpp`) en tu Mac/Linux, sin ESP32 real. Ver la sección
"El handshake criptográfico YA está validado" en `../README.md` para el
contexto completo y el resultado ya obtenido.

```bash
brew install mbedtls@3 cjson   # mbedtls@3, no mbedtls (la 4.x reestructura la API)
./build.sh
./handshake_test
```

Antes de ejecutar `./handshake_test`, arranca el lado Kotlin: corre `fun main()`
de `beam/src/jvmTest/kotlin/com/nubax/beam/library/ManualHandshakeHarness.kt`
(botón ▶ del IDE). Se queda escuchando en el puerto 9999 hasta que hagas Ctrl+C.
