#include "beam_protocol.h"
#include "beam_crypto.h"
#include "config.h"

#include <algorithm>
#include <cstring>
#include <memory>
#include <vector>

#include "cJSON.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "lwip/sockets.h"
#include "lwip/inet.h"

namespace beam_protocol {

namespace {
constexpr char TAG[] = "beam_protocol";

std::string g_device_name;
MessageHandler g_on_message;

struct Peer {
    std::string id;
    int fd;
    std::unique_ptr<beam_crypto::SecureChannel> channel;
};

std::vector<Peer> g_peers;
std::vector<std::string> g_connecting_ids;
SemaphoreHandle_t g_peers_mutex;

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
// Peers
// ---------------------------------------------------------------------------

bool is_known_peer_locked(const std::string& id) {
    for (auto& p : g_peers) if (p.id == id) return true;
    for (auto& id2 : g_connecting_ids) if (id2 == id) return true;
    return false;
}

void mark_connecting(const std::string& id) {
    xSemaphoreTake(g_peers_mutex, portMAX_DELAY);
    g_connecting_ids.push_back(id);
    xSemaphoreGive(g_peers_mutex);
}

void unmark_connecting(const std::string& id) {
    xSemaphoreTake(g_peers_mutex, portMAX_DELAY);
    g_connecting_ids.erase(
        std::remove(g_connecting_ids.begin(), g_connecting_ids.end(), id), g_connecting_ids.end());
    xSemaphoreGive(g_peers_mutex);
}

void register_peer(const std::string& id, int fd, std::unique_ptr<beam_crypto::SecureChannel> channel);
void read_loop_task(void* arg);

struct ReadLoopArgs { std::string id; int fd; };

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

void register_peer(const std::string& id, int fd, std::unique_ptr<beam_crypto::SecureChannel> channel) {
    xSemaphoreTake(g_peers_mutex, portMAX_DELAY);
    g_peers.push_back(Peer{id, fd, std::move(channel)});
    xSemaphoreGive(g_peers_mutex);
    ESP_LOGI(TAG, "Peer conectado: %s (total=%d)", id.c_str(), (int)g_peers.size());

    auto* args = new ReadLoopArgs{id, fd};
    xTaskCreate(read_loop_task, "beam_read", 4096, args, 5, nullptr);
}

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
        if (g_on_message) g_on_message(args->id, plaintext);
    }
    cleanup_peer(args->id);
    vTaskDelete(nullptr);
}

// ---------------------------------------------------------------------------
// Handshake — réplica exacta de performHandshake en MeshBeamConnection.kt
// ---------------------------------------------------------------------------

void perform_handshake(int fd, bool is_initiator) {
    std::string my_id = beam_crypto::device_id();

    cJSON* my_hello = cJSON_CreateObject();
    cJSON_AddStringToObject(my_hello, "id", my_id.c_str());
    cJSON_AddStringToObject(my_hello, "name", g_device_name.c_str());
    cJSON_AddStringToObject(my_hello, "publicKeyBase64",
        beam_crypto::to_base64(beam_crypto::identity_public_der()).c_str());

    cJSON* their_hello = nullptr;
    if (is_initiator) {
        if (!write_plain_json(fd, my_hello)) { cJSON_Delete(my_hello); close(fd); return; }
        their_hello = read_plain_json(fd);
    } else {
        their_hello = read_plain_json(fd);
        if (their_hello) write_plain_json(fd, my_hello);
    }
    cJSON_Delete(my_hello);
    if (!their_hello) { close(fd); return; }

    std::string their_id = cJSON_GetObjectItem(their_hello, "id")->valuestring;
    std::string their_name = cJSON_GetObjectItem(their_hello, "name")->valuestring;
    std::string their_pub_b64 = cJSON_GetObjectItem(their_hello, "publicKeyBase64")->valuestring;
    cJSON_Delete(their_hello);

    if (their_id == my_id) { close(fd); return; }

    auto* ephemeral = beam_crypto::generate_ephemeral();
    auto ephemeral_pub_der = beam_crypto::ephemeral_public_der(ephemeral);
    auto signature = beam_crypto::sign_with_identity(ephemeral_pub_der);

    cJSON* my_offer = cJSON_CreateObject();
    cJSON_AddStringToObject(my_offer, "publicKeyBase64", beam_crypto::to_base64(ephemeral_pub_der).c_str());
    cJSON_AddStringToObject(my_offer, "signatureBase64", beam_crypto::to_base64(signature).c_str());

    cJSON* their_offer = nullptr;
    if (is_initiator) {
        if (!write_plain_json(fd, my_offer)) { cJSON_Delete(my_offer); beam_crypto::free_ephemeral(ephemeral); close(fd); return; }
        their_offer = read_plain_json(fd);
    } else {
        their_offer = read_plain_json(fd);
        if (their_offer) write_plain_json(fd, my_offer);
    }
    cJSON_Delete(my_offer);
    if (!their_offer) { beam_crypto::free_ephemeral(ephemeral); close(fd); return; }

    std::string their_eph_b64 = cJSON_GetObjectItem(their_offer, "publicKeyBase64")->valuestring;
    std::string their_sig_b64 = cJSON_GetObjectItem(their_offer, "signatureBase64")->valuestring;
    cJSON_Delete(their_offer);

    auto their_identity_pub_der = beam_crypto::from_base64(their_pub_b64);
    auto their_ephemeral_der = beam_crypto::from_base64(their_eph_b64);
    auto their_signature = beam_crypto::from_base64(their_sig_b64);

    bool valid = beam_crypto::verify_with_public_der(their_identity_pub_der, their_ephemeral_der, their_signature);
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
    // Sin TrustStore de por medio: el pinganillo confía en cualquiera que complete el
    // handshake criptográfico (la puerta de seguridad real la pone el móvil/desktop).
    register_peer(their_id, fd, std::move(channel));
}

