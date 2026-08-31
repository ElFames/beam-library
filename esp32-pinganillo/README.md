# Pinganillo (firmware ESP32)

Firmware en C++ (ESP-IDF) que convierte un ESP32 + micrófono I2S + altavoz I2S en
un nodo **acceptor** de la red **Aircom** (antes "Beam"), hablando el mismo
protocolo criptográfico que usan Android y Desktop
(`beam/src/commonMain/.../connection/MeshBeamConnection.kt`): mismo beacon UDP,
mismo handshake TCP, mismo cifrado — pero a diferencia de Android/Desktop
(simétricos), el pinganillo **nunca conecta hacia fuera**, solo acepta. Ver
`PROJECT.md` (raíz del repo) para el diseño funcional completo.

## Qué hace

1. Levanta su propio WiFi (AP) con SSID/contraseña **únicos de fábrica** (como
   una tarjeta SIM — ver `PROJECT.md` §2.3) — así el móvil puede unirse sin que
   el pinganillo tenga pantalla.
2. Si además se le ha configurado la WiFi de casa (una vez, ver más abajo),
   también se conecta a ella como cliente y hace NAT: quien se una a su AP
   recibe internet de forma transparente.
3. **Vinculación**: mientras no haya nadie vinculado (NVS vacío), el primer
   Android que complete el handshake criptográfico se vincula automáticamente
   (conocer las credenciales del AP ya es la prueba de autorización). A partir
   de ahí, solo ESE Android puede volver a conectar — cualquier otro se
   rechaza. Si el Android vinculado nos avisa de que ya no lo estamos (porque
   el usuario lo desvinculó desde la app), el pinganillo borra su estado y se
   reinicia solo, listo para un emparejamiento nuevo.
4. **Botón físico, 3 estados** (pulsación corta, alterna): `IDLE` →
   (pulsación) → `ESCUCHANDO` (captura y manda audio) → (pulsación) →
   `ESPERANDO_RESPUESTA` (corta el envío, marca el último trozo como final, y
   espera la respuesta) → vuelve a `IDLE` al recibirla o pasados 30s sin
   respuesta. Una pulsación mientras espera cancela la espera y empieza a
   grabar de nuevo ("olvida eso, pregunto otra cosa").
5. **Pulsación larga** (~3s, independiente de los 3 estados de arriba):
   desvincula del Android actual y reinicia el dispositivo a modo de fábrica.
6. Si le llega audio del Android vinculado mientras está `ESPERANDO_RESPUESTA`,
   lo reproduce por el altavoz.

No hay reconocimiento de palabra de activación ("jarvis") — es un placeholder
deliberado resuelto con el botón; ver `PROJECT.md` §3.3 para el porqué.

## Credenciales — únicas por unidad, como una SIM (PROJECT.md §2.3)

`main/config.h` trae unos valores de ejemplo para desarrollo:
```c
#define PINGANILLO_AP_SSID     "Pinganillo-Beam"
#define PINGANILLO_AP_PASSWORD "beam12345"
```
Para una unidad física real: **antes de compilar/flashear cada pinganillo**,
cambia estos dos valores a algo único para esa unidad concreta, y anota el
`device_id` que imprime por el log de arranque (`beam_crypto::device_id()`).
Esos tres datos (SSID, contraseña, deviceId) son los que van impresos en la
tarjeta física que acompaña al pinganillo — el backend los guarda igual desde
fabricación, para poder recuperarlos si el usuario pierde la tarjeta.

`PinganilloDefaults.AP_SSID`/`AP_PASSWORD` en
`beam/src/commonMain/kotlin/com/nubax/beam/library/connectivity/PinganilloDefaults.kt`
son solo los valores de ejemplo que usa la demo (`composeApp`) para probar
contra un único pinganillo de desarrollo — no hace falta que coincidan con los
de una unidad real, solo con el prototipo que tengas flasheado en cada momento.

## Construir y flashear

Necesitas [ESP-IDF](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/get-started/) instalado (v5.x recomendado).

```bash
cd esp32-pinganillo
idf.py set-target esp32
idf.py build
idf.py -p /dev/tty.usbserial-XXXX flash monitor
```

## Cableado (ajustar en `main/config.h` si tu hardware es distinto)

Se asume un mic digital I2S tipo **INMP441** y un ampli+altavoz I2S tipo
**MAX98357A** — es la combinación más habitual en proyectos de este tipo, pero
son solo los `#define` de partida:

| Señal          | Pin ESP32 (mic) | Pin ESP32 (altavoz) |
|----------------|-----------------|----------------------|
| BCLK           | GPIO26          | GPIO27               |
| WS / LRCLK     | GPIO25          | GPIO14               |
| DATA           | GPIO22 (in)     | GPIO12 (out)         |

Botón físico: `GPIO4` a GND (pull-up interno, activo en bajo) — evita `GPIO0` a
propósito porque decide el modo de arranque en la mayoría de placas.

