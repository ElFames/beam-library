// Programa de prueba MANUAL, para macOS/Linux, SIN nada de ESP32 de por medio.
//
// Reproduce exactamente el handshake criptográfico que hace el firmware real
// (esp32-pinganillo/main/beam_crypto.cpp + beam_protocol.cpp) — misma curva EC
// P-256, mismo ECDH, misma firma ECDSA SHA-256, mismo AES-256-GCM, mismo
// framing de 4 bytes — pero usando sockets y librerías normales de escritorio
// en vez de ESP-IDF, para poder validar la interoperabilidad con el lado
// Kotlin SIN tener que comprar/flashear un ESP32 todavía.
//
// Se conecta directamente a 127.0.0.1:9999 (el puerto donde escuchará el
// harness de Kotlin, ver ../../beam/src/jvmTest/.../ManualHandshakeHarness.kt),
// hace de "iniciador" del handshake (como si fuera el pinganillo conectando
// hacia el móvil), manda un mensaje de texto cifrado y espera la respuesta.
//
// Si este código y el firmware real alguna vez se desincronizan, la señal es
// clara: este test empezará a fallar el mismo día que el firmware real lo haría.
//
// Compilar: ver build.sh en esta misma carpeta.

// mbedTLS >= 3.0 renombra los campos internos de sus structs (X, Y, Z, grp, Q, d...)
// tras la macro MBEDTLS_PRIVATE a menos que se pida explícitamente acceso directo
// "a la antigua" — necesario aquí porque accedemos a mine->grp/Q/d a mano para el ECDH.
#define MBEDTLS_ALLOW_PRIVATE_ACCESS

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#include "cjson/cJSON.h"
#include "mbedtls/base64.h"
#include "mbedtls/ctr_drbg.h"
#include "mbedtls/ecdh.h"
#include "mbedtls/entropy.h"
#include "mbedtls/gcm.h"
#include "mbedtls/pk.h"
#include "mbedtls/sha256.h"

