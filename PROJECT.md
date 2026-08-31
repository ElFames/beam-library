# UDIS / Aircom — Documento de arquitectura y producto

> Estado: primera versión completa del diseño funcional + técnico, escrita a partir de
> la conversación de diseño. Sirve de referencia para implementar Aircom Desktop,
> Aircom Android y el firmware del pinganillo (C++). Se irá actualizando a medida que
> el diseño evolucione — no es un documento congelado.

## 1. Visión general

UDIS es un asistente personal cuyo "cerebro" vive en el móvil (la IA del propio
dispositivo), y cuya "capa de memoria" es esta aplicación: le da al asistente
capacidades que un LLM no tiene de fábrica — recordar sitios, relaciones, acciones,
archivos e imágenes con contexto — funcionando en segundo plano y offline-first.

El pinganillo es un **wearable de entrada/salida de audio** para esa IA: capta voz y
la manda al móvil, y reproduce lo que el móvil le devuelve. El PC (Desktop) es
**músculo extra**: recibe prompts/archivos del móvil y le devuelve resultados, para
tareas que el móvil no puede o no quiere hacer solo.

**Aircom** (antes "Beam") es la librería KMP de transporte que conecta estas tres
piezas — descubrimiento, cifrado, emparejamiento, mensajería — pero no sabe nada de
IA ni de memoria; eso vive en la app UDIS que la consume.

> Nota de secuenciación: el renombrado Beam→Aircom (paquetes, namespace, artefacto
> Maven) se deja para un pase dedicado aparte, para no mezclar un rename mecánico
> de ~30 archivos con el rediseño del protocolo de emparejamiento en el mismo cambio.
> Mientras tanto este documento ya usa el nombre "Aircom" para referirse a la
> librería, aunque el código siga en el paquete `com.nubax.beam.library`.

```
┌─────────────┐   audio (Aircom)   ┌─────────────┐   prompts/archivos (Aircom)   ┌─────────────┐
│  Pinganillo  │ ◄────────────────► │   UDIS       │ ◄────────────────────────────► │  UDIS        │
│  (ESP32)     │   1:1 con Android  │   Android    │   1:1 con Desktop              │  Desktop     │
└─────────────┘                    │  (el "hub")  │                                └─────────────┘
                                    └──────┬───────┘
                                           │ sync sesión/credenciales/backup memoria
                                           ▼
                                    ┌──────────────┐
                                    │ Servidor nube │
                                    └──────────────┘
```

---

## 2. Aircom — máquina de estados de vinculación

Estado compartido por los tres lados (pinganillo, Android, Desktop), tanto para el
estado LOCAL de un dispositivo como para cada entrada de su historial de peers:

| Estado         | Significado |
|----------------|-------------|
| `FABRICA`      | Nunca se ha emparejado (o se ha desvinculado y ha vuelto aquí). Es el estado de arranque de un pinganillo virgen. |
| `DESCUBRIENDO` | Visible/anunciándose, pero sin ningún intento de emparejamiento en curso. |
| `VINCULANDO`   | Handshake de emparejamiento en curso (credenciales o código ya enviados, esperando confirmación). |
| `VINCULADO`    | Emparejado y activo — este es el único peer con `active = true` de su tipo. |
| `DESVINCULADO` | Se ha desvinculado explícitamente (por el usuario, o por propagación desde el otro lado). |

### 2.1 Modelo de datos: historial de dispositivos

Sustituye al `TrustStore` actual (que solo guardaba "confío en este id"). Ahora se
guarda un **histórico** de todos los peers con los que se ha intentado/logrado
vincular, cada uno con su propio estado:

```kotlin
enum class LinkState { FABRICA, DESCUBRIENDO, VINCULANDO, VINCULADO, DESVINCULADO }
enum class PeerKind { PINGANILLO, DESKTOP, ANDROID }

data class LinkedDevice(
    val deviceId: String,
    val kind: PeerKind,
    val name: String,
    val publicKeyBase64: String,
    val state: LinkState,
    val active: Boolean,       // invariante: como mucho un `active=true` — ver más abajo
)
```

