# UDIS / Aircom — Documento de arquitectura y producto

> Estado: v3. La v2 había reducido el ESP32 a un "puente de red" (infraestructura
> WiFi pura, sin protocolo propio). Esta versión lo retira **por completo** — ni
> firmware, ni hardware, ni código Kotlin — al confirmar que el mismo problema (no
> compartir red fuera de casa/oficina) ya lo resuelve el propio móvil compartiendo
> su conexión (hotspot local en Android, Hotspot personal en iOS), sin ningún
> dispositivo externo. Se irá actualizando a medida que el diseño evolucione — no
> es un documento congelado.

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

El **ESP32 se ha retirado por completo** — no queda hardware en el diseño más allá
de las gafas. Cuando móvil y Desktop no comparten ninguna red (fuera de
casa/oficina), es el propio **móvil quien comparte su conexión** con el Desktop
(ver §3) — sin ningún dispositivo externo de por medio.

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
                                         └──────────────┘   transporte de Aircom, ver §6)

Sin red compartida (fuera de casa/oficina): el propio móvil comparte su conexión
con el Desktop — sin ESP32, sin ningún dispositivo intermedio. Ver §3.
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
   funciona exactamente igual si comparten la WiFi de casa o si comparten la
   conexión que el propio móvil comparte con el Desktop (§3) — el protocolo no
   distingue entre esos casos, es "una red más" en cualquiera.
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
por completo** junto con el propio hardware — ver §3 para el porqué y para la
solución que lo sustituye. `pairPinganillo`, `PeerKind.PINGANILLO` y todo el
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
Es equivalente en espíritu a TLS con ECDHE + autenticación por firma.

---

## 3. Compartir red sin router (sin hardware) — antes el "puente ESP32"

> Historial de esta sección, porque vale la pena que quede escrito: la v1 de este
> documento tenía aquí un wearable de audio ESP32 (el "pinganillo"), hablando el
> protocolo Aircom como un peer más. La v2 lo redujo a un "puente de red" — un AP
> WiFi sin protocolo propio, solo para los casos sin red compartida. Esta v3 lo
> **retira del todo** — ni firmware, ni hardware, ni código Kotlin — al confirmar
> que el propio móvil ya resuelve el mismo problema sin ningún dispositivo externo.
> El wearable de audio/cámara/pantalla es hoy las **gafas Ray-Ban Meta**, integradas
> por SDK directamente en la app — fuera de Aircom, no se documenta aquí.

### 3.1 El problema que había que resolver

Aircom descubre y empareja por beacon UDP + handshake TCP — funciona sin ningún
código especial siempre que móvil y Desktop **compartan la misma red WiFi** (ver
§2.3). El problema es fuera de casa/oficina, cuando no hay ninguna red que
compartir: no hay relay en la nube (Aircom es P2P, 100% offline — nunca pasa por
un servidor, ver §6) y las tecnologías de "WiFi P2P sin router" de cada fabricante
(WiFi Direct de Android, AWDL/Multipeer de Apple) son **incompatibles entre sí** —
un Android no puede hablar WiFi Direct con un iPhone ni al revés, por diseño de
cada fabricante, no por falta de una librería.

Se evaluaron y descartaron, en orden: un relay en la nube (rompe el requisito
100% offline), un dispositivo ESP32 propio haciendo de AP neutral (funcionaba,
pero añadía hardware, fabricación, logística de tarjetas SIM-like — innecesario
si hay una vía sin hardware), y repartir el internet del móvil hacia ese ESP32
por Bluetooth (descartado por incompatibilidad WPA3/Bluetooth-Classic entre
chips y por que ESP-IDF no trae el perfil PAN/BNEP de fábrica).

### 3.2 La solución: el móvil comparte su propia conexión

No hace falta ningún dispositivo intermedio — **el móvil ya puede compartir su
conexión con el Desktop directamente**, con mecanismos nativos de cada plataforma:

- **Android**: `WifiManager.startLocalOnlyHotspot()` — la app tiene un botón
  ("Activar hotspot local") que levanta un hotspot temporal con SSID/clave
  aleatorios. **No comparte internet a propósito** (restricción deliberada de
  Android para este API) — solo sirve para que el Desktop se una y compartan red
  para Aircom, no para darle internet general al Desktop.
- **iOS**: **Hotspot personal** (Ajustes → Hotspot personal, o el Centro de
  Control) — activado a mano por el usuario, **no por la app**: Apple no expone
  ninguna API para que una app de terceros lo active. A diferencia del de Android,
  este **sí comparte internet real** (datos móviles) con quien se una.
- En ambos casos, el Desktop se une a esa red exactamente igual que a cualquier
  WiFi (`WifiJoiner`/`DesktopWifiJoiner`, ya construido) — tecleando el SSID/clave
  que el móvil muestra en pantalla. En cuanto están en la misma red, el beacon y
  el handshake de Aircom funcionan exactamente igual que en cualquier WiFi, sin
  ningún código adicional.

La diferencia entre plataformas es solo **quién pulsa qué botón** (la app en
Android, Ajustes del sistema en iOS) — no una diferencia de capacidad real: las
dos consiguen el mismo resultado (móvil y Desktop en la misma red), y por eso ya
no hace falta ningún hardware externo para cubrir ambas.

### 3.3 Limitaciones aceptadas

