#include "beam_protocol.h"
#include "beam_crypto.h"
#include "config.h"

#include <algorithm>
#include <cstring>
#include <memory>
#include <vector>

#include "cJSON.h"
#include "esp_log.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "lwip/sockets.h"
#include "lwip/inet.h"
#include "nvs.h"

namespace beam_protocol {

namespace {
constexpr char TAG[] = "beam_protocol";

std::string g_device_name;
MessageHandler g_on_message;
std::string g_bonded_android_id; // vacío == FABRICA (nadie vinculado todavía)

struct Peer {
    std::string id;
    int fd;
    std::unique_ptr<beam_crypto::SecureChannel> channel;
};

std::vector<Peer> g_peers;
SemaphoreHandle_t g_peers_mutex;

int g_udp_socket = -1;
int g_tcp_server_socket = -1;

// ---------------------------------------------------------------------------
// Persistencia de vinculación
// ---------------------------------------------------------------------------

void load_bonded_id() {
    nvs_handle_t handle;
    if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &handle) != ESP_OK) return;
    char buf[40] = {0};
    size_t len = sizeof(buf);
    if (nvs_get_str(handle, NVS_KEY_BONDED_ANDROID_ID, buf, &len) == ESP_OK) {
        g_bonded_android_id = buf;
    }
    nvs_close(handle);
}

void save_bonded_id(const std::string& id) {
    g_bonded_android_id = id;
    nvs_handle_t handle;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &handle) == ESP_OK) {
        nvs_set_str(handle, NVS_KEY_BONDED_ANDROID_ID, id.c_str());
        nvs_commit(handle);
        nvs_close(handle);
    }
}

void clear_bonded_id() {
    g_bonded_android_id.clear();
    nvs_handle_t handle;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &handle) == ESP_OK) {
        nvs_erase_key(handle, NVS_KEY_BONDED_ANDROID_ID);
        nvs_commit(handle);
        nvs_close(handle);
    }
}

// ---------------------------------------------------------------------------
// Framing: 4 bytes big-endian de longitud + payload. Igual que BeamProtocol.kt.
// ---------------------------------------------------------------------------

bool read_exact(int fd, uint8_t* buf, size_t len) {
    size_t read_total = 0;
    while (read_total < len) {
        ssize_t n = recv(fd, buf + read_total, len - read_total, 0);
        if (n <= 0) return false;
        read_total += n;
    }
    return true;
}

bool write_exact(int fd, const uint8_t* buf, size_t len) {
    size_t written = 0;
    while (written < len) {
        ssize_t n = send(fd, buf + written, len - written, 0);
        if (n <= 0) return false;
        written += n;
    }
    return true;
}

bool write_framed(int fd, const std::vector<uint8_t>& bytes) {
    uint32_t size = static_cast<uint32_t>(bytes.size());
    uint8_t header[4] = {
        static_cast<uint8_t>(size >> 24), static_cast<uint8_t>(size >> 16),
        static_cast<uint8_t>(size >> 8), static_cast<uint8_t>(size)
    };
    return write_exact(fd, header, 4) && write_exact(fd, bytes.data(), bytes.size());
}

bool read_framed(int fd, std::vector<uint8_t>& out) {
    uint8_t header[4];
    if (!read_exact(fd, header, 4)) return false;
    uint32_t size = (static_cast<uint32_t>(header[0]) << 24) | (header[1] << 16) | (header[2] << 8) | header[3];
    if (size > 4 * 1024 * 1024) return false; // guarda-raíl: nada de este protocolo manda mensajes tan grandes
    out.resize(size);
    return size == 0 || read_exact(fd, out.data(), size);
}

bool write_plain_json(int fd, cJSON* obj) {
    char* text = cJSON_PrintUnformatted(obj);
    if (!text) return false;
    std::vector<uint8_t> bytes(text, text + strlen(text));
    cJSON_free(text);
    return write_framed(fd, bytes);
}

cJSON* read_plain_json(int fd) {
    std::vector<uint8_t> bytes;
    if (!read_framed(fd, bytes)) return nullptr;
    std::string text(bytes.begin(), bytes.end());
    return cJSON_Parse(text.c_str());
}

