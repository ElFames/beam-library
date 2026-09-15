# UDIS / Aircom — Documento de arquitectura y producto

> Estado: v2 tras la conversación de rediseño que retira el pinganillo como wearable
> de audio y lo reduce a infraestructura de red pura (puente WiFi), abre la
> cardinalidad de Desktop, y separa el audio/cámara/pantalla de las gafas Ray-Ban
> Meta como integración de SDK a nivel de app (fuera de Aircom). Se irá actualizando
> a medida que el diseño evolucione — no es un documento congelado.

## 1. Visión general

UDIS es un asistente personal cuyo "cerebro" vive en el móvil (la IA del propio
dispositivo), y cuya "capa de memoria" es esta aplicación: le da al asistente
capacidades que un LLM no tiene de fábrica — recordar sitios, relaciones, acciones,
archivos e imágenes con contexto — funcionando en segundo plano y offline-first.

El **wearable de entrada/salida de audio** de esa IA ya no es el ESP32 — son las
**gafas Ray-Ban Meta**, consumidas directamente por la app móvil vía el SDK de Meta
(micrófono, altavoz, cámara y, si aplica, la propia pantalla de las gafas). Esta
integración vive **en la app, no en Aircom**: el SDK de Meta no habla el protocolo
de Aircom en ningún momento, es una capacidad más de la app móvil, igual que la
cámara del propio teléfono. Este documento no cubre su diseño (es responsabilidad
de la app UDIS, no de la librería de transporte).

El PC (Desktop) es **músculo extra**: recibe prompts/archivos del móvil y le
devuelve resultados, para tareas que el móvil no puede o no quiere hacer solo. A
diferencia de la v1, un móvil puede tener **cualquier número de Desktops
vinculados a la vez** (ver §2.5) — la limitación "solo un PC" era una invariante de
software, no una limitación de red, y se ha retirado.

El **ESP32 sigue en el hardware**, pero con un rol completamente distinto: ya no es
un wearable ni habla el protocolo Aircom — es un **puente de red WiFi puro**
(ver §3), útil solo cuando móvil y Desktop no comparten ninguna otra red (fuera de
casa/oficina). Es infraestructura transparente, no un peer.

**Aircom** (antes "Beam") es la librería KMP de transporte que conecta móvil y
Desktop — descubrimiento, cifrado, emparejamiento, mensajería — pero no sabe nada de
IA, memoria, ni del wearable; eso vive en la app UDIS que la consume.

> Nota de secuenciación: el renombrado Beam→Aircom (paquetes, namespace, artefacto
> Maven) se deja para un pase dedicado aparte, para no mezclar un rename mecánico
> de ~30 archivos con el resto del rediseño en el mismo cambio. Mientras tanto este
> documento ya usa el nombre "Aircom" para referirse a la librería, aunque el código
> siga en el paquete `com.nubax.beam.library`.

```
┌──────────────┐  gafas Ray-Ban Meta   ┌─────────────┐   prompts/archivos (Aircom)   ┌─────────────┐
│ Gafas (SDK    │◄──────────────────────│   UDIS       │◄────────────────────────────►│  UDIS        │
│ Meta, EN LA   │  fuera de Aircom       │   Android/   │   1:1 (Desktop) / N:1 (móvil) │  Desktop 1  │
│ APP, no aquí) │                        │   iOS        │───────────────────────────────│  Desktop 2  │
└──────────────┘                        │  (el "hub")  │        ...                     │  Desktop N  │
                                         └──────┬───────┘                                └─────────────┘
                                                │ sync sesión/credenciales/backup memoria
                                                ▼
                                         ┌──────────────┐
                                         │ Servidor nube │  (solo cuenta/backup — NUNCA
                                         └──────────────┘   transporte de Aircom, ver §3.3)

Puente de red (ESP32, opcional): un AP WiFi más, sin protocolo propio — móvil y
Desktop se unen a él exactamente como a cualquier otra WiFi cuando no comparten
ninguna otra red. Ver §3.
```