namespace {

constexpr char SHARED_SALT[] = "8Gf9xY3sP8aR5jP3vH11H9qC0yJ6nN8z"; // debe coincidir con SecureChannel.kt
constexpr int TCP_PORT = 9999;

mbedtls_entropy_context g_entropy;
mbedtls_ctr_drbg_context g_rng;

int rng_cb(void* ctx, unsigned char* out, size_t len) {
    return mbedtls_ctr_drbg_random(ctx, out, len);
}

std::vector<uint8_t> write_pubkey_der(mbedtls_pk_context* pk) {
    std::vector<uint8_t> buf(256);
    int len = mbedtls_pk_write_pubkey_der(pk, buf.data(), buf.size());
    if (len < 0) { std::cerr << "write_pubkey_der falló: -0x" << std::hex << -len << "\n"; return {}; }
    return std::vector<uint8_t>(buf.end() - len, buf.end());
}

std::vector<uint8_t> sha256(const std::vector<uint8_t>& data) {
    std::vector<uint8_t> out(32);
    mbedtls_sha256(data.data(), data.size(), out.data(), 0);
    return out;
}

std::string to_base64(const std::vector<uint8_t>& bytes) {
    size_t needed = 0;
    mbedtls_base64_encode(nullptr, 0, &needed, bytes.data(), bytes.size());
    std::vector<uint8_t> buf(needed);
    size_t written = 0;
    mbedtls_base64_encode(buf.data(), buf.size(), &written, bytes.data(), bytes.size());
    return std::string(buf.begin(), buf.begin() + written);
}

std::vector<uint8_t> from_base64(const std::string& text) {
    size_t needed = 0;
    mbedtls_base64_decode(nullptr, 0, &needed, (const unsigned char*)text.data(), text.size());
    std::vector<uint8_t> out(needed);
    size_t written = 0;
    mbedtls_base64_decode(out.data(), out.size(), &written, (const unsigned char*)text.data(), text.size());
    out.resize(written);
    return out;
}

std::string device_id_from_public_der(const std::vector<uint8_t>& pub_der) {
    auto digest = sha256(pub_der);
    char hex[17];
    for (int i = 0; i < 8; i++) snprintf(hex + i * 2, 3, "%02x", digest[i]);
    return std::string(hex, 16);
}

bool read_exact(int fd, uint8_t* buf, size_t len) {
    size_t r = 0;
    while (r < len) {
        ssize_t n = recv(fd, buf + r, len - r, 0);
        if (n <= 0) return false;
        r += n;
    }
    return true;
}

bool write_exact(int fd, const uint8_t* buf, size_t len) {
    size_t w = 0;
    while (w < len) {
        ssize_t n = send(fd, buf + w, len - w, 0);
        if (n <= 0) return false;
        w += n;
    }
    return true;
}

bool write_framed(int fd, const std::vector<uint8_t>& bytes) {
    uint32_t size = (uint32_t)bytes.size();
    uint8_t header[4] = {
        (uint8_t)(size >> 24), (uint8_t)(size >> 16), (uint8_t)(size >> 8), (uint8_t)size
    };
    return write_exact(fd, header, 4) && write_exact(fd, bytes.data(), bytes.size());
}

bool read_framed(int fd, std::vector<uint8_t>& out) {
    uint8_t header[4];
    if (!read_exact(fd, header, 4)) return false;
    uint32_t size = ((uint32_t)header[0] << 24) | (header[1] << 16) | (header[2] << 8) | header[3];
    out.resize(size);
    return size == 0 || read_exact(fd, out.data(), size);
}

bool write_json(int fd, cJSON* obj) {
    char* text = cJSON_PrintUnformatted(obj);
    if (!text) return false;
    std::vector<uint8_t> bytes(text, text + strlen(text));
    cJSON_free(text);
    return write_framed(fd, bytes);
}

cJSON* read_json(int fd) {
    std::vector<uint8_t> bytes;
    if (!read_framed(fd, bytes)) return nullptr;
    std::string text(bytes.begin(), bytes.end());
    return cJSON_Parse(text.c_str());
}

// Misma clase que SecureChannel.kt / beam_crypto.cpp: IV(12) || ciphertext || tag(16).
class SecureChannel {
public:
    explicit SecureChannel(const std::vector<uint8_t>& shared_secret_raw) {
        std::vector<uint8_t> salted(shared_secret_raw);
        salted.insert(salted.end(), SHARED_SALT, SHARED_SALT + strlen(SHARED_SALT));
        auto digest = sha256(salted);
        memcpy(key_, digest.data(), 32);
    }

    std::vector<uint8_t> encrypt(const std::vector<uint8_t>& data) {
        uint8_t iv[12];
        mbedtls_ctr_drbg_random(&g_rng, iv, sizeof(iv));
        std::vector<uint8_t> ciphertext(data.size());
        uint8_t tag[16];

        mbedtls_gcm_context gcm;
        mbedtls_gcm_init(&gcm);
        mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, key_, 256);
        mbedtls_gcm_crypt_and_tag(&gcm, MBEDTLS_GCM_ENCRYPT, data.size(), iv, sizeof(iv),
            nullptr, 0, data.data(), ciphertext.data(), sizeof(tag), tag);
        mbedtls_gcm_free(&gcm);

        std::vector<uint8_t> out;
        out.insert(out.end(), iv, iv + 12);
        out.insert(out.end(), ciphertext.begin(), ciphertext.end());
        out.insert(out.end(), tag, tag + 16);
        return out;
    }

    std::vector<uint8_t> decrypt(const std::vector<uint8_t>& iv_ct_tag) {
        if (iv_ct_tag.size() < 28) return {};
        const uint8_t* iv = iv_ct_tag.data();
        size_t body_len = iv_ct_tag.size() - 12 - 16;
        const uint8_t* ciphertext = iv_ct_tag.data() + 12;
        const uint8_t* tag = iv_ct_tag.data() + 12 + body_len;
        std::vector<uint8_t> plaintext(body_len);

        mbedtls_gcm_context gcm;
        mbedtls_gcm_init(&gcm);
        mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, key_, 256);
        int rc = mbedtls_gcm_auth_decrypt(&gcm, body_len, iv, 12, nullptr, 0, tag, 16, ciphertext, plaintext.data());
        mbedtls_gcm_free(&gcm);
        return rc == 0 ? plaintext : std::vector<uint8_t>{};
    }