// ---------------------------------------------------------------------------
// Discovery (beacon UDP)
// ---------------------------------------------------------------------------

int g_udp_socket = -1;
int g_tcp_server_socket = -1;

void attempt_connect(const std::string& host, int port, const std::string& expected_id) {
    mark_connecting(expected_id);
    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    inet_pton(AF_INET, host.c_str(), &addr.sin_addr);
    if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) == 0) {
        perform_handshake(fd, /*is_initiator=*/true);
    } else {
        ESP_LOGE(TAG, "No se pudo conectar a %s:%d", host.c_str(), port);
        close(fd);
    }
    unmark_connecting(expected_id);
}

struct ConnectTaskArgs { std::string host; int port; std::string id; };

void connect_task(void* arg) {
    std::unique_ptr<ConnectTaskArgs> args(static_cast<ConnectTaskArgs*>(arg));
    attempt_connect(args->host, args->port, args->id);
    vTaskDelete(nullptr);
}

void on_beacon_received(const std::string& id, const std::string& name, int tcp_port, const std::string& address) {
    (void)name;
    if (id == beam_crypto::device_id()) return;

    xSemaphoreTake(g_peers_mutex, portMAX_DELAY);
    bool known = is_known_peer_locked(id);
    xSemaphoreGive(g_peers_mutex);
    if (known) return;

    std::string my_id = beam_crypto::device_id();
    // Misma regla de arbitraje que en Kotlin: solo el id lexicográficamente menor conecta.
    if (my_id < id) {
        auto* args = new ConnectTaskArgs{address, tcp_port, id};
        xTaskCreate(connect_task, "beam_connect", 4096, args, 5, nullptr);
    }
    // Si mi id es mayor, no hago nada: espero a que el otro me conecte (acceptLoop).
}

void beacon_listen_task(void*) {
    char buf[512];
    while (true) {
        struct sockaddr_in from{};
        socklen_t from_len = sizeof(from);
        ssize_t n = recvfrom(g_udp_socket, buf, sizeof(buf) - 1, 0, (struct sockaddr*)&from, &from_len);
        if (n <= 0) continue;
        buf[n] = '\0';

        cJSON* json = cJSON_Parse(buf);
        if (!json) continue;
        cJSON* id_item = cJSON_GetObjectItem(json, "id");
        cJSON* name_item = cJSON_GetObjectItem(json, "name");
        cJSON* port_item = cJSON_GetObjectItem(json, "tcpPort");
        if (cJSON_IsString(id_item) && cJSON_IsString(name_item) && cJSON_IsNumber(port_item)) {
            char addr_str[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &from.sin_addr, addr_str, sizeof(addr_str));
            on_beacon_received(id_item->valuestring, name_item->valuestring, port_item->valueint, addr_str);
        }
        cJSON_Delete(json);
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

void accept_task(void*) {
    while (true) {
        struct sockaddr_in client_addr{};
        socklen_t len = sizeof(client_addr);
        int client_fd = accept(g_tcp_server_socket, (struct sockaddr*)&client_addr, &len);
        if (client_fd < 0) continue;
        // El handshake entrante puede bloquear un rato; que no frene el accept() de otros peers.
        xTaskCreate([](void* arg) {
            int fd = reinterpret_cast<intptr_t>(arg);
            perform_handshake(fd, /*is_initiator=*/false);
            vTaskDelete(nullptr);
        }, "beam_accept_hs", 6144, reinterpret_cast<void*>(static_cast<intptr_t>(client_fd)), 5, nullptr);
    }
}

} // namespace

void start(const std::string& device_name, MessageHandler on_message) {
    g_device_name = device_name;
    g_on_message = std::move(on_message);
    g_peers_mutex = xSemaphoreCreateMutex();

    g_udp_socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    int enable = 1;
    setsockopt(g_udp_socket, SOL_SOCKET, SO_BROADCAST, &enable, sizeof(enable));
    setsockopt(g_udp_socket, SOL_SOCKET, SO_REUSEADDR, &enable, sizeof(enable));
    struct sockaddr_in udp_addr{};
    udp_addr.sin_family = AF_INET;
    udp_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    udp_addr.sin_port = htons(BEAM_BEACON_PORT);
    bind(g_udp_socket, (struct sockaddr*)&udp_addr, sizeof(udp_addr));

    g_tcp_server_socket = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    setsockopt(g_tcp_server_socket, SOL_SOCKET, SO_REUSEADDR, &enable, sizeof(enable));
    struct sockaddr_in tcp_addr{};
    tcp_addr.sin_family = AF_INET;
    tcp_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    tcp_addr.sin_port = htons(BEAM_TCP_PORT);
    bind(g_tcp_server_socket, (struct sockaddr*)&tcp_addr, sizeof(tcp_addr));
    listen(g_tcp_server_socket, 4);

    xTaskCreate(beacon_send_task, "beam_beacon_tx", 4096, nullptr, 4, nullptr);
    xTaskCreate(beacon_listen_task, "beam_beacon_rx", 4096, nullptr, 4, nullptr);
    xTaskCreate(accept_task, "beam_accept", 4096, nullptr, 5, nullptr);

    ESP_LOGI(TAG, "Beam iniciado: id=%s tcpPort=%d beaconPort=%d",
        beam_crypto::device_id().c_str(), BEAM_TCP_PORT, BEAM_BEACON_PORT);
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

} // namespace beam_protocol
