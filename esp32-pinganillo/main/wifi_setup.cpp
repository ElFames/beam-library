#include "wifi_setup.h"
#include "provisioning_http.h"
#include "config.h"

#include <cstring>

#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "nvs.h"
#include "nvs_flash.h"

#if CONFIG_LWIP_IPV4_NAPT
#include "lwip/lwip_napt.h"
#endif

namespace wifi_setup {

namespace {
constexpr char TAG[] = "wifi_setup";
esp_netif_t* g_ap_netif = nullptr;
esp_netif_t* g_sta_netif = nullptr;

void on_wifi_sta_got_ip(void*, esp_event_base_t, int32_t, void* event_data) {
    auto* event = static_cast<ip_event_got_ip_t*>(event_data);
    ESP_LOGI(TAG, "STA conectada, IP=" IPSTR, IP2STR(&event->ip_info.ip));

#if CONFIG_LWIP_IPV4_NAPT
    esp_netif_ip_info_t ap_ip;
    esp_netif_get_ip_info(g_ap_netif, &ap_ip);
    // NAT entre la interfaz STA (internet real) y la AP (donde se cuelgan
    // móvil/desktop): a partir de aquí el pinganillo reparte internet de forma
    // transparente a quien se una a su propia red. `ip_napt_enable` es la API
    // clásica de lwIP para esto; si tu versión de ESP-IDF ya trae el wrapper
    // esp_netif_napt_enable(), es equivalente y puedes usarlo en su lugar.
    ip_napt_enable(ap_ip.ip.addr, 1);
    ESP_LOGI(TAG, "NAT activado: %s ahora reparte internet a su red", PINGANILLO_AP_SSID);
#else
    ESP_LOGW(TAG, "CONFIG_LWIP_IPV4_NAPT no está activo en este build: sin NAT no hay reparto de internet");
#endif
}

void start_ap() {
    wifi_config_t ap_config = {};
    strncpy(reinterpret_cast<char*>(ap_config.ap.ssid), PINGANILLO_AP_SSID, sizeof(ap_config.ap.ssid));
    ap_config.ap.ssid_len = strlen(PINGANILLO_AP_SSID);
    strncpy(reinterpret_cast<char*>(ap_config.ap.password), PINGANILLO_AP_PASSWORD, sizeof(ap_config.ap.password));
    ap_config.ap.authmode = WIFI_AUTH_WPA2_PSK;
    ap_config.ap.max_connection = 4;
    ap_config.ap.channel = 1;
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap_config));
}

} // namespace

bool has_sta_credentials() {
    nvs_handle_t handle;
    if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &handle) != ESP_OK) return false;
    size_t len = 0;
    bool ok = nvs_get_str(handle, NVS_KEY_STA_SSID, nullptr, &len) == ESP_OK && len > 1;
    nvs_close(handle);
    return ok;
}

void save_sta_credentials(const std::string& ssid, const std::string& password) {
    nvs_handle_t handle;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &handle) != ESP_OK) return;
    nvs_set_str(handle, NVS_KEY_STA_SSID, ssid.c_str());
    nvs_set_str(handle, NVS_KEY_STA_PASSWORD, password.c_str());
    nvs_commit(handle);
    nvs_close(handle);
    ESP_LOGI(TAG, "Credenciales de la WiFi de casa guardadas; reinicia el pinganillo para aplicarlas");
}

void start() {
    esp_err_t nvs_err = nvs_flash_init();
    if (nvs_err == ESP_ERR_NVS_NO_FREE_PAGES || nvs_err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        nvs_err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(nvs_err);

    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());

    g_ap_netif = esp_netif_create_default_wifi_ap();

    wifi_init_config_t init_cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&init_cfg));

    bool has_sta = has_sta_credentials();

    if (has_sta) {
        g_sta_netif = esp_netif_create_default_wifi_sta();
        ESP_ERROR_CHECK(esp_event_handler_instance_register(
            IP_EVENT, IP_EVENT_STA_GOT_IP, &on_wifi_sta_got_ip, nullptr, nullptr));

        std::string ssid, password;
        nvs_handle_t handle;
        if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &handle) == ESP_OK) {
            char buf[64] = {0};
            size_t len = sizeof(buf);
            if (nvs_get_str(handle, NVS_KEY_STA_SSID, buf, &len) == ESP_OK) ssid = buf;
            len = sizeof(buf);
            if (nvs_get_str(handle, NVS_KEY_STA_PASSWORD, buf, &len) == ESP_OK) password = buf;
            nvs_close(handle);
        }

        wifi_config_t sta_config = {};
        strncpy(reinterpret_cast<char*>(sta_config.sta.ssid), ssid.c_str(), sizeof(sta_config.sta.ssid));
        strncpy(reinterpret_cast<char*>(sta_config.sta.password), password.c_str(), sizeof(sta_config.sta.password));

        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_APSTA));
        start_ap();
        ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &sta_config));
        ESP_ERROR_CHECK(esp_wifi_start());
        esp_wifi_connect();
        ESP_LOGI(TAG, "Modo AP+STA: %s (propia) + %s (internet)", PINGANILLO_AP_SSID, ssid.c_str());
    } else {
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
        start_ap();
        ESP_ERROR_CHECK(esp_wifi_start());
        ESP_LOGI(TAG, "Modo AP puro: %s (sin WiFi de casa configurada todavía)", PINGANILLO_AP_SSID);
        provisioning_http::start(); // portal en 192.168.4.1 para configurar la WiFi de casa
    }
}

} // namespace wifi_setup