**Invariante de `active`, distinta según quién la guarda:**
- **Android**: como mucho UN `active=true` **por cada `kind`** — puede tener a la vez
  un pinganillo activo Y un desktop activo, pero nunca dos pinganillos ni dos desktops.
- **Pinganillo** y **Desktop**: como mucho UN `active=true` **en total** — cada uno
  solo puede estar vinculado con un Android a la vez.

Si llega un mensaje/handshake de un `deviceId` que SÍ está en el historial pero con
`active=false` (o no está en absoluto siendo el activo), se responde con el mensaje
de control `LinkStateMessage(state = DESVINCULADO)` en vez de tratarlo como un peer
válido. Quien lo recibe pone su propia entrada a `active=false, state=DESVINCULADO`
y actúa en consecuencia (el pinganillo se reinicia solo a modo `FABRICA`).

### 2.2 Mensajes de control nuevos (Aircom)

Además de los mensajes de aplicación (chat, audio, archivos), Aircom necesita estos
mensajes de protocolo, cifrados igual que cualquier otro mensaje pero con un `type`
reservado para que ambos lados los distingan del payload normal:

```kotlin
@Serializable
sealed class ControlMessage {
    @Serializable data class LinkStateChanged(val state: LinkState) : ControlMessage()
    @Serializable data class DesktopPairCodeSubmit(val code: String) : ControlMessage()
}
```

(El emparejamiento con el pinganillo no necesita mensaje de control propio: conocer
el SSID/clave YA es la prueba de autorización — ver §2.3.)

### 2.3 Flujo de emparejamiento — Pinganillo (usuario/contraseña)

Cada pinganillo se vende con una **tarjeta física** (como una SIM): `deviceId` +
SSID + contraseña de su AP, únicos por unidad y grabados en el firmware al
flashear. El backend guarda esa misma terna `(deviceId, ssid, password)` desde
fabricación, para poder recuperarla si el usuario pierde la tarjeta.

1. **Registro de propiedad** (nube, una sola vez): con sesión iniciada en UDIS, el
   usuario introduce el `deviceId` de la tarjeta → el backend vincula ese
   `deviceId` a su cuenta. A partir de aquí, si pierde la tarjeta, puede recuperar
   el SSID/clave desde su cuenta (login → "he perdido mi pinganillo" → backend
   devuelve las credenciales de ESE `deviceId` si le pertenece).
2. **Emparejamiento local** (P2P, no pasa por la nube): el pinganillo (`FABRICA`)
   levanta su AP con esas credenciales y el servidor Aircom (beacon UDP + TCP)
   escuchando en esa red. Android lo descubre y lo lista en *Devices*.
3. El usuario selecciona el pinganillo y teclea el SSID/clave de la tarjeta (o
   recuperados de su cuenta si la perdió).
4. Android se une a esa red (`AndroidPinganilloController`/`WifiNetworkSpecifier`)
   y hace el handshake criptográfico normal de Aircom (ECDH+firma).
5. Conocer las credenciales YA es la prueba de autorización — no hay un segundo
   paso de "confirmar código en pantalla": al completar el handshake, ambos lados
   pasan directamente a `VINCULADO` (`active=true`).
6. El pinganillo persiste la vinculación en NVS y se reinicia; a partir de ahí
   arranca siempre directo en modo vinculado (no vuelve a `FABRICA` salvo
   desvinculación).

> Nota de alcance: el registro de propiedad en el backend (paso 1) es un
> requisito nuevo para el servidor en la nube (ver §6) — no existe código de
> servidor en este repo todavía, así que por ahora se documenta el contrato pero
> no se implementa hasta que haya un backend real.

### 2.4 Flujo de emparejamiento — Desktop (código)

