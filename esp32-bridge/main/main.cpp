#include "wifi_setup.h"

#include "esp_log.h"

namespace {
constexpr char TAG[] = "main";
}

extern "C" void app_main(void) {
    // Puente de red puro: AP propio de fábrica siempre (WPA3, ver wifi_setup.cpp);
    // STA+NAT hacia la WiFi de casa/oficina si ya se configuró (si no, portal de
    // provisioning en 192.168.4.1). Sin protocolo Aircom aquí — este dispositivo
    // es infraestructura de red transparente, no un peer (ver PROJECT.md §3).
    wifi_setup::start();

    ESP_LOGI(TAG, "Puente de red listo");
}