// ---------------------------------------------------------------------------
// Mensajes de control (ver ControlMessage.kt): JSON con "type" discriminador.
// ---------------------------------------------------------------------------

std::vector<uint8_t> build_link_state_changed_desvinculado() {
    cJSON* msg = cJSON_CreateObject();
    cJSON_AddStringToObject(msg, "type", "link_state_changed");
    cJSON_AddStringToObject(msg, "state", "DESVINCULADO");
    char* text = cJSON_PrintUnformatted(msg);
    std::vector<uint8_t> bytes(text, text + strlen(text));
    cJSON_free(text);
    cJSON_Delete(msg);
    return bytes;
}

/** true si YA se gestionó aquí (no hay que pasarlo a la app). */
bool try_handle_control_message(const std::string& peer_id, const std::vector<uint8_t>& plaintext) {
    std::string text(plaintext.begin(), plaintext.end());
    cJSON* json = cJSON_Parse(text.c_str());
    if (!json) return false;

    cJSON* type_item = cJSON_GetObjectItem(json, "type");
    if (!cJSON_IsString(type_item)) { cJSON_Delete(json); return false; }

    bool handled = false;
    if (strcmp(type_item->valuestring, "link_state_changed") == 0) {
        cJSON* state_item = cJSON_GetObjectItem(json, "state");
        if (cJSON_IsString(state_item) && strcmp(state_item->valuestring, "DESVINCULADO") == 0) {
            ESP_LOGI(TAG, "%s nos avisa de que ya no estamos vinculados -> reinicio a FABRICA", peer_id.c_str());
            clear_bonded_id();
            handled = true;
            cJSON_Delete(json);
            vTaskDelay(pdMS_TO_TICKS(200));
            esp_restart();
            return true; // no debería llegar aquí
        }
        handled = true;
    }
    cJSON_Delete(json);
    return handled;
}

// ---------------------------------------------------------------------------
// Peers
// ---------------------------------------------------------------------------

void cleanup_peer(const std::string& id) {
    xSemaphoreTake(g_peers_mutex, portMAX_DELAY);
    for (auto it = g_peers.begin(); it != g_peers.end(); ++it) {
        if (it->id == id) {
            close(it->fd);
            g_peers.erase(it);
            break;
        }
    }
    xSemaphoreGive(g_peers_mutex);
    ESP_LOGI(TAG, "Peer %s desconectado", id.c_str());
}

struct ReadLoopArgs { std::string id; int fd; };

void read_loop_task(void* arg) {
    std::unique_ptr<ReadLoopArgs> args(static_cast<ReadLoopArgs*>(arg));
    while (true) {
        std::vector<uint8_t> framed;
        if (!read_framed(args->fd, framed)) break;

        beam_crypto::SecureChannel* channel = nullptr;
        xSemaphoreTake(g_peers_mutex, portMAX_DELAY);
        for (auto& p : g_peers) if (p.id == args->id) channel = p.channel.get();
        xSemaphoreGive(g_peers_mutex);
        if (!channel) break;

        auto plaintext = channel->decrypt(framed);
        if (plaintext.empty() && !framed.empty()) {
            ESP_LOGE(TAG, "Mensaje de %s no se pudo descifrar", args->id.c_str());
            continue;
        }
        if (!try_handle_control_message(args->id, plaintext) && g_on_message) {
            g_on_message(args->id, plaintext);
        }
    }
    cleanup_peer(args->id);
    vTaskDelete(nullptr);
}

void register_peer(const std::string& id, int fd, std::unique_ptr<beam_crypto::SecureChannel> channel) {
    xSemaphoreTake(g_peers_mutex, portMAX_DELAY);
    g_peers.push_back(Peer{id, fd, std::move(channel)});
    xSemaphoreGive(g_peers_mutex);
    ESP_LOGI(TAG, "Peer conectado: %s (total=%d)", id.c_str(), (int)g_peers.size());

    auto* args = new ReadLoopArgs{id, fd};
    xTaskCreate(read_loop_task, "beam_read", 4096, args, 5, nullptr);
}

// ---------------------------------------------------------------------------
// Handshake — solo acceptor (el pinganillo nunca conecta hacia fuera).
// ---------------------------------------------------------------------------

