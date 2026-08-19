#include "audio.h"
#include "beam_crypto.h"
#include "beam_protocol.h"
#include "wifi_setup.h"

#include "esp_log.h"

namespace {
constexpr char TAG[] = "main";
}

extern "C" void app_main(void) {
    // 1) Red: AP propio de fábrica siempre; STA+NAT hacia la WiFi de casa si ya
    //    se configuró (si no, levanta el portal de provisioning en 192.168.4.1).
    wifi_setup::start();

    // 2) Identidad criptográfica estable (se genera una vez, se persiste en NVS).
    beam_crypto::init();
    beam_crypto::load_or_create_identity();

    // 3) Protocolo Beam: beacon UDP + handshake TCP, símetrico con Android/Desktop.
    //    Cualquier AudioMessage que llegue de un peer se manda al altavoz.
    beam_protocol::start("Pinganillo", [](const std::string& /*peer_id*/, const std::vector<uint8_t>& plaintext) {
        audio::handle_incoming(plaintext);
    });

    // 4) Captura de mic -> AudioMessage -> se manda cifrado a todos los peers conectados.
    audio::start([](const std::vector<uint8_t>& data) {
        return beam_protocol::send_to_all(data);
    });

    ESP_LOGI(TAG, "Pinganillo listo, device_id=%s", beam_crypto::device_id().c_str());
}