private:
    uint8_t key_[32];
};

} // namespace

int main() {
    mbedtls_entropy_init(&g_entropy);
    mbedtls_ctr_drbg_init(&g_rng);
    const char* pers = "handshake_test";
    mbedtls_ctr_drbg_seed(&g_rng, mbedtls_entropy_func, &g_entropy, (const unsigned char*)pers, strlen(pers));

    // --- 1) Identidad "de pinganillo" (efímera para este test, no se persiste) ---
    mbedtls_pk_context identity_pk;
    mbedtls_pk_init(&identity_pk);
    mbedtls_pk_setup(&identity_pk, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY));
    if (mbedtls_ecp_gen_key(MBEDTLS_ECP_DP_SECP256R1, mbedtls_pk_ec(identity_pk), rng_cb, &g_rng) != 0) {
        std::cerr << "No se pudo generar la identidad EC\n";
        return 1;
    }
    auto identity_pub_der = write_pubkey_der(&identity_pk);
    std::string my_id = device_id_from_public_der(identity_pub_der);
    std::cout << "[native] device_id = " << my_id << "\n";

    // --- 2) Conectar por TCP a donde escucha el harness de Kotlin ---
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(TCP_PORT);
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);
    std::cout << "[native] conectando a 127.0.0.1:" << TCP_PORT << "...\n";
    if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) != 0) {
        std::cerr << "No se pudo conectar. ¿Está corriendo ManualHandshakeHarness en la JVM?\n";
        return 1;
    }

    // --- 3) HandshakeHello (como iniciador: mando primero) ---
    // "kind": "ANDROID" porque en esta prueba el harness de Kotlin arranca como
    // PINGANILLO (para ejercitar su rama de auto-vinculación como acceptor) — este
    // programa nativo hace de "el móvil" que se conecta a él, aunque el nombre del
    // binario diga "pinganillo simulado" (es al revés que en el firmware real).
    cJSON* my_hello = cJSON_CreateObject();
    cJSON_AddStringToObject(my_hello, "id", my_id.c_str());
    cJSON_AddStringToObject(my_hello, "name", "pinganillo-simulado");
    cJSON_AddStringToObject(my_hello, "kind", "ANDROID");
    cJSON_AddStringToObject(my_hello, "publicKeyBase64", to_base64(identity_pub_der).c_str());
    if (!write_json(fd, my_hello)) { std::cerr << "Fallo enviando HandshakeHello\n"; return 1; }
    cJSON_Delete(my_hello);

    cJSON* their_hello = read_json(fd);
    if (!their_hello) { std::cerr << "No llegó HandshakeHello del otro lado\n"; return 1; }
    std::string their_id = cJSON_GetObjectItem(their_hello, "id")->valuestring;
    std::string their_pub_b64 = cJSON_GetObjectItem(their_hello, "publicKeyBase64")->valuestring;
    std::cout << "[native] Hola de " << cJSON_GetObjectItem(their_hello, "name")->valuestring
              << " (id=" << their_id << ")\n";
    cJSON_Delete(their_hello);

    // --- 4) EphemeralOffer: clave efímera firmada con la clave de identidad ---
    mbedtls_pk_context ephemeral_pk;
    mbedtls_pk_init(&ephemeral_pk);
    mbedtls_pk_setup(&ephemeral_pk, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY));
    mbedtls_ecp_gen_key(MBEDTLS_ECP_DP_SECP256R1, mbedtls_pk_ec(ephemeral_pk), rng_cb, &g_rng);
    auto ephemeral_pub_der = write_pubkey_der(&ephemeral_pk);

    auto digest = sha256(ephemeral_pub_der);
    uint8_t sig[MBEDTLS_ECDSA_MAX_LEN];
    size_t sig_len = 0;
    mbedtls_pk_sign(&identity_pk, MBEDTLS_MD_SHA256, digest.data(), digest.size(), sig, sizeof(sig), &sig_len, rng_cb, &g_rng);
    std::vector<uint8_t> signature(sig, sig + sig_len);

    cJSON* my_offer = cJSON_CreateObject();
    cJSON_AddStringToObject(my_offer, "publicKeyBase64", to_base64(ephemeral_pub_der).c_str());
    cJSON_AddStringToObject(my_offer, "signatureBase64", to_base64(signature).c_str());
    write_json(fd, my_offer);
    cJSON_Delete(my_offer);

    cJSON* their_offer = read_json(fd);
    if (!their_offer) { std::cerr << "No llegó EphemeralOffer del otro lado\n"; return 1; }
    std::string their_eph_b64 = cJSON_GetObjectItem(their_offer, "publicKeyBase64")->valuestring;
    std::string their_sig_b64 = cJSON_GetObjectItem(their_offer, "signatureBase64")->valuestring;
    cJSON_Delete(their_offer);

    auto their_identity_der = from_base64(their_pub_b64);
    auto their_ephemeral_der = from_base64(their_eph_b64);
    auto their_signature = from_base64(their_sig_b64);

    // --- 5) Verificar su firma con SU clave de identidad ---
    mbedtls_pk_context their_identity_pk;
    mbedtls_pk_init(&their_identity_pk);
    mbedtls_pk_parse_public_key(&their_identity_pk, their_identity_der.data(), their_identity_der.size());
    auto their_eph_digest = sha256(their_ephemeral_der);
    bool valid = mbedtls_pk_verify(&their_identity_pk, MBEDTLS_MD_SHA256,
        their_eph_digest.data(), their_eph_digest.size(),
        their_signature.data(), their_signature.size()) == 0;
    std::cout << "[native] Firma del otro lado: " << (valid ? "VÁLIDA ✅" : "INVÁLIDA ❌") << "\n";
    if (!valid) return 1;

    // --- 6) ECDH + AES-GCM ---
    mbedtls_pk_context their_ephemeral_pk;
    mbedtls_pk_init(&their_ephemeral_pk);
    mbedtls_pk_parse_public_key(&their_ephemeral_pk, their_ephemeral_der.data(), their_ephemeral_der.size());

    mbedtls_ecp_keypair* mine = mbedtls_pk_ec(ephemeral_pk);
    mbedtls_ecp_keypair* theirs = mbedtls_pk_ec(their_ephemeral_pk);
    mbedtls_mpi shared;
    mbedtls_mpi_init(&shared);
    mbedtls_ecdh_compute_shared(&mine->grp, &shared, &theirs->Q, &mine->d, rng_cb, &g_rng);
    std::vector<uint8_t> shared_secret(32);
    mbedtls_mpi_write_binary(&shared, shared_secret.data(), shared_secret.size());
    mbedtls_mpi_free(&shared);

    SecureChannel channel(shared_secret);
    std::cout << "[native] Canal cifrado establecido. Mandando mensaje de prueba...\n";

    // --- 7) Mandar un mensaje cifrado (mismo formato que TestMessage en el harness) ---
    cJSON* msg = cJSON_CreateObject();
    cJSON_AddStringToObject(msg, "text", "hola desde el pinganillo simulado");
    char* msg_text = cJSON_PrintUnformatted(msg);
    std::vector<uint8_t> plaintext(msg_text, msg_text + strlen(msg_text));
    cJSON_free(msg_text);
    cJSON_Delete(msg);

    write_framed(fd, channel.encrypt(plaintext));

    // --- 8) Esperar la respuesta cifrada y descifrarla ---
    std::vector<uint8_t> response_framed;
    if (!read_framed(fd, response_framed)) { std::cerr << "No llegó respuesta\n"; return 1; }
    auto response_plain = channel.decrypt(response_framed);
    if (response_plain.empty()) {
        std::cerr << "❌ La respuesta no se pudo descifrar — el AES-GCM NO es compatible\n";
        return 1;
    }
    std::string response_text(response_plain.begin(), response_plain.end());
    std::cout << "[native] ✅ Respuesta descifrada correctamente: " << response_text << "\n";
    std::cout << "\n=== TODO OK: el handshake y el cifrado son compatibles con Kotlin ===\n";

    close(fd);
    return 0;
}