void perform_handshake_as_acceptor(int fd) {
    std::string my_id = beam_crypto::device_id();

    cJSON* their_hello = read_plain_json(fd);
    if (!their_hello) { close(fd); return; }

    cJSON* my_hello = cJSON_CreateObject();
    cJSON_AddStringToObject(my_hello, "id", my_id.c_str());
    cJSON_AddStringToObject(my_hello, "name", g_device_name.c_str());
    cJSON_AddStringToObject(my_hello, "kind", "PINGANILLO");
    cJSON_AddStringToObject(my_hello, "publicKeyBase64",
        beam_crypto::to_base64(beam_crypto::identity_public_der()).c_str());
    write_plain_json(fd, my_hello);
    cJSON_Delete(my_hello);

    std::string their_id = cJSON_GetObjectItem(their_hello, "id")->valuestring;
    std::string their_pub_b64 = cJSON_GetObjectItem(their_hello, "publicKeyBase64")->valuestring;
    cJSON_Delete(their_hello);

    if (their_id == my_id) { close(fd); return; }

    cJSON* their_offer = read_plain_json(fd);
    if (!their_offer) { close(fd); return; }

    auto* ephemeral = beam_crypto::generate_ephemeral();
    auto ephemeral_pub_der = beam_crypto::ephemeral_public_der(ephemeral);
    auto signature = beam_crypto::sign_with_identity(ephemeral_pub_der);

    cJSON* my_offer = cJSON_CreateObject();
    cJSON_AddStringToObject(my_offer, "publicKeyBase64", beam_crypto::to_base64(ephemeral_pub_der).c_str());
    cJSON_AddStringToObject(my_offer, "signatureBase64", beam_crypto::to_base64(signature).c_str());
    write_plain_json(fd, my_offer);
    cJSON_Delete(my_offer);

    std::string their_eph_b64 = cJSON_GetObjectItem(their_offer, "publicKeyBase64")->valuestring;
    std::string their_sig_b64 = cJSON_GetObjectItem(their_offer, "signatureBase64")->valuestring;
    cJSON_Delete(their_offer);

    auto their_identity_der = beam_crypto::from_base64(their_pub_b64);
    auto their_ephemeral_der = beam_crypto::from_base64(their_eph_b64);
    auto their_signature = beam_crypto::from_base64(their_sig_b64);

    bool valid = beam_crypto::verify_with_public_der(their_identity_der, their_ephemeral_der, their_signature);
    if (!valid) {
        ESP_LOGE(TAG, "Firma inválida de %s, cerrando conexión", their_id.c_str());
        beam_crypto::free_ephemeral(ephemeral);
        close(fd);
        return;
    }

    auto shared_secret = beam_crypto::ecdh_shared_secret(ephemeral, their_ephemeral_der);
    beam_crypto::free_ephemeral(ephemeral);
    if (shared_secret.empty()) { close(fd); return; }

    auto channel = std::make_unique<beam_crypto::SecureChannel>(shared_secret);

    if (!g_bonded_android_id.empty() && g_bonded_android_id != their_id) {
        // Ya vinculados con OTRO Android: rechazar, este no es el nuestro.
        ESP_LOGI(TAG, "Rechazando a %s: ya vinculado con %s", their_id.c_str(), g_bonded_android_id.c_str());
        auto rejected = channel->encrypt(build_link_state_changed_desvinculado());
        write_framed(fd, rejected);
        close(fd);
        return;
    }

    if (g_bonded_android_id.empty()) {
        // FABRICA: conocer las credenciales del AP ya es la prueba de autorización.
        save_bonded_id(their_id);
        ESP_LOGI(TAG, "Vinculado con %s", their_id.c_str());
    }

    register_peer(their_id, fd, std::move(channel));
}

void accept_task(void*) {
    while (true) {
        struct sockaddr_in client_addr{};
        socklen_t len = sizeof(client_addr);
        int client_fd = accept(g_tcp_server_socket, (struct sockaddr*)&client_addr, &len);
        if (client_fd < 0) continue;
        // El handshake puede bloquear un rato; que no frene el accept() de otros intentos.
        xTaskCreate([](void* arg) {
            int fd = reinterpret_cast<intptr_t>(arg);
            perform_handshake_as_acceptor(fd);
            vTaskDelete(nullptr);
        }, "beam_accept_hs", 6144, reinterpret_cast<void*>(static_cast<intptr_t>(client_fd)), 5, nullptr);
    }
}