Si el audio sale saturado o demasiado bajo, ajusta el desplazamiento de 32→16
bits en `audio.cpp` (`raw[i] >> 14`) — depende de la ganancia del mic concreto.

## Cómo probar cada pieza (bring-up paso a paso)

No esperes a tenerlo todo cableado para probar por primera vez — así, si algo
falla, sabes exactamente en qué pieza mirar. Sigue este orden:

### 0. Toolchain (antes de tocar este proyecto)

Crea un proyecto de ejemplo vacío y flashéalo, SIN nada de este firmware:
```bash
idf.py create-project hello
cd hello
idf.py set-target esp32
idf.py build
idf.py -p /dev/cu.usbserial-XXXX flash monitor
```
Si esto no compila/flashea, el problema es el toolchain o el driver USB, no
el firmware del pinganillo — resuélvelo aquí primero.

### 1. La placa arranca y su WiFi aparece (sin cablear nada más)

Con solo la placa ESP32 conectada por USB (mic/altavoz/botón aún no hacen
falta), flashea `esp32-pinganillo/` tal cual y mira `idf.py monitor`:
- Deberías ver `Beam iniciado: id=... bonded=no (FABRICA)`.
- Desde el WiFi de tu móvil, busca la red — debería aparecer el SSID de
  `config.h` (`Pinganillo-Beam` si no lo has cambiado todavía).
- Únete con la contraseña de `config.h`. Si conecta, la parte de red ya funciona.

### 2. El botón (sin mic/altavoz todavía)

Cablea solo el botón (GPIO4 a GND). Con `idf.py monitor` abierto:
- Pulsación corta → deberías ver `IDLE -> ESCUCHANDO`.
- Otra pulsación corta → `ESCUCHANDO -> ESPERANDO_RESPUESTA`.
- Mantén pulsado ~3s → `Pulsación larga -> desvincular` y el dispositivo se
  reinicia (verás el log de arranque otra vez, con `bonded=no`).

Si no ves ninguna línea al pulsar: revisa el pin en `config.h`
(`BUTTON_GPIO_PIN`) contra tu cableado real.

### 3. El micrófono (verificación temporal, sin red de por medio)

Añade temporalmente esta línea dentro de `voice_task()`, justo después del
bucle que rellena `out16[]` en `audio.cpp` (bórrala luego, es solo para probar):
```cpp
int16_t peak = 0;
for (size_t i = 0; i < samples; i++) if (abs(out16[i]) > peak) peak = abs(out16[i]);
ESP_LOGI(TAG, "pico de audio: %d", peak);
```
Recompila, flashea, pulsa el botón para entrar en `ESCUCHANDO`, y habla/da
palmas cerca del mic. Si el número sube notablemente cuando haces ruido, el
mic funciona. Si siempre sale ~0 o un valor fijo, revisa el cableado I2S
(BCLK/WS/DATA) o el desplazamiento de bits (`>> 14`) comentado en el código.

### 4. El altavoz (sin red, con un tono de prueba)

Añade temporalmente esta función en `audio.cpp` y llámala una vez desde
`start()` (después de `install_speaker()`), solo para confirmar que el
ampli+altavoz suenan sin depender de que llegue audio por red:
```cpp
void test_tone() {
    std::vector<int16_t> tone(AUDIO_SAMPLE_RATE_HZ); // 1 segundo
    for (size_t i = 0; i < tone.size(); i++) {
        tone[i] = static_cast<int16_t>(3000.0 * sin(2.0 * M_PI * 440.0 * i / AUDIO_SAMPLE_RATE_HZ));
    }
    size_t written = 0;
    i2s_write(I2S_SPK_PORT, tone.data(), tone.size() * sizeof(int16_t), &written, portMAX_DELAY);
}
```
(Necesita `#include <cmath>`.) Si suena un pitido de 1 segundo al arrancar, el
ampli+altavoz funcionan. Bórralo cuando confirmes que suena.

### 5. Integración completa (con la demo `composeApp`)

Ya con todo cableado y sin los snippets de prueba:
1. Arranca la demo en Android (o Desktop) y pulsa "Iniciar SDK".
2. Únete a la WiFi del pinganillo (en Android, con el botón "Vincular
   pinganillo" ya hace el join + empareja; en Desktop, únete a mano a esa red
   primero).
3. En la lista de "Dispositivos descubiertos", pulsa "Emparejar pinganillo".
   Deberías ver `Peer conectado` en los logs de la demo.
4. Pulsa el botón físico del pinganillo, habla, vuelve a pulsarlo. En la demo
   deberías ver líneas tipo `...audio: seq=3 isFinal=false 2048B @ 16000Hz` en
   el chat — así confirmas que el audio capturado llega de verdad al otro lado,
   sin necesitar aún nada de speech-to-text.
5. Para probar la reproducción: manda cualquier mensaje desde la demo hacia el
   pinganillo (de momento no hay UI para mandar un `AudioMessage` de prueba
   desde la demo — si quieres probar esta dirección, pide que se añada un botón
   de "mandar tono de prueba" a `App.kt`).

