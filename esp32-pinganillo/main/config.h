#pragma once

// ---------------------------------------------------------------------------
// Identidad de red del pinganillo. DEBE coincidir carácter a carácter con
// PinganilloDefaults.AP_SSID / AP_PASSWORD en el lado Kotlin
// (beam/src/commonMain/.../connectivity/PinganilloDefaults.kt): son dos repos y
// lenguajes distintos, no hay una única fuente de verdad compartida, así que si
// cambias una constante cambia también la otra a mano.
// ---------------------------------------------------------------------------
#define PINGANILLO_AP_SSID     "Pinganillo-Beam"
#define PINGANILLO_AP_PASSWORD "beam12345"

// Puertos del protocolo Beam — deben coincidir con MeshBeamConnection (Kotlin).
#define BEAM_BEACON_PORT 8888
#define BEAM_TCP_PORT    9999
#define BEAM_BEACON_INTERVAL_MS 3000

// Máximo de peers (móvil + desktop, típicamente) hablando con el pinganillo a la vez.
#define BEAM_MAX_PEERS 4

// NVS: namespace y claves de configuración persistente.
#define NVS_NAMESPACE        "pinganillo"
#define NVS_KEY_STA_SSID     "sta_ssid"
#define NVS_KEY_STA_PASSWORD "sta_pass"
#define NVS_KEY_EC_PRIVATE   "ec_priv"
#define NVS_KEY_EC_PUBLIC    "ec_pub_der"

// Vinculación (PROJECT.md §2/§3): el pinganillo solo guarda si ya está vinculado
// y con qué deviceId de Android — el resto de estados (FABRICA/DESCUBRIENDO/
// VINCULANDO/DESVINCULADO) son transitorios y no necesitan persistirse aparte:
// "sin bonded_id" ES el estado FABRICA.
#define NVS_KEY_BONDED_ANDROID_ID "bonded_id"

// ---------------------------------------------------------------------------
// Botón físico — máquina de 3 estados (IDLE/ESCUCHANDO/ESPERANDO_RESPUESTA) con
// pulsación corta, más pulsación larga (independiente) para desvincular.
// AJUSTAR el pin a tu cableado real; se evita GPIO0 a propósito porque en la
// mayoría de placas decide el modo de arranque (mantenerlo pulsado sin querer
// al encender metería al ESP32 en modo descarga).
// ---------------------------------------------------------------------------
#define BUTTON_GPIO_PIN GPIO_NUM_4
#define BUTTON_LONG_PRESS_MS 3000
#define BUTTON_POLL_MS 30
#define RESPONSE_TIMEOUT_MS 30000

// ---------------------------------------------------------------------------
// Pines I2S — AJUSTAR a la placa/mic/ampli reales. Valores por defecto pensados
// para un micrófono digital tipo INMP441 (bus I2S_NUM_0) y un ampli+altavoz I2S
// tipo MAX98357A (bus I2S_NUM_1), que es la combinación más común en proyectos
// de este tipo. Si usas otro hardware, solo hace falta tocar estos #define.
// ---------------------------------------------------------------------------
#define I2S_MIC_PORT      I2S_NUM_0
#define I2S_MIC_BCLK_PIN  GPIO_NUM_26
#define I2S_MIC_WS_PIN    GPIO_NUM_25
#define I2S_MIC_DATA_PIN  GPIO_NUM_22

#define I2S_SPK_PORT      I2S_NUM_1
#define I2S_SPK_BCLK_PIN  GPIO_NUM_27
#define I2S_SPK_WS_PIN    GPIO_NUM_14
#define I2S_SPK_DATA_PIN  GPIO_NUM_12

#define AUDIO_SAMPLE_RATE_HZ 16000
#define AUDIO_BITS_PER_SAMPLE 16
#define AUDIO_CHANNELS 1
#define AUDIO_CHUNK_SAMPLES 1024
