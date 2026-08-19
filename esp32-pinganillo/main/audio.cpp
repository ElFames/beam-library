#include "audio.h"
#include "beam_crypto.h"
#include "config.h"

#include <cstdio>
#include <cstring>

#include "cJSON.h"
#include "driver/i2s.h"
#include "esp_log.h"
#include "esp_random.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

namespace audio {

namespace {
constexpr char TAG[] = "audio";

SendFrame g_send_frame;
std::string g_stream_id;
int g_seq = 0;

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

std::string make_stream_id() {
    uint8_t raw[8];
    esp_fill_random(raw, sizeof(raw));
    char hex[17];
    for (int i = 0; i < 8; i++) snprintf(hex + i * 2, 3, "%02x", raw[i]);
    return std::string(hex, 16);
}

void capture_task(void*) {
    const size_t frame_bytes = AUDIO_CHUNK_SAMPLES * sizeof(int32_t);
    std::vector<int32_t> raw(AUDIO_CHUNK_SAMPLES);
    std::vector<uint8_t> pcm16(AUDIO_CHUNK_SAMPLES * sizeof(int16_t));

    while (true) {
        size_t bytes_read = 0;
        i2s_read(I2S_MIC_PORT, raw.data(), frame_bytes, &bytes_read, portMAX_DELAY);
        size_t samples = bytes_read / sizeof(int32_t);

        auto* out16 = reinterpret_cast<int16_t*>(pcm16.data());
        for (size_t i = 0; i < samples; i++) {
            // Recorte de 32->16 bits. El desplazamiento (>>14) es un punto de
            // partida razonable para INMP441 a ganancia de fábrica; ajusta si
            // el audio sale saturado o demasiado bajo con tu micro concreto.
            out16[i] = static_cast<int16_t>(raw[i] >> 14);
        }

        cJSON* msg = cJSON_CreateObject();
        cJSON_AddStringToObject(msg, "streamId", g_stream_id.c_str());
        cJSON_AddNumberToObject(msg, "seq", g_seq++);
        cJSON_AddBoolToObject(msg, "isFinal", false);
        cJSON_AddNumberToObject(msg, "sampleRateHz", AUDIO_SAMPLE_RATE_HZ);
        cJSON_AddNumberToObject(msg, "channels", AUDIO_CHANNELS);
        cJSON_AddNumberToObject(msg, "bitsPerSample", AUDIO_BITS_PER_SAMPLE);
        std::vector<uint8_t> pcm_slice(pcm16.begin(), pcm16.begin() + samples * sizeof(int16_t));
        cJSON_AddStringToObject(msg, "pcm", beam_crypto::to_base64(pcm_slice).c_str());

        char* text = cJSON_PrintUnformatted(msg);
        if (text && g_send_frame) {
            std::vector<uint8_t> bytes(text, text + strlen(text));
            g_send_frame(bytes);
        }
        if (text) cJSON_free(text);
        cJSON_Delete(msg);
    }
}

} // namespace

void start(SendFrame send_frame) {
    g_send_frame = std::move(send_frame);
    g_stream_id = make_stream_id();
    install_mic();
    install_speaker();
    xTaskCreate(capture_task, "audio_capture", 4096, nullptr, 5, nullptr);
    ESP_LOGI(TAG, "Audio iniciado (streamId=%s)", g_stream_id.c_str());
}

void handle_incoming(const std::vector<uint8_t>& plaintext) {
    if (plaintext.empty()) return;
    std::string text(plaintext.begin(), plaintext.end());
    cJSON* json = cJSON_Parse(text.c_str());
    if (!json) return;

    cJSON* pcm_item = cJSON_GetObjectItem(json, "pcm");
    if (cJSON_IsString(pcm_item)) {
        auto pcm = beam_crypto::from_base64(pcm_item->valuestring);
        size_t written = 0;
        i2s_write(I2S_SPK_PORT, pcm.data(), pcm.size(), &written, portMAX_DELAY);
    }
    cJSON_Delete(json);
}

} // namespace audio