---

## 2. Aircom — máquina de estados de vinculación

Estado compartido por los dos lados (móvil, Desktop — **ya no hay un tercer lado**,
ver §3), tanto para el estado LOCAL de un dispositivo como para cada entrada de su
historial de peers:

| Estado         | Significado |
|----------------|-------------|
| `FABRICA`      | Nunca se ha emparejado (o se ha desvinculado y ha vuelto aquí). |
| `DESCUBRIENDO` | Visible/anunciándose, pero sin ningún intento de emparejamiento en curso. |
| `VINCULANDO`   | Handshake de emparejamiento en curso (código ya enviado, esperando confirmación). |
| `VINCULADO`    | Emparejado y activo. |
| `DESVINCULADO` | Se ha desvinculado explícitamente (por el usuario, o por propagación desde el otro lado). |

### 2.1 Modelo de datos: historial de dispositivos

Sustituye al `TrustStore` original (que solo guardaba "confío en este id"). Guarda
un **histórico** de todos los peers con los que se ha intentado/logrado vincular,
cada uno con su propio estado:

```kotlin
enum class LinkState { FABRICA, DESCUBRIENDO, VINCULANDO, VINCULADO, DESVINCULADO }
enum class PeerKind { DESKTOP, MOBILE }

data class LinkedDevice(
    val deviceId: String,
    val kind: PeerKind,
    val name: String,
    val publicKeyBase64: String,
    val state: LinkState,
    val active: Boolean,
)
```

**Invariante de `active` — asimétrica por diseño (ver §2.5):**
- **Móvil**: puede tener **N Desktops activos a la vez, sin límite**. Vincular un
  nuevo Desktop NUNCA desactiva a los anteriores.
- **Desktop**: como mucho **UN móvil activo** — cada Desktop solo puede estar
  vinculado con un móvil a la vez (esto no ha cambiado desde la v1).

Si llega un mensaje/handshake de un `deviceId` que SÍ está en el historial pero con
`active=false` (o no está en absoluto siendo el activo), se responde con el mensaje
de control `LinkStateChanged(state = DESVINCULADO)` en vez de tratarlo como un peer
válido. Quien lo recibe pone su propia entrada a `active=false, state=DESVINCULADO`.

### 2.2 Mensajes de control (Aircom)

Además de los mensajes de aplicación (chat, archivos), Aircom necesita estos
mensajes de protocolo, cifrados igual que cualquier otro mensaje pero con un `type`
reservado para que ambos lados los distingan del payload normal:

```kotlin
@Serializable
sealed class ControlMessage {
    @Serializable data class LinkStateChanged(val state: LinkState) : ControlMessage()
    @Serializable data class DesktopPairCodeSubmit(val code: String) : ControlMessage()
    @Serializable data class DesktopPairCodeResult(val success: Boolean) : ControlMessage()
}
```

### 2.3 Flujo de emparejamiento — Desktop (código)

Es el ÚNICO flujo de emparejamiento que existe en Aircom (el pinganillo por
credenciales WiFi se retiró junto con su rol de peer, ver §3):

1. Desktop, mientras no tenga ningún móvil `VINCULADO`, genera un código corto
   aleatorio (p. ej. 6 dígitos) y lo muestra de forma **continua** en su pantalla
   (*Devices*) — no espera ninguna solicitud previa para generarlo.
2. El móvil descubre el Desktop (beacon Aircom) y lo lista en *Devices*. Esto
   funciona exactamente igual si comparten la WiFi de casa, si comparten el
   hotspot del propio móvil, o si ambos se han unido al puente de red del §3 — el
   protocolo no distingue entre esos casos, es "una red más" en cualquiera.
3. El usuario lo selecciona, mira el código en la pantalla del Desktop, y lo
   teclea en el móvil.
