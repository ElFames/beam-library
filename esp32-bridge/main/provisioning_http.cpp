#include "provisioning_http.h"
#include "wifi_setup.h"

#include <cstdio>
#include <string>
#include <vector>

#include "esp_http_server.h"
#include "esp_log.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

namespace provisioning_http {

namespace {
constexpr char TAG[] = "provisioning_http";

const char* FORM_HTML =
    "<!doctype html><html><body style=\"font-family:sans-serif;max-width:400px;margin:40px auto\">"
    "<h2>Configurar WiFi de casa</h2>"
    "<p>El puente usara esta red para repartir internet a quien se una a su propio WiFi.</p>"
    "<form method=\"POST\" action=\"/wifi\">"
    "<label>SSID<br><input name=\"ssid\" required></label><br><br>"
    "<label>Contrasena<br><input name=\"password\" type=\"password\"></label><br><br>"
    "<button type=\"submit\">Guardar y reiniciar</button>"
    "</form></body></html>";

esp_err_t handle_root(httpd_req_t* req) {
    httpd_resp_send(req, FORM_HTML, HTTPD_RESP_USE_STRLEN);
    return ESP_OK;
}

std::string url_decode(const std::string& in) {
    std::string out;
    for (size_t i = 0; i < in.size(); i++) {
        if (in[i] == '+') {
            out += ' ';
        } else if (in[i] == '%' && i + 2 < in.size()) {
            int value = 0;
            sscanf(in.substr(i + 1, 2).c_str(), "%x", &value);
            out += static_cast<char>(value);
            i += 2;
        } else {
            out += in[i];
        }
    }
    return out;
}

std::string extract_field(const std::string& body, const std::string& key) {
    auto pos = body.find(key + "=");
    if (pos == std::string::npos) return "";
    pos += key.size() + 1;
    auto end = body.find('&', pos);
    return url_decode(body.substr(pos, end == std::string::npos ? std::string::npos : end - pos));
}

esp_err_t handle_wifi_post(httpd_req_t* req) {
    if (req->content_len == 0 || req->content_len > 512) {
        httpd_resp_send_500(req);
        return ESP_FAIL;
    }
    std::vector<char> buf(req->content_len + 1, 0);
    int received = httpd_req_recv(req, buf.data(), req->content_len);
    if (received <= 0) {
        httpd_resp_send_500(req);
        return ESP_FAIL;
    }
    std::string body(buf.data(), received);
    std::string ssid = extract_field(body, "ssid");
    std::string password = extract_field(body, "password");

    wifi_setup::save_sta_credentials(ssid, password);

    const char* done = "<html><body><h2>Guardado. Reiniciando...</h2></body></html>";
    httpd_resp_send(req, done, HTTPD_RESP_USE_STRLEN);

    vTaskDelay(pdMS_TO_TICKS(1500));
    esp_restart();
    return ESP_OK;
}

} // namespace

void start() {
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    httpd_handle_t server = nullptr;
    if (httpd_start(&server, &config) != ESP_OK) {
        ESP_LOGE(TAG, "No se pudo arrancar el portal HTTP");
        return;
    }

    httpd_uri_t root_uri = {};
    root_uri.uri = "/";
    root_uri.method = HTTP_GET;
    root_uri.handler = handle_root;
    httpd_register_uri_handler(server, &root_uri);

    httpd_uri_t wifi_uri = {};
    wifi_uri.uri = "/wifi";
    wifi_uri.method = HTTP_POST;
    wifi_uri.handler = handle_wifi_post;
    httpd_register_uri_handler(server, &wifi_uri);

    ESP_LOGI(TAG, "Portal de configuracion en http://192.168.4.1/");
}

} // namespace provisioning_http
