#include "audio.h"
#include "beam_crypto.h"
#include "beam_protocol.h"
#include "config.h"

#include <cstdio>
#include <cstring>

#include "cJSON.h"
#include "driver/gpio.h"
#include "driver/i2s.h"
#include "esp_log.h"
#include "esp_random.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"

namespace audio {

namespace {
constexpr char TAG[] = "audio";

enum class VoiceState { IDLE, ESCUCHANDO, ESPERANDO_RESPUESTA };

SendFrame g_send_frame;
std::string g_stream_id;
int g_seq = 0;

SemaphoreHandle_t g_state_mutex;
VoiceState g_state = VoiceState::IDLE;
TickType_t g_waiting_since = 0;

void install_mic() {
    i2s_config_t cfg = {};
    cfg.mode = static_cast<i2s_mode_t>(I2S_MODE_MASTER | I2S_MODE_RX);
    cfg.sample_rate = AUDIO_SAMPLE_RATE_HZ;
    // La mayoría de mics I2S digitales (p. ej. INMP441) entregan 24 bits útiles
    // dentro de una trama de 32; se lee a 32 bits y se recorta a 16 en software
    // (ver captura más abajo). Si tu mic ya da 16 bits nativos, cambia esto y
    // quita el recorte.
    cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT;
    cfg.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
    cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
    cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
    cfg.dma_buf_count = 4;
    cfg.dma_buf_len = AUDIO_CHUNK_SAMPLES;
    cfg.use_apll = false;

    i2s_driver_install(I2S_MIC_PORT, &cfg, 0, nullptr);
    i2s_pin_config_t pins = {};
    pins.bck_io_num = I2S_MIC_BCLK_PIN;
    pins.ws_io_num = I2S_MIC_WS_PIN;
    pins.data_out_num = I2S_PIN_NO_CHANGE;
    pins.data_in_num = I2S_MIC_DATA_PIN;
    i2s_set_pin(I2S_MIC_PORT, &pins);
}

void install_speaker() {
    i2s_config_t cfg = {};
    cfg.mode = static_cast<i2s_mode_t>(I2S_MODE_MASTER | I2S_MODE_TX);
    cfg.sample_rate = AUDIO_SAMPLE_RATE_HZ;
    cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
    cfg.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
    cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
    cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
    cfg.dma_buf_count = 4;
    cfg.dma_buf_len = AUDIO_CHUNK_SAMPLES;
    cfg.use_apll = false;

    i2s_driver_install(I2S_SPK_PORT, &cfg, 0, nullptr);
    i2s_pin_config_t pins = {};
    pins.bck_io_num = I2S_SPK_BCLK_PIN;
    pins.ws_io_num = I2S_SPK_WS_PIN;
    pins.data_out_num = I2S_SPK_DATA_PIN;
    pins.data_in_num = I2S_PIN_NO_CHANGE;
    i2s_set_pin(I2S_SPK_PORT, &pins);
}

void install_button() {
    gpio_config_t cfg = {};
    cfg.pin_bit_mask = 1ULL << BUTTON_GPIO_PIN;
    cfg.mode = GPIO_MODE_INPUT;
    cfg.pull_up_en = GPIO_PULLUP_ENABLE; // botón a GND: reposo=alto, pulsado=bajo
    cfg.pull_down_en = GPIO_PULLDOWN_DISABLE;
    cfg.intr_type = GPIO_INTR_DISABLE; // se sondea por polling, no por interrupción
    gpio_config(&cfg);
}

std::string make_stream_id() {
    uint8_t raw[8];
    esp_fill_random(raw, sizeof(raw));
    char hex[17];
    for (int i = 0; i < 8; i++) snprintf(hex + i * 2, 3, "%02x", raw[i]);
    return std::string(hex, 16);
}

void send_audio_chunk(const uint8_t* pcm, size_t len, bool is_final) {
    cJSON* msg = cJSON_CreateObject();
    cJSON_AddStringToObject(msg, "streamId", g_stream_id.c_str());
    cJSON_AddNumberToObject(msg, "seq", g_seq++);
    cJSON_AddBoolToObject(msg, "isFinal", is_final);
    cJSON_AddNumberToObject(msg, "sampleRateHz", AUDIO_SAMPLE_RATE_HZ);
    cJSON_AddNumberToObject(msg, "channels", AUDIO_CHANNELS);
    cJSON_AddNumberToObject(msg, "bitsPerSample", AUDIO_BITS_PER_SAMPLE);
    std::vector<uint8_t> pcm_slice(pcm, pcm + len);
    cJSON_AddStringToObject(msg, "pcm", beam_crypto::to_base64(pcm_slice).c_str());

    char* text = cJSON_PrintUnformatted(msg);
    if (text && g_send_frame) {
        std::vector<uint8_t> bytes(text, text + strlen(text));
        g_send_frame(bytes);
    }
    if (text) cJSON_free(text);
    cJSON_Delete(msg);
}

/** Transición al pulsar (corta): IDLE->ESCUCHANDO->ESPERANDO_RESPUESTA->ESCUCHANDO (cancela). */
void on_short_press() {
    xSemaphoreTake(g_state_mutex, portMAX_DELAY);
    switch (g_state) {
        case VoiceState::IDLE:
            g_stream_id = make_stream_id();
            g_seq = 0;
            g_state = VoiceState::ESCUCHANDO;
            ESP_LOGI(TAG, "IDLE -> ESCUCHANDO (streamId=%s)", g_stream_id.c_str());
            break;
        case VoiceState::ESCUCHANDO:
            send_audio_chunk(nullptr, 0, /*is_final=*/true);
            g_state = VoiceState::ESPERANDO_RESPUESTA;
            g_waiting_since = xTaskGetTickCount();
            ESP_LOGI(TAG, "ESCUCHANDO -> ESPERANDO_RESPUESTA");
            break;
        case VoiceState::ESPERANDO_RESPUESTA:
            // "Olvida eso, pregunto otra cosa": cancela la espera y graba ya.
            g_stream_id = make_stream_id();
            g_seq = 0;
            g_state = VoiceState::ESCUCHANDO;
            ESP_LOGI(TAG, "ESPERANDO_RESPUESTA -> ESCUCHANDO (cancelado, streamId=%s)", g_stream_id.c_str());
            break;
    }
    xSemaphoreGive(g_state_mutex);
}

void button_task(void*) {
    bool last_level_high = true; // reposo = alto (pull-up)
    TickType_t press_start = 0;
    bool long_press_fired = false;

    while (true) {
        bool level_high = gpio_get_level(BUTTON_GPIO_PIN) != 0;
        TickType_t now = xTaskGetTickCount();

        if (last_level_high && !level_high) {
            // flanco de bajada: empieza una pulsación
            press_start = now;
            long_press_fired = false;
        } else if (!level_high && !long_press_fired) {
            // sigue pulsado: ¿ya llegó a pulsación larga?
            if ((now - press_start) * portTICK_PERIOD_MS >= BUTTON_LONG_PRESS_MS) {
                long_press_fired = true;
                ESP_LOGI(TAG, "Pulsación larga -> desvincular");
                beam_protocol::unlink_and_restart(); // no vuelve: reinicia el dispositivo
            }
        } else if (!last_level_high && level_high) {
            // flanco de subida: soltó el botón
            if (!long_press_fired) on_short_press();
        }

        last_level_high = level_high;
        vTaskDelay(pdMS_TO_TICKS(BUTTON_POLL_MS));
    }
}

/** Captura mientras ESCUCHANDO; comprueba el timeout mientras ESPERANDO_RESPUESTA. */
void voice_task(void*) {
    const size_t frame_bytes = AUDIO_CHUNK_SAMPLES * sizeof(int32_t);
    std::vector<int32_t> raw(AUDIO_CHUNK_SAMPLES);
    std::vector<uint8_t> pcm16(AUDIO_CHUNK_SAMPLES * sizeof(int16_t));

    while (true) {
        xSemaphoreTake(g_state_mutex, portMAX_DELAY);
        VoiceState state = g_state;
        TickType_t waiting_since = g_waiting_since;
        xSemaphoreGive(g_state_mutex);

        if (state == VoiceState::ESCUCHANDO) {
            size_t bytes_read = 0;
            i2s_read(I2S_MIC_PORT, raw.data(), frame_bytes, &bytes_read, pdMS_TO_TICKS(200));
            size_t samples = bytes_read / sizeof(int32_t);
            if (samples == 0) continue;

            auto* out16 = reinterpret_cast<int16_t*>(pcm16.data());
            for (size_t i = 0; i < samples; i++) {
                // Recorte de 32->16 bits. El desplazamiento (>>14) es un punto de
                // partida razonable para INMP441 a ganancia de fábrica; ajusta si
                // el audio sale saturado o demasiado bajo con tu micro concreto.
                out16[i] = static_cast<int16_t>(raw[i] >> 14);
            }
            send_audio_chunk(pcm16.data(), samples * sizeof(int16_t), /*is_final=*/false);
        } else if (state == VoiceState::ESPERANDO_RESPUESTA) {
            TickType_t elapsed_ms = (xTaskGetTickCount() - waiting_since) * portTICK_PERIOD_MS;
            if (elapsed_ms >= RESPONSE_TIMEOUT_MS) {
                xSemaphoreTake(g_state_mutex, portMAX_DELAY);
                if (g_state == VoiceState::ESPERANDO_RESPUESTA) {
                    g_state = VoiceState::IDLE;
                    ESP_LOGI(TAG, "Timeout esperando respuesta -> IDLE");
                }
                xSemaphoreGive(g_state_mutex);
            }
            vTaskDelay(pdMS_TO_TICKS(200));
        } else {
            vTaskDelay(pdMS_TO_TICKS(50)); // IDLE: no hay nada que hacer, solo esperar al botón
        }
    }
}

} // namespace

void start(SendFrame send_frame) {
    g_send_frame = std::move(send_frame);
    g_state_mutex = xSemaphoreCreateMutex();
    install_mic();
    install_speaker();
    install_button();
    xTaskCreate(button_task, "button", 3072, nullptr, 6, nullptr);
    xTaskCreate(voice_task, "voice", 4096, nullptr, 5, nullptr);
    ESP_LOGI(TAG, "Audio iniciado, esperando pulsación del botón (IDLE)");
}

void handle_incoming(const std::vector<uint8_t>& plaintext) {
    if (plaintext.empty()) return;
    std::string text(plaintext.begin(), plaintext.end());
    cJSON* json = cJSON_Parse(text.c_str());
    if (!json) return;

    cJSON* pcm_item = cJSON_GetObjectItem(json, "pcm");
    if (cJSON_IsString(pcm_item)) {
        bool should_play = false;
        xSemaphoreTake(g_state_mutex, portMAX_DELAY);
        if (g_state == VoiceState::ESPERANDO_RESPUESTA) {
            g_state = VoiceState::IDLE;
            should_play = true;
        }
        // Si NO estábamos esperando (p. ej. ya cancelamos y volvimos a grabar),
        // la respuesta llegó tarde: se descarta, no se reproduce encima de la
        // nueva grabación.
        xSemaphoreGive(g_state_mutex);

        if (should_play) {
            auto pcm = beam_crypto::from_base64(pcm_item->valuestring);
            size_t written = 0;
            i2s_write(I2S_SPK_PORT, pcm.data(), pcm.size(), &written, portMAX_DELAY);
        } else {
            ESP_LOGI(TAG, "Respuesta descartada (ya no estábamos esperando)");
        }
    }
    cJSON_Delete(json);
}

} // namespace audio