4. El móvil manda `DesktopPairCodeSubmit(code)`. Si coincide con el que el Desktop
   está mostrando en ese momento, ambos pasan a `VINCULADO` (`active=true`);
   Desktop persiste el `deviceId` del móvil (desactivando cualquier móvil activo
   anterior — invariante 1:1), el móvil persiste el del Desktop (**sin** desactivar
   otros Desktops ya activos — invariante N:1, ver §2.1/§2.5).
5. **A partir de aquí, la reconexión es automática**: no se vuelve a pedir el
   código nunca más. En cuanto Desktop y móvil compartan red (por cualquiera de
   las vías del punto 2), el beacon/handshake normal de Aircom los reconecta solo
   porque cada uno ya tiene al otro como `active=true` en su historial.
6. Una vez vinculados, ambos pueden enviarse y recibirse texto y archivos
   libremente por el canal ya cifrado.

### 2.4 (retirado) Flujo de emparejamiento — Pinganillo

Existía un flujo de emparejamiento por credenciales WiFi específico para el
pinganillo (conocer el SSID/clave de su AP era la prueba de autorización, con
autovinculación al primer Android que completara el handshake). **Se ha retirado
por completo** junto con su rol de peer de Aircom — ver §3 para el porqué y en qué
se ha convertido ese hardware. `PairPinganillo`, `PeerKind.PINGANILLO` y todo el
código de acceptor asociado ya no existen en el repo.

### 2.5 Cardinalidad (resumen)

| Dispositivo | Puede vincularse con |
|---|---|
| Desktop     | 1 móvil (nunca más) |
| Móvil       | **N Desktops** (sin límite) |