1. Desktop, mientras no tenga ningún dispositivo `VINCULADO`, genera un código
   corto aleatorio (p. ej. 6 dígitos) y lo muestra de forma **continua** en su
   pantalla (*Devices*) — no espera ninguna solicitud previa para generarlo.
2. Android descubre el Desktop (beacon Aircom) y lo lista en *Devices*.
3. El usuario lo selecciona, mira el código en la pantalla del Desktop, y lo
   teclea en Android.
4. Android manda `DesktopPairCodeSubmit(code)`. Si coincide con el que el Desktop
   está mostrando en ese momento, ambos pasan a `VINCULADO` (`active=true`);
   Desktop persiste el `deviceId` del móvil, Android persiste el del Desktop.
5. **A partir de aquí, la reconexión es automática**: no se vuelve a pedir el
   código nunca más. En cuanto Desktop y Android compartan red (el portátil se
   enciende en el radio del móvil, o viceversa), el beacon/handshake normal de
   Aircom los reconecta solo porque cada uno ya tiene al otro como `active=true`
   en su historial — el código de un solo uso solo sirve para la primera vez.
6. Una vez vinculados, ambos pueden enviarse y recibirse texto y archivos
   libremente por el canal ya cifrado.

### 2.5 Cardinalidad (resumen)

| Dispositivo | Puede vincularse con |
|---|---|
| Pinganillo  | 1 Android (nunca más) |
| Desktop     | 1 Android (nunca más) |
| Android     | 1 Pinganillo **y** 1 Desktop a la vez (no dos de un mismo tipo) |

Sin soporte de varios PCs por ahora — queda para una fase futura.

### 2.6 Propagación de desvinculación

- El usuario desvincula el pinganillo desde Android → Android marca esa entrada
  `active=false, state=DESVINCULADO` en su historial.
- El pinganillo no lo sabe todavía: sigue mandando beacons/intentando hablar.
- En cuanto el pinganillo contacta a Android, Android responde
  `LinkStateChanged(DESVINCULADO)` en vez de tratarlo como peer válido.
- El pinganillo recibe esto, se pone `state=DESVINCULADO` también, y se reinicia
  solo a `FABRICA` (vuelve a levantar su AP en modo emparejable).
- Mismo mecanismo simétrico para Desktop.

---

## 3. Pinganillo (firmware ESP32, C++)

Construido sobre el mismo concepto de Aircom ya implementado
(`esp32-pinganillo/main/beam_crypto.cpp` + `beam_protocol.cpp`: EC P-256, ECDH,
AES-256-GCM, framing de 4 bytes — ya validado contra Kotlin, ver el propio README),
pero **solo la parte de levantar servidor** (no necesita lógica de arbitraje ni
conectar hacia fuera: el pinganillo siempre es el que espera, Android siempre el
que conecta hacia él).

### 3.1 Máquina de estados local

```
        arranque
           │
    ¿hay vinculación
    persistida en NVS?
     │             │
     no            sí
     │             │
     ▼             ▼
  FABRICA      VINCULADO
  (AP+server   (AP+server
  esperando    esperando a
  pairing)     SU Android)
     │             │
  handshake OK     │
     │             │
     ▼             │
  VINCULADO ◄──────┘
     │
     │  botón mantenido pulsado
     ▼
  DESVINCULADO → borra NVS → reinicia → FABRICA
     │
     │  (o) llega LinkStateChanged(DESVINCULADO) del Android
     ▼
  DESVINCULADO → borra NVS → reinicia → FABRICA
```

### 3.2 Identidad de fábrica

Cada unidad física, al flashear, recibe (grabado en NVS o en el propio binario):
- Un `deviceId` único (ya lo calculábamos como `sha256(pubkey_der)[:16]` — sigue
  igual, es automático a partir de la keypair EC que se genera la primera vez).