## Primera configuración (WiFi de casa, opcional)

Si no hay WiFi de casa guardada, el pinganillo arranca en modo AP puro y expone
un portal en `http://192.168.4.1/`:

1. Desde el móvil/portátil, únete al WiFi `Pinganillo-Beam` (clave de fábrica).
2. Abre `http://192.168.4.1/` en el navegador (no hay redirección automática
   tipo "portal cautivo" — hay que visitarlo a mano, es una config de una sola vez).
3. Introduce el SSID/contraseña de tu WiFi de casa y guarda. El pinganillo
   reinicia solo y a partir de ahí arranca siempre en modo AP+STA con NAT.

Sin este paso, el pinganillo sigue funcionando perfectamente para hablar con
móvil/desktop — simplemente no reparte internet a través de su red.

## Cómo encaja con el lado Kotlin

- **Android**: se une al AP del pinganillo como red "solo local"
  (`AndroidPinganilloController`, `WifiNetworkSpecifier`) sin perder su ruta a
  internet por defecto (datos móviles), y llama a
  `BeamApplication.pairPinganillo(host, port)` — el pinganillo, al no tener
  todavía nadie vinculado, acepta el handshake sin pasos adicionales.
- **Desktop**: no se empareja nunca con el pinganillo directamente (solo con
  Android, por código — ver `PROJECT.md` §2.4); si tiene WiFi de casa
  configurada, el pinganillo le hace de pasarela NAT igualmente si Desktop se
  une a su AP por otros motivos, pero eso es incidental, no parte del flujo de
  emparejamiento.
- El pinganillo nunca inicia una conexión ni escucha beacons ajenos — solo
  anuncia el suyo y acepta. La reconexión tras el primer emparejamiento es
  automática (Android reconoce el `deviceId` como ya vinculado y activo).

## El handshake criptográfico YA está validado (sin hardware)

`native_test/` compila la MISMA lógica de `beam_crypto.cpp`/`beam_protocol.cpp`
(EC P-256, ECDH, ECDSA SHA-256, AES-256-GCM, SubjectPublicKeyInfo DER) como un
programa normal de macOS/Linux, y `beam/src/jvmTest/.../ManualHandshakeHarness.kt`
levanta un `MeshBeamConnection` real en la JVM. Ya se han hecho hablar entre sí
en local con éxito: handshake completo, fingerprint de pairing, mensaje cifrado
de ida y de vuelta descifrado correctamente. Esto confirma que el diseño
criptográfico (curva, formato DER, ECDH, AES-GCM, framing) es compatible con
Android/Desktop de verdad, no solo "debería serlo según el estándar".

Para reproducirlo tú mismo:
```bash
# Terminal 1 — arranca el lado Kotlin (usa el classpath de tu IDE, o compílalo
# con ./gradlew :beam:compileTestKotlinJvm y ejecútalo desde Android Studio con ▶)

# Terminal 2
cd esp32-pinganillo/native_test
brew install mbedtls@3 cjson   # OJO: mbedtls@3, NO mbedtls (la 4.x reestructura la API)
./build.sh
./handshake_test
```

Un detalle real que salió al probarlo, ya corregido: mbedTLS ≥3.0 renombra los
campos internos de sus structs (`grp`, `Q`, `d`...) tras la macro
`MBEDTLS_PRIVATE` salvo que definas `MBEDTLS_ALLOW_PRIVATE_ACCESS` antes de
incluir sus headers — ya está puesto en `beam_crypto.h` y en `handshake_test.cpp`.

## Lo que SIGUE sin poder probarse sin hardware real

- El AP+STA+NAT de verdad (necesita radios WiFi reales).
- El micrófono/altavoz I2S reales (ganancia, ruido, cableado).
- El botón físico (debounce, tiempos de pulsación larga/corta con un dedo real
  en vez de un GPIO simulado).
- `ip_napt_enable()` es la API clásica de lwIP para el NAT; si tu ESP-IDF ya
  trae el wrapper más nuevo `esp_netif_napt_enable()`, es equivalente.
- El portal de configuración WiFi es HTTP simple sin redirección DNS — no vas a
  ver el popup automático de "Iniciar sesión en la red" de iOS/Android, hay que
  visitar `192.168.4.1` a mano.
- `native_test/` valida el handshake y la vinculación automática (rama
  "acceptor sin nadie vinculado todavía") contra el `MeshBeamConnection` real de
  Kotlin — ver más abajo. NO valida el rechazo cuando ya hay alguien vinculado
  (`LinkStateChanged(DESVINCULADO)`) ni la persistencia en NVS real, que solo
  se pueden probar con hardware.
- Compílalo con `idf.py build` cuando tengas ESP-IDF instalado y revisa que no
  haya warnings — el propio `native_test` ya demostró que hace falta
  `MBEDTLS_ALLOW_PRIVATE_ACCESS`, pero ESP-IDF podría tener matices propios en
  su fork de mbedTLS que aquí no se ven.