- **Android**: el Desktop no recibe internet a través del hotspot local (es
  "solo local" por diseño de la API) — si lo necesita y no tiene Ethernet, se
  queda sin internet general mientras dura la sesión, igual que ya asumíamos
  como límite de ir 100% offline.
- **iOS**: sí hay internet de propina (Hotspot personal comparte datos móviles
  de verdad), pero activar y compartir el SSID/clave es un paso manual del
  usuario en Ajustes, no algo que la app pueda automatizar.
- Sigue aplicando el límite de fondo ya aceptado: **proximidad real** — esto
  nunca sustituye a "estar en el mismo sitio", solo evita depender de que haya
  ya una red de por medio (router de casa/oficina).

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
  Desktop (fuera de casa/oficina), aquí también se ofrece compartir la conexión
  del propio móvil (§3: hotspot local en Android; en iOS, instrucciones para
  activar el Hotspot personal en Ajustes) antes de que aparezca nada que emparejar.

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
internet en ningún momento. Ver §3 para la solución que sí se adoptó (el móvil
comparte su propia conexión, sin ningún servidor ni hardware de por medio).

No existe código de servidor en este repo todavía; esta sección es el contrato a
implementar cuando se aborde el backend.

---

## 7. Qué se aborda ya vs qué queda para después

**Hecho y verificado (Kotlin, con tests reales — no solo compilado):**
- ✅ `PeerKind.PINGANILLO` retirado del enum — solo `DESKTOP`/`MOBILE`.
- ✅ `DeviceHistoryStore.link()` abierto a **N Desktops activos por móvil** (antes
  1), manteniendo 1 móvil activo por Desktop. Test en `MeshBeamConnectionTest` que
  lo verifica end-to-end (dos Desktops, un móvil, ninguno se desvincula al vincular
  al otro).
- ✅ Todo el código de emparejamiento/acceptor del pinganillo retirado de
  `MeshBeamConnection` e `IosMeshBeamConnection`.
- ✅ **ESP32 retirado del todo, en una segunda pasada** — no solo el firmware: se
  eliminó también `NetworkBridgeController`/`AndroidNetworkBridgeController`/
  `DesktopNetworkBridgeController`, `NetworkSocketBinder`/`JvmNetworkSocketBinder`,
  y `attachNetwork`/`detachNetwork` de `BeamConnection`/`BeamApplication`/
  `MeshBeamConnection`/`IosMeshBeamConnection` — quedaban muertos en cuanto se
  confirmó que el móvil compartiendo su propia conexión (§3) cubre lo mismo sin
  hardware. El directorio `esp32-bridge/` (y antes `esp32-pinganillo/`) ya no
  existe en el repo.
- ✅ `AudioMessage` y `ManualHandshakeHarness` retirados (sin uso).
- ✅ Demo (`composeApp`) rediseñada: logs/dispositivos/chat en secciones con
  borde, flujo principal reordenado, y las opciones de red que no son el camino
  normal (hotspot de reencuentro, IP manual) escondidas detrás de un desplegable
  "Opciones avanzadas de red", plegado por defecto.

**Todavía no empezado:**
- Pantallas UDIS: Chat, Historial, Devices, Ajustes, Drive (la app en sí, más
  allá de la demo de `composeApp`).
- Integración del SDK de Ray-Ban Meta en la app móvil (micrófono/altavoz/cámara/
  pantalla) — vive en la app, no en Aircom; no se ha empezado ni se documenta aquí.
- Instrucciones en la UI para el flujo manual de Hotspot personal en iOS (§3.2) —
  hoy el mecanismo ya funciona (es una WiFi normal a la que el Desktop se une),
  pero falta la pantalla que se lo explique al usuario paso a paso.

**Deliberadamente fuera de esta fase:**
- Emparejamiento móvil↔móvil (§2.5).
- Backend real (backup de memoria) — solo el contrato, no la implementación.
- Sync automática de galería.
- Las capas de memoria como funcionalidad de IA real (esto es backend/producto, no Aircom).
- Rename Beam→Aircom de paquetes (pase aparte).

## 8. Decisiones ya cerradas (histórico de la conversación de diseño)

1. ✅ El wearable de audio/cámara/pantalla pasa a ser las gafas Ray-Ban Meta,
   integradas por SDK directamente en la app móvil — fuera del alcance de Aircom.
2. ✅ Cardinalidad Desktop abierta a N por móvil (antes 1) — invariante de
   software, sin implicación de red (§2.5).
3. ✅ Protocolo 100% offline confirmado como requisito duro: sin relay en la nube
   como solución al problema de "no comparten red" (§6) — el límite aceptado es
   que Aircom solo funciona con proximidad real.
4. ✅ **ESP32 retirado del todo** (no solo reducido a "puente de red" como decía la
   v2): el móvil compartiendo su propia conexión (hotspot local en Android,
   Hotspot personal manual en iOS) cubre el mismo problema sin ningún hardware
   externo — ver §3 para el porqué y las dos rondas de evaluación que llevaron
   hasta aquí (relay en la nube → puente ESP32 → Bluetooth PAN → esta solución).
5. ✅ Código de Desktop: se genera y muestra de forma continua mientras no tenga
   ningún móvil vinculado; el móvil lo envía directamente con
   `DesktopPairCodeSubmit`, sin paso de solicitud previo (§2.3).
