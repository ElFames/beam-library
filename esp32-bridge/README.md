# Puente de red (firmware ESP32)

Firmware en C++ (ESP-IDF) que convierte un ESP32 en un **puente de red WiFi puro**
para Aircom: levanta su propio punto de acceso (AP) al que se unen el móvil y
cualquier número de Desktops cuando no comparten ninguna otra red (fuera de casa,
fuera de la oficina), y opcionalmente hace de pasarela NAT hacia una WiFi de casa
si la tiene configurada. Ver `PROJECT.md` (raíz del repo) §3 para el diseño
funcional completo y el porqué de este cambio de rol.

## Qué hace (y qué YA NO hace)

Este dispositivo era antes el "pinganillo" — un wearable de audio que hablaba el
protocolo criptográfico de Aircom como un peer más (acceptor). **Eso se ha
retirado por completo**: sin micrófono, sin altavoz, sin botón, sin identidad
criptográfica, sin handshake, sin aparecer en ningún historial de vinculación.
Hoy es exactamente lo que sería un router de viaje comercial, solo que en
miniatura:

1. Levanta su propio WiFi (AP) con SSID/contraseña **únicos de fábrica** (como
   una tarjeta SIM), con **WPA3-SAE + PMF (802.11w) obligatorio** — ver
   `wifi_setup.cpp` para el detalle exacto y el aviso de qué comprobar contra
   tu versión de ESP-IDF antes de flashear una unidad real.
2. Si además se le ha configurado la WiFi de casa/oficina (una vez, ver más
   abajo), también se conecta a ella como cliente y hace NAT: quien se una a
   su AP recibe internet de forma transparente.
3. Nada más. No interpreta ni conoce el protocolo Aircom — móvil y Desktop
   hablan entre ellos exactamente igual que si compartieran cualquier otra
   WiFi (el mismo beacon UDP + handshake TCP de siempre). El puente es
   transparente para ese protocolo, no participa en él.

## Por qué no reparte también el internet del móvil por Bluetooth

Se evaluó (ver la conversación de diseño en `PROJECT.md`) que el móvil compartiera
su conexión al puente por Bluetooth Classic (perfil PAN), para no depender de
que el ESP32 tenga WiFi de casa configurada. Se descartó por dos motivos:

- Solo el ESP32 **clásico** (no los C3/S3, que son BLE-only) tiene Bluetooth
  Classic real — y ese chip no tiene el mismo nivel de soporte WPA3 verificado.
- ESP-IDF no trae el perfil PAN/BNEP implementado de fábrica — habría que
  escribirlo desde cero sobre L2CAP, un proyecto de bajo nivel en sí mismo.

Por eso este firmware es **solo WiFi**, sin ninguna pieza de Bluetooth. Si el
Desktop necesita internet mientras usa el puente y no tiene WiFi de casa
configurada en él, se queda sin internet general (igual que ya asumimos como
límite aceptable de ir 100% offline) — Aircom en sí sigue funcionando igual.

## Credenciales — únicas por unidad, como una SIM

`main/config.h` trae unos valores de ejemplo para desarrollo:
```c
#define BRIDGE_AP_SSID     "Aircom-Bridge"
#define BRIDGE_AP_PASSWORD "cambia-esto-por-unidad"
```
Para una unidad física real: **antes de compilar/flashear cada puente**, cambia
estos dos valores a algo único para esa unidad concreta. Al no haber identidad
criptográfica ni `deviceId` en este dispositivo (ya no es un peer de Aircom),
la tarjeta física que acompaña a la unidad solo necesita llevar SSID + clave.

## Construir y flashear

Necesitas [ESP-IDF](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/get-started/) instalado (v5.x recomendado).

```bash
cd esp32-bridge
idf.py set-target esp32
idf.py build
idf.py -p /dev/tty.usbserial-XXXX flash monitor
```

Chip recomendado: **ESP32-S3** (WPA3 + BLE, aceleración cripto en hardware para
Secure Boot/Flash Encryption, y hay módulos ultra pequeños — Seeed XIAO ESP32-S3,
Adafruit QT Py ESP32-S3 — que caben en un colgante/pulsera). No se necesita
Bluetooth Classic en absoluto con este diseño (ver más arriba), así que no hay
motivo para usar el ESP32 clásico.

## Endurecimiento de seguridad recomendado (no activado por defecto)

- **WPA3-SAE + PMF obligatorio**: ya en `wifi_setup.cpp` — confirma los nombres
  exactos de campo/constante contra tu versión de ESP-IDF antes de dar esto
  por cerrado.
- **Secure Boot V2 + Flash Encryption**: deliberadamente NO están en
  `sdkconfig.defaults` — son operaciones irreversibles sobre eFuses del chip
  (un fallo de configuración inutiliza la unidad para siempre). Se activan a
  mano, unidad por unidad, siguiendo la guía oficial de Espressif, nunca como
  un default que se aplique sin darte cuenta.

## Primera configuración (WiFi de casa, opcional)

Si no hay WiFi de casa guardada, el puente arranca en modo AP puro y expone
un portal en `http://192.168.4.1/`:

1. Desde el móvil/portátil, únete al WiFi del puente (SSID/clave de su tarjeta).
2. Abre `http://192.168.4.1/` en el navegador (no hay redirección automática
   tipo "portal cautivo" — hay que visitarlo a mano, es una config de una sola vez).
3. Introduce el SSID/contraseña de tu WiFi de casa/oficina y guarda. El puente
   reinicia solo y a partir de ahí arranca siempre en modo AP+STA con NAT.

Sin este paso, el puente sigue funcionando perfectamente para que móvil y
Desktop se hablen entre sí — simplemente no reparte internet a través de su red.

## Cómo encaja con el lado Kotlin

- **Android**: se une al AP del puente como red "solo local"
  (`AndroidNetworkBridgeController`, `WifiNetworkSpecifier`) sin perder su ruta
  a internet por defecto (datos móviles) — el SSID/clave se teclean una vez,
  igual que unirse a cualquier WiFi nueva.
- **Desktop**: se une directamente con `networksetup` (macOS) — al no tener
  colchón de datos móviles, si el puente no reparte internet (paso opcional de
  arriba) el Desktop se queda sin internet general mientras esté unido, algo
  que si tiene conexión por Ethernet no le afecta en absoluto (ver PROJECT.md §3).
- **Aircom, en ambos casos, no sabe que existe este dispositivo**: descubre y
  empareja exactamente igual que en cualquier WiFi doméstica.