void beacon_send_task(void*) {
    struct sockaddr_in target{};
    target.sin_family = AF_INET;
    target.sin_port = htons(BEAM_BEACON_PORT);
    target.sin_addr.s_addr = htonl(INADDR_BROADCAST);

    while (true) {
        cJSON* beacon = cJSON_CreateObject();
        cJSON_AddStringToObject(beacon, "id", beam_crypto::device_id().c_str());
        cJSON_AddStringToObject(beacon, "name", g_device_name.c_str());
        cJSON_AddStringToObject(beacon, "kind", "PINGANILLO");
        cJSON_AddNumberToObject(beacon, "tcpPort", BEAM_TCP_PORT);
        char* text = cJSON_PrintUnformatted(beacon);
        if (text) {
            sendto(g_udp_socket, text, strlen(text), 0, (struct sockaddr*)&target, sizeof(target));
            cJSON_free(text);
        }
        cJSON_Delete(beacon);
        vTaskDelay(pdMS_TO_TICKS(BEAM_BEACON_INTERVAL_MS));
    }
}

} // namespace

void start(const std::string& device_name, MessageHandler on_message) {
    g_device_name = device_name;
    g_on_message = std::move(on_message);
    g_peers_mutex = xSemaphoreCreateMutex();
    load_bonded_id();

    g_udp_socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    int enable = 1;
    setsockopt(g_udp_socket, SOL_SOCKET, SO_BROADCAST, &enable, sizeof(enable));

    g_tcp_server_socket = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    setsockopt(g_tcp_server_socket, SOL_SOCKET, SO_REUSEADDR, &enable, sizeof(enable));
    struct sockaddr_in tcp_addr{};
    tcp_addr.sin_family = AF_INET;
    tcp_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    tcp_addr.sin_port = htons(BEAM_TCP_PORT);
    bind(g_tcp_server_socket, (struct sockaddr*)&tcp_addr, sizeof(tcp_addr));
    listen(g_tcp_server_socket, 4);

    xTaskCreate(beacon_send_task, "beam_beacon_tx", 4096, nullptr, 4, nullptr);
    xTaskCreate(accept_task, "beam_accept", 4096, nullptr, 5, nullptr);

    ESP_LOGI(TAG, "Beam iniciado: id=%s tcpPort=%d beaconPort=%d bonded=%s",
        beam_crypto::device_id().c_str(), BEAM_TCP_PORT, BEAM_BEACON_PORT,
        g_bonded_android_id.empty() ? "no (FABRICA)" : g_bonded_android_id.c_str());
}

int send_to_all(const std::vector<uint8_t>& data) {
    int sent = 0;
    xSemaphoreTake(g_peers_mutex, portMAX_DELAY);
    for (auto& p : g_peers) {
        auto encrypted = p.channel->encrypt(data);
        if (write_framed(p.fd, encrypted)) sent++;
    }
    xSemaphoreGive(g_peers_mutex);
    return sent;
}

int connected_peer_count() {
    xSemaphoreTake(g_peers_mutex, portMAX_DELAY);
    int count = static_cast<int>(g_peers.size());
    xSemaphoreGive(g_peers_mutex);
    return count;
}

bool is_bonded() { return !g_bonded_android_id.empty(); }

void unlink_and_restart() {
    xSemaphoreTake(g_peers_mutex, portMAX_DELAY);
    for (auto& p : g_peers) {
        auto encrypted = p.channel->encrypt(build_link_state_changed_desvinculado());
        write_framed(p.fd, encrypted);
    }
    xSemaphoreGive(g_peers_mutex);

    clear_bonded_id();
    ESP_LOGI(TAG, "Desvinculado por pulsación larga; reiniciando a FABRICA");
    vTaskDelay(pdMS_TO_TICKS(200)); // deja salir el aviso por el socket antes de reiniciar
    esp_restart();
}

} // namespace beam_protocol