- SSID/clave de su AP WiFi, **únicos por unidad** (cambio respecto al diseño
  anterior, donde eran un default compartido `Pinganillo-Beam`/`beam12345` para
  todas las unidades). Se entregan al usuario en una tarjeta física impresa al
  fabricar/flashear (mismo modelo que una SIM) — ver §2.3 para el flujo completo,
  incluido el registro de propiedad en el backend para poder recuperarlas.

### 3.3 Palabra de activación ("jarvis") — resuelto: placeholder por botón

Detectar una palabra de activación en audio continuo (*keyword spotting*)
requeriría un motor dedicado en el ESP32 (Espressif tiene **ESP-SR**/*WakeNet*),
con palabras "de catálogo" limitadas (no incluye "jarvis") y entrenar una propia
no es trivial ni instantáneo — además de flash/RAM/CPU extra a presupuestar.

**Decisión confirmada**: para este MVP no hay reconocimiento de voz para activar
el micro — todo el ciclo de escucha se controla con el único botón físico (§3.4).
Queda documentado para retomarlo si algún día se justifica integrar ESP-SR de
verdad.

### 3.4 Botón — máquina de 3 estados, una sola tecla

El mismo botón (pulsación corta) alterna entre los tres estados posibles; no hay
gestos distintos para "empezar" y "terminar" de escuchar — es siempre "alternar":

```
IDLE ──pulsación──► ESCUCHANDO ──pulsación──► ESPERANDO_RESPUESTA
 ▲                                                     │
 │◄──────── respuesta recibida y reproducida ──────────┤
 │◄──────── timeout de 30s sin respuesta ───────────────┤
 │                                                      │
 └───── pulsación (cancela espera, empieza a grabar) ───┘
```

- **`IDLE` → pulsación → `ESCUCHANDO`**: abre el mic, empieza a capturar y mandar
  `AudioMessage` en trozos.
- **`ESCUCHANDO` → pulsación → `ESPERANDO_RESPUESTA`**: manda el trozo final con
  `isFinal=true`, cierra el mic, se queda esperando el audio de respuesta.
- **`ESPERANDO_RESPUESTA` → pulsación → `ESCUCHANDO`**: cancela la espera (una
  respuesta que llegue tarde se descarta, no se reproduce encima de la nueva
  grabación) y empieza a grabar de inmediato — "olvida eso, pregunto otra cosa".
- **`ESPERANDO_RESPUESTA` → llega la respuesta → `IDLE`**: la reproduce por el
  altavoz y vuelve a reposo.
- **`ESPERANDO_RESPUESTA` → timeout (30s sin respuesta) → `IDLE`**: para no
  quedarse esperando para siempre si hay un fallo de red.

Aparte, **independiente de estos 3 estados**: mantener pulsado el botón varios
segundos siempre desvincula del Android actual (borra NVS, reinicia, arranca en
`FABRICA`) — funciona desde cualquiera de los tres estados.

### 3.5 Ciclo de vida de una interacción de voz

1. `IDLE`: mic cerrado, esperando pulsación.
2. Pulsación → `ESCUCHANDO`: se captura y manda audio en trozos (`AudioMessage`,
   ya implementado) mientras el mic esté abierto.
3. Pulsación → `ESPERANDO_RESPUESTA`: se manda el trozo final con `isFinal=true`,
   se cierra el mic.
4. Llega la respuesta (o pasan 30s) → vuelta a `IDLE`. Si llegó respuesta, se
   reproduce por el altavoz (ya implementado en `audio.cpp`).

---

## 4. UDIS (app KMP que consume Aircom)

### 4.1 Pantalla Chat (común)

Chat de texto con UDIS — sirve tanto como interfaz principal en Desktop como de
*fallback* en Android cuando el pinganillo no está disponible (sin batería, fuera
de alcance): puedes escribir en vez de hablar y entra por el mismo canal.

### 4.2 Pantalla Historial (común)

Registro de todas las interacciones con UDIS y las acciones que se han ejecutado a
raíz de ellas (auditoría/memoria de "qué hice y cuándo").

### 4.3 Pantalla Devices (varía por plataforma)

- **Desktop**: si no está vinculado, muestra el código de emparejamiento cuando se
  solicita (popup); si ya está vinculado, muestra el Android con el que lo está.
- **Android**: lista de dispositivos descubiertos (pinganillo y/o desktop). Al
  seleccionar uno: si es un pinganillo, pide usuario/contraseña (SSID/clave); si
  es un desktop, pide el código que se está mostrando en su pantalla. Solo estas
  dos posibilidades, nada más configurable por ahora.

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

Alcance: sesiones/credenciales de usuario, backup de la memoria (capas del §5), y
el **registro de propiedad de pinganillos** para recuperación de credenciales:

- `users`: cuenta, sesión.
- `pinganillos`: `deviceId → (ssid, password)`, precargado desde fabricación/flasheo.
- `pinganillo_ownership`: `deviceId → userId`, se crea cuando el usuario registra
  su tarjeta la primera vez (§2.3). Permite servir `(ssid, password)` de vuelta
  solo al dueño, si pierde la tarjeta física.
- Backup de las capas de memoria (§5).

No pasa prompts ni hace de intermediario entre dispositivos — eso sigue siendo
Aircom, P2P, local. No existe código de servidor en este repo todavía; esta
sección es el contrato a implementar cuando se aborde el backend.

---

## 7. Qué se aborda ya vs qué queda para después

**Hecho y verificado (Kotlin, con tests reales — no solo compilado):**
- ✅ Modelo `LinkedDevice`/`LinkState`/`PeerKind` + `DeviceHistoryStore` reemplazando `TrustStore`.
- ✅ Mensajes de control (`ControlMessage`): `LinkStateChanged`, `DesktopPairCodeSubmit`, `DesktopPairCodeResult`.
- ✅ Flujo pinganillo (`pairPinganillo`) y flujo desktop (`pairDesktopWithCode`, código
  generado de forma continua, reconexión automática después) en `MeshBeamConnection`.
- ✅ Test end-to-end del flujo de código Desktop (JUnit, `MeshBeamConnectionTest`).
- ✅ Demo (`composeApp`) actualizada con la nueva UI de emparejamiento.

**Hecho, escrito pero SIN poder probarse aquí (firmware C++, sin hardware/toolchain):**
- Firmware: acceptor-only (ya no conecta hacia fuera), bonding con un único
  Android persistido en NVS, rechazo de un segundo dispositivo, máquina de
  botón de 3 estados + pulsación larga, reinicio en desvinculación. Ver
  `esp32-pinganillo/README.md` para el detalle de qué sí se validó
  (`native_test/`, la rama de auto-vinculación) y qué no (rechazo de un
  segundo dispositivo, NVS real, el botón físico).

**Todavía no empezado:**
- Pantallas UDIS: Chat, Historial, Devices, Ajustes, Drive (la app en sí, más
  allá de la demo de `composeApp`).

**Deliberadamente fuera de esta fase:**
- Reconocimiento real de palabra de activación ("jarvis") — confirmado placeholder por botón (§3.3).
- Backend real (registro de propiedad, backup de memoria) — solo el contrato, no la implementación.
- Varios PCs vinculados a un mismo Android.
- Sync automática de galería.
- Las capas de memoria como funcionalidad de IA real (esto es backend/producto, no Aircom).
- Rename Beam→Aircom de paquetes (pase aparte).

## 8. Decisiones ya cerradas (histórico de la conversación de diseño)

1. ✅ Credenciales del pinganillo: tarjeta física tipo SIM + registro de propiedad
   en backend para recuperación (§2.3).
2. ✅ Código de Desktop: se genera y muestra de forma continua mientras esté sin
   vincular; Android lo envía directamente con `DesktopPairCodeSubmit`, sin paso
   de solicitud previo (§2.4).
3. ✅ "Jarvis": placeholder por botón confirmado para el MVP, sin ESP-SR por ahora (§3.3).