Esto es un cambio deliberado sobre la v1 (que limitaba a 1 Desktop por móvil). La
razón por la que se puede abrir sin ningún riesgo de red: una misma red WiFi ya
admite muchos clientes a la vez de forma nativa — el límite anterior era una
invariante de software en `DeviceHistoryStore.link()`, no algo impuesto por la capa
de red. Ver `DeviceHistoryStore.kt` y su test `MeshBeamConnectionTest` (caso "un
móvil puede tener N Desktops activos a la vez").

**Fuera de alcance todavía**: emparejamiento móvil↔móvil (dos móviles vinculados
entre sí, compartiendo red). El razonamiento es simétrico y encajaría en el mismo
mecanismo, pero no hay ningún `PairingIntent` ni rama de protocolo para ese caso —
sería una extensión nueva, con su propia decisión de producto (¿mismo usuario, dos
dispositivos? ¿usuarios distintos?) antes de diseñarla.

### 2.6 Propagación de desvinculación

- El usuario desvincula un Desktop desde el móvil → el móvil marca esa entrada
  `active=false, state=DESVINCULADO` en su historial.
- El Desktop no lo sabe todavía: sigue mandando beacons/intentando hablar.
- En cuanto el Desktop contacta al móvil, el móvil responde
  `LinkStateChanged(DESVINCULADO)` en vez de tratarlo como peer válido.
- El Desktop recibe esto y se pone `state=DESVINCULADO` también.
- Mismo mecanismo simétrico en la otra dirección (Desktop desvincula al móvil).

### 2.7 Criptografía del canal

Cada dispositivo tiene una identidad estable: par de claves **EC P-256
(secp256r1)**, generado una vez y persistido (`DeviceIdentity`); `deviceId` es
`SHA-256(clave pública)[:16]` en hex.

El handshake, en cada conexión nueva:
1. Cada lado firma una clave **ECDH efímera nueva** (una por conexión, nunca
   reutilizada) con `SHA256withECDSA` usando su clave de identidad — así el otro
   lado verifica que la efímera viene de quien dice ser, sin que la identidad
   participe directamente en el cifrado.
2. Acuerdo de claves por **ECDH** sobre esas efímeras → forward secrecy real: si
   algún día se compromete la clave de identidad, las sesiones ya cerradas siguen
   siendo indescifrables porque las efímeras ya se descartaron.
3. Clave de sesión: `SHA-256(secreto_ECDH || constante_fija_de_dominio)` — la
   constante es pública e igual para todos (no es un salt aleatorio, es una
   etiqueta de separación de dominio).
4. Cifrado de mensajes: **AES-256-GCM**, IV aleatorio de 96 bits por mensaje, tag
   de 128 bits.

Implementación: `BeamCrypto`/`SecureChannel` (jvmCommon vía `java.security`/
`javax.crypto`; iOS vía CryptoKit por cinterop, `beam/native/ios/BeamCryptoKit.swift`).
Es equivalente en espíritu a TLS con ECDHE + autenticación por firma. El puente de
red del §3 **no participa de nada de esto** — no tiene identidad ni hace handshake.

---

## 3. Puente de red (firmware ESP32, C++) — antes "pinganillo"

> Cambio de rol respecto a la v1 de este documento: el ESP32 ya no es un wearable
> de audio ni un peer de Aircom. El wearable de audio/cámara/pantalla ahora son las
> **gafas Ray-Ban Meta**, integradas directamente en la app móvil vía el SDK de
> Meta — eso vive en la app, no aquí, y no se documenta en este repo. El ESP32 se
> ha reducido a lo que sigue: infraestructura de red pura. Código en
> [`esp32-bridge/`](./esp32-bridge) (antes `esp32-pinganillo/`).

### 3.1 Por qué existe y cuándo se usa

Aircom descubre y empareja por beacon UDP + handshake TCP — funciona sin ningún
código especial siempre que móvil y Desktop **compartan la misma red WiFi** (ver
§2.3). El problema es fuera de casa/oficina, cuando no hay ninguna red que
compartir: no hay relay en la nube (Aircom es P2P, 100% offline — nunca pasa por
un servidor, ver §6) y las tecnologías de "WiFi P2P sin router" de cada fabricante
(WiFi Direct de Android, AWDL/Multipeer de Apple) son **incompatibles entre sí** —
un Android no puede hablar WiFi Direct con un iPhone ni al revés, por diseño de
cada fabricante, no por falta de una librería.

La solución que sí cruza cualquier combinación de plataformas es la más antigua y
aburrida: un **punto de acceso WiFi normal** (modo estación 802.11, no ningún
protocolo P2P) al que cualquiera se une como a cualquier WiFi. Eso es exactamente
lo que hace este dispositivo — nada más.

**Se evaluó y se descartó** que además repartiera el internet del móvil recibido
por Bluetooth (tethering PAN): solo el ESP32 clásico tiene Bluetooth Classic real
(los C3/S3, que sí tienen WPA3 bien soportado, son BLE-only), y ESP-IDF no trae el
perfil PAN/BNEP implementado de fábrica — habría que escribirlo desde cero sobre
L2CAP. Por eso este firmware es **solo WiFi, sin ninguna pieza de Bluetooth**. Si
el Desktop no tiene WiFi de casa configurada en el puente (§3.3) ni conexión por
Ethernet, se queda sin internet general mientras lo use — límite aceptado de ir
100% offline (ver §6).

### 3.2 Qué hace (y qué YA NO hace)

1. Levanta su propio AP con SSID/contraseña **únicos de fábrica** (tarjeta física
   tipo SIM, igual que el pinganillo original) y **WPA3-SAE + PMF (802.11w)
   obligatorio** — más seguro que la mayoría de routers domésticos, que siguen en
   WPA2 sin PMF. Ver `esp32-bridge/main/wifi_setup.cpp`.
2. Si además se le configura la WiFi de casa/oficina (portal HTTP en
   `192.168.4.1`, sin cambios respecto al diseño original), se conecta a ella
   como cliente y hace NAT: quien se une a su AP recibe internet de forma
   transparente.
3. Nada más. **No tiene identidad criptográfica, no hace handshake, no aparece en
   ningún historial de vinculación, no interpreta el protocolo Aircom.** Se ha
   retirado por completo: `beam_crypto.cpp`, `beam_protocol.cpp`, el micrófono/
   altavoz I2S, el botón de 3 estados, y toda la máquina de estados de
   vinculación local que tenía la v1 de este documento. Móvil y Desktop se hablan
   entre ellos exactamente igual que si compartieran cualquier otra WiFi.

### 3.3 Seguridad reforzada (más allá de lo habitual)

Pedido explícitamente para este dispositivo — "más seguro que un router normal":

- **WPA3-SAE + PMF obligatorio** en el AP (§3.2) — cierra el vector de deauth que
  sí funciona contra la mayoría de APs domésticos.
- **Secure Boot V2 + Flash Encryption** recomendados, pero **deliberadamente NO
  activados por defecto** en `sdkconfig.defaults` — son operaciones irreversibles
  sobre eFuses del chip (un fallo de configuración inutiliza la unidad para
  siempre). Se activan a mano, unidad por unidad, siguiendo la guía oficial de
  Espressif — nunca como un default que se aplique sin darse cuenta.
- Chip recomendado: **ESP32-S3** (WPA3 + aceleración cripto en hardware para el
  Secure Boot/Flash Encryption de arriba, y hay módulos ultra pequeños — Seeed
  XIAO ESP32-S3, Adafruit QT Py ESP32-S3 — que caben en un colgante/pulsera). No
  hace falta Bluetooth Classic con este diseño (§3.1), así que no hay motivo para
  usar el ESP32 clásico.
- No existe hoy un producto comercial en formato colgante/pulsera que haga esto —
  lo más cercano son los routers de viaje de bolsillo (GL.iNet Mango y similares),
  de ahí que se construya a medida.

### 3.4 Identidad de fábrica

Cada unidad física, al flashear, recibe SSID/contraseña de su AP WiFi **únicos por
unidad**, entregados al usuario en una tarjeta física impresa (mismo modelo que
una SIM). A diferencia del pinganillo original, **no hay `deviceId` ni keypair
criptográfica que grabar** — este dispositivo no tiene identidad de red en el
sentido de Aircom, solo una red WiFi con nombre y clave.

---

## 4. UDIS (app KMP que consume Aircom)

### 4.1 Pantalla Chat (común)

Chat de texto con UDIS — sirve tanto como interfaz principal en Desktop como de
*fallback* en el móvil cuando las gafas no están disponibles (sin batería, fuera
de alcance): puedes escribir en vez de hablar y entra por el mismo canal.

### 4.2 Pantalla Historial (común)

Registro de todas las interacciones con UDIS y las acciones que se han ejecutado a
raíz de ellas (auditoría/memoria de "qué hice y cuándo").

### 4.3 Pantalla Devices (varía por plataforma)

- **Desktop**: si no tiene ningún móvil vinculado, muestra el código de
  emparejamiento cuando se solicita (popup); si ya está vinculado, muestra el
  móvil con el que lo está.
- **Móvil**: lista de Desktops descubiertos (pueden ser varios, ver §2.5). Al
  seleccionar uno, pide el código que se está mostrando en su pantalla — es la
  única posibilidad de emparejamiento que existe. Si no comparte red con ningún
  Desktop (fuera de casa/oficina), aquí también se ofrece unirse al puente de
  red del §3 (SSID/clave de su tarjeta) antes de que aparezca nada que emparejar.

### 4.4 Pantalla Ajustes

Inicio de sesión y gestión de cuenta — la app guarda mucho dato de usuario en
servidor (histórico, memoria), así que hace falta sesión para el backup/sync.
Funciona **offline-first**: todo lo local sigue funcionando sin conexión, la nube
es solo respaldo/sincronización.

### 4.5 Pantalla "Drive" (archivos/imágenes)

Subida manual de archivos/imágenes (sync de galería queda para más adelante). Cada
archivo subido:
- Si es una imagen, la IA genera una descripción para poder buscarla luego por
  contenido ("como si tuviera tu misma memoria").
- Puede pedirse que se transfiera entre Android ↔ Desktop a través de Aircom (el
  mismo mecanismo de `MediaMessage` que ya existe), tanto archivos que subas tú
  como los que genere la propia IA.

---

## 5. Capas de memoria (propuesta inicial — abierto a revisión)

Las que ya mencionaste, más algunas sugerencias adicionales a valorar:

**Ya definidas por ti:**
- **Ubicaciones**: sitios donde has estado / sitios relevantes.
- **Relaciones**: árbol genealógico / círculo de amigos, con nombre + info libre
  por persona.
- **Acciones**: cosas que has hecho (histórico de intenciones ejecutadas).
- **Archivos/imágenes con descripción generada por IA**, buscables por contenido.

**Sugerencias a valorar:**
- **Preferencias/gustos**: comida, marcas, aficiones — información que hoy
  repetirías cada vez que hablas con un LLM genérico.
- **Rutinas/hábitos temporales**: "los martes suelo...", para dar contexto
  temporal sin que se lo tengas que recordar cada vez.
- **Entidades/objetos propios**: tus dispositivos, vehículo, documentos
  importantes — un espacio intermedio entre "relaciones" (personas) y "archivos"
  (contenido).
- **Estado emocional/notas libres**: un diario mínimo, útil si más adelante
  quieres que UDIS tenga contexto de cómo has estado, no solo qué has hecho.

No hace falta cerrarlas todas ahora — se pueden ir añadiendo como colecciones
independientes en el backend sin tocar Aircom (esto vive por encima del transporte).

---

## 6. Servidor en la nube

Alcance: sesiones/credenciales de usuario y backup de la memoria (capas del §5).

- `users`: cuenta, sesión.
- Backup de las capas de memoria (§5).

**Nunca** transporte ni intermediario de Aircom — el emparejamiento y la mensajería
entre móvil y Desktop son P2P, 100% offline, y así se ha mantenido deliberadamente
tras evaluar (y descartar) un relay en la nube como solución al problema de "no
comparten red": rompería el requisito de que el protocolo funcione sin depender de
internet en ningún momento. Ver §3.1 para la solución que sí se adoptó (el puente
de red).

> Nota histórica: la v1 de este documento tenía aquí el contrato de registro de
> propiedad del pinganillo (`deviceId → ssid/password`, para recuperar credenciales
> perdidas). Ya no aplica — el puente de red (§3) no tiene `deviceId` ni identidad
> que registrar. Si se quiere poder recuperar el SSID/clave de una unidad perdida,
> haría falta decidir algún otro identificador (p. ej. un número de serie impreso
> en la tarjeta) — no se ha diseñado, queda pendiente si se necesita.

No existe código de servidor en este repo todavía; esta sección es el contrato a
implementar cuando se aborde el backend.

---

## 7. Qué se aborda ya vs qué queda para después

**Hecho y verificado (Kotlin, con tests reales — no solo compilado):**
- ✅ `PeerKind.PINGANILLO` retirado del enum — solo `DESKTOP`/`MOBILE`.
- ✅ `DeviceHistoryStore.link()` abierto a **N Desktops activos por móvil** (antes
  1), manteniendo 1 móvil activo por Desktop. Test nuevo en `MeshBeamConnectionTest`
  que lo verifica end-to-end (dos Desktops, un móvil, ninguno se desvincula al
  vincular al otro).
- ✅ Todo el código de emparejamiento/acceptor del pinganillo retirado de
  `MeshBeamConnection` e `IosMeshBeamConnection` (`PairingIntent.PinganilloCredentials`,
  `pairPinganillo`, la rama de auto-link `myKind == PINGANILLO`).
- ✅ `AndroidPinganilloController`/`DesktopPinganilloController`/`PinganilloController`
  renombrados a `*NetworkBridgeController` — mismo mecanismo (red "solo local" en
  Android vía `WifiNetworkSpecifier`, join directo en Desktop vía `networksetup`),
  ahora con SSID/clave tecleados por el usuario en vez de un default fijo
  (`PinganilloDefaults` eliminado). Android usa `setWpa3Passphrase` (antes WPA2).
- ✅ `AudioMessage` y `ManualHandshakeHarness` retirados (sin uso: nadie manda audio
  por Aircom, y el harness solo validaba el handshake contra el firmware del
  pinganillo, que ya no existe).
- ✅ Demo (`composeApp`) actualizada: sin botón de "vincular pinganillo" con SSID
  fijo, ahora un campo SSID/clave genérico para el puente de red; sin la rama de
  audio del pinganillo en el chat de la demo.

**Hecho, escrito pero SIN poder probarse aquí (firmware C++, sin hardware/toolchain):**
- `esp32-bridge/` (antes `esp32-pinganillo/`): firmware reducido a AP+STA+NAT puro,
  sin ninguna pieza de Aircom/cripto/audio. WPA3-SAE + PMF en el AP. Ver el propio
  README para qué sigue sin poder probarse sin hardware real (AP+STA+NAT de
  verdad, y confirmar los nombres exactos de campo/constante de WPA3 contra la
  versión de ESP-IDF en uso).

**Todavía no empezado:**
- Pantallas UDIS: Chat, Historial, Devices, Ajustes, Drive (la app en sí, más
  allá de la demo de `composeApp`).
- `NetworkBridgeController` en iOS (unirse al puente sin perder la ruta a internet
  por defecto — necesita `NEHotspotConfiguration` + atar el socket a esa interfaz
  con `Network.framework`, ninguna de las dos cosas está escrita).
- Integración del SDK de Ray-Ban Meta en la app móvil (micrófono/altavoz/cámara/
  pantalla) — vive en la app, no en Aircom; no se ha empezado ni se documenta aquí.

**Deliberadamente fuera de esta fase:**
- Emparejamiento móvil↔móvil (§2.5).
- Backend real (backup de memoria) — solo el contrato, no la implementación.
- Reparto de internet del móvil al puente de red vía Bluetooth — evaluado y
  descartado (§3.1): incompatibilidad WPA3/Bluetooth-Classic entre chips ESP32 y
  PAN/BNEP no soportado de fábrica en ESP-IDF.
- Sync automática de galería.
- Las capas de memoria como funcionalidad de IA real (esto es backend/producto, no Aircom).
- Rename Beam→Aircom de paquetes (pase aparte).

## 8. Decisiones ya cerradas (histórico de la conversación de diseño)

1. ✅ El ESP32 deja de ser wearable/peer de Aircom — pasa a ser infraestructura de
   red pura ("puente de red"), sin crypto ni protocolo propio (§3).
2. ✅ El wearable de audio/cámara/pantalla pasa a ser las gafas Ray-Ban Meta,
   integradas por SDK directamente en la app móvil — fuera del alcance de Aircom.
3. ✅ Cardinalidad Desktop abierta a N por móvil (antes 1) — invariante de
   software, sin implicación de red (§2.5).
4. ✅ Protocolo 100% offline confirmado como requisito duro: sin relay en la nube
   como solución al problema de "no comparten red" (§3.1, §6) — el límite
   aceptado es que Aircom solo funciona con proximidad real (misma red WiFi, o el
   puente cuando no la hay).
5. ✅ Reparto de internet del móvil al puente vía Bluetooth evaluado y descartado
   por incompatibilidad de hardware/SDK (§3.1) — el puente es solo-WiFi.
6. ✅ Seguridad del puente reforzada: WPA3-SAE + PMF obligatorio; Secure Boot/Flash
   Encryption recomendados pero no forzados por defecto (irreversibles) (§3.3).
7. ✅ Código de Desktop: se genera y muestra de forma continua mientras no tenga
   ningún móvil vinculado; el móvil lo envía directamente con
   `DesktopPairCodeSubmit`, sin paso de solicitud previo (§2.3).
