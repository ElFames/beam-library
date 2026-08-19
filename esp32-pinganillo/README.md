# Pinganillo (firmware ESP32)

Firmware en C++ (ESP-IDF) que convierte un ESP32 + micrófono I2S + altavoz I2S en
un nodo más de la red **Beam**, hablando el mismo protocolo que usan Android y
Desktop (`beam/src/commonMain/.../connection/MeshBeamConnection.kt`): mismo
beacon UDP, mismo handshake TCP, mismo cifrado. Desde el punto de vista de Beam
el pinganillo es "un peer más", no un dispositivo especial.

## Qué hace

1. Levanta su propio WiFi (AP) con SSID/contraseña **fijos de fábrica** — así
   móvil y desktop pueden unirse sin que el pinganillo tenga pantalla.
2. Si además se le ha configurado la WiFi de casa (una vez, ver más abajo),
   también se conecta a ella como cliente y hace NAT: quien se una a su AP
   recibe internet de forma transparente.
3. Captura audio del micrófono y lo manda cifrado, en trozos, a todos los
   peers Beam conectados (móvil/desktop) para que allí se pase a texto.
4. Si le llega audio de un peer, lo reproduce por el altavoz.

## Credenciales por defecto — OJO, deben coincidir con el lado Kotlin

`main/config.h`:
```c
#define PINGANILLO_AP_SSID     "Pinganillo-Beam"
#define PINGANILLO_AP_PASSWORD "beam12345"
```
Tienen que ser **exactamente** las mismas que
`PinganilloDefaults.AP_SSID` / `AP_PASSWORD` en
`beam/src/commonMain/kotlin/com/nubax/beam/library/connectivity/PinganilloDefaults.kt`.
Son dos repos/lenguajes distintos — si cambias una, cambia la otra a mano.

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

Si el audio sale saturado o demasiado bajo, ajusta el desplazamiento de 32→16
bits en `audio.cpp` (`raw[i] >> 14`) — depende de la ganancia del mic concreto.

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
  internet por defecto (datos móviles).
- **Desktop**: se une directamente (`DesktopPinganilloController`, vía
  `networksetup`) porque ahí el pinganillo hace de pasarela NAT si tiene WiFi
  de casa configurada.
- Una vez todos en la misma red, el discovery normal de Beam (beacon UDP +
  handshake TCP) hace el resto sin código adicional — el pinganillo es
  indistinguible de cualquier otro peer para el protocolo.

## Limitaciones conocidas / pendiente de verificar en hardware real

Este firmware se ha escrito replicando byte a byte el protocolo criptográfico
de `BeamCrypto.kt`/`SecureChannel.kt` (EC P-256, ECDH, ECDSA SHA-256, AES-256-GCM,
SubjectPublicKeyInfo DER para las claves públicas), pero **no se ha podido
compilar ni probar contra hardware real** en este entorno (no hay toolchain de
ESP-IDF disponible aquí). Antes de darlo por bueno:

- Compílalo con `idf.py build` y revisa que no haya warnings de tipos en las
  llamadas a mbedTLS (las firmas exactas varían un poco entre mbedTLS 2.x y 3.x
  — este código está escrito para mbedTLS 3.x, que es lo que trae ESP-IDF 5.x).
- Verifica en el primer emparejamiento real que el `fingerprint` que muestra la
  app en el móvil/desktop tiene sentido (si el ECDH o el parseo de claves
  fallara silenciosamente, el síntoma sería "nunca completa el handshake" o
  "descifra basura", no un crash).
- `ip_napt_enable()` (NAT) es la API clásica de lwIP para esto; si tu ESP-IDF ya
  trae el wrapper más nuevo `esp_netif_napt_enable()`, es equivalente.
- El portal de configuración WiFi es HTTP simple sin redirección DNS — no vas a
  ver el popup automático de "Iniciar sesión en la red" de iOS/Android, hay que
  visitar `192.168.4.1` a mano.
- No hay detección de fin de frase (VAD): el audio se manda en un stream
  continuo desde el arranque (`isFinal` siempre `false`). Trocear en
  frases/utterances queda del lado de quien escucha (móvil/desktop).
