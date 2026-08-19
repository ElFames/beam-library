#include "beam_crypto.h"
#include "config.h"

#include <cstdio>
#include <cstring>

#include "esp_log.h"
#include "nvs.h"
#include "mbedtls/ecdh.h"
#include "mbedtls/ecdsa.h"
#include "mbedtls/entropy.h"
#include "mbedtls/ctr_drbg.h"
#include "mbedtls/sha256.h"
#include "mbedtls/gcm.h"
#include "mbedtls/base64.h"
#include "mbedtls/md.h"

namespace beam_crypto {

namespace {
constexpr char TAG[] = "beam_crypto";

// Debe coincidir carácter a carácter con SecureChannel.SHARED_SALT en Kotlin.
constexpr char SHARED_SALT[] = "8Gf9xY3sP8aR5jP3vH11H9qC0yJ6nN8z";

mbedtls_entropy_context g_entropy;
mbedtls_ctr_drbg_context g_rng;

mbedtls_pk_context g_identity_pk;
std::vector<uint8_t> g_identity_public_der;
std::string g_device_id;

int rng_callback(void* ctx, unsigned char* out, size_t len) {
    return mbedtls_ctr_drbg_random(ctx, out, len);
}

/**
 * mbedtls_pk_write_pubkey_der / write_key_der escriben el DER al FINAL del
 * buffer que se les pasa (crecen hacia atrás) y devuelven la longitud escrita;
 * el DER real empieza en buf + buf_size - len. Este helper deja el resultado
 * ya recortado y en orden normal.
 */
std::vector<uint8_t> write_pubkey_der(mbedtls_pk_context* pk) {
    std::vector<uint8_t> buf(256);
    int len = mbedtls_pk_write_pubkey_der(pk, buf.data(), buf.size());
    if (len < 0) {
        ESP_LOGE(TAG, "mbedtls_pk_write_pubkey_der falló: -0x%04x", -len);
        return {};
    }
    return std::vector<uint8_t>(buf.end() - len, buf.end());
}

bool nvs_read_blob(nvs_handle_t handle, const char* key, std::vector<uint8_t>& out) {
    size_t size = 0;
    if (nvs_get_blob(handle, key, nullptr, &size) != ESP_OK || size == 0) return false;
    out.resize(size);
    return nvs_get_blob(handle, key, out.data(), &size) == ESP_OK;
}

void generate_and_persist_identity() {
    mbedtls_pk_setup(&g_identity_pk, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY));
    int rc = mbedtls_ecp_gen_key(
        MBEDTLS_ECP_DP_SECP256R1,
        mbedtls_pk_ec(g_identity_pk),
        rng_callback, &g_rng
    );
    if (rc != 0) {
        ESP_LOGE(TAG, "No se pudo generar la identidad EC: -0x%04x", -rc);
        return;
    }

    uint8_t priv_raw[32];
    mbedtls_mpi_write_binary(&mbedtls_pk_ec(g_identity_pk)->d, priv_raw, sizeof(priv_raw));
    g_identity_public_der = write_pubkey_der(&g_identity_pk);

    nvs_handle_t handle;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &handle) == ESP_OK) {
        nvs_set_blob(handle, NVS_KEY_EC_PRIVATE, priv_raw, sizeof(priv_raw));
        nvs_set_blob(handle, NVS_KEY_EC_PUBLIC, g_identity_public_der.data(), g_identity_public_der.size());
        nvs_commit(handle);
        nvs_close(handle);
    }
    ESP_LOGI(TAG, "Identidad EC nueva generada y persistida en NVS");
}

bool load_persisted_identity() {
    nvs_handle_t handle;
    if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &handle) != ESP_OK) return false;

    std::vector<uint8_t> priv_raw, pub_der;
    bool ok = nvs_read_blob(handle, NVS_KEY_EC_PRIVATE, priv_raw) &&
              nvs_read_blob(handle, NVS_KEY_EC_PUBLIC, pub_der);
    nvs_close(handle);
    if (!ok || priv_raw.size() != 32) return false;

    mbedtls_pk_setup(&g_identity_pk, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY));
    if (mbedtls_pk_parse_public_key(&g_identity_pk, pub_der.data(), pub_der.size()) != 0) return false;
    if (mbedtls_mpi_read_binary(&mbedtls_pk_ec(g_identity_pk)->d, priv_raw.data(), priv_raw.size()) != 0) return false;

    g_identity_public_der = pub_der;
    ESP_LOGI(TAG, "Identidad EC cargada de NVS");
    return true;
}

} // namespace

void init() {
    mbedtls_entropy_init(&g_entropy);
    mbedtls_ctr_drbg_init(&g_rng);
    const char* pers = "pinganillo-beam";
    mbedtls_ctr_drbg_seed(&g_rng, mbedtls_entropy_func, &g_entropy,
        reinterpret_cast<const unsigned char*>(pers), strlen(pers));
}

void load_or_create_identity() {
    mbedtls_pk_init(&g_identity_pk);
    if (!load_persisted_identity()) {
        generate_and_persist_identity();
    }
    // Igual que deviceIdFromPublicKey en Kotlin: hex en minúsculas del sha256 de la
    // clave pública DER, recortado a 16 caracteres (8 bytes).
    auto digest = sha256(g_identity_public_der);
    char hex[17];
    for (int i = 0; i < 8; i++) snprintf(hex + i * 2, 3, "%02x", digest[i]);
    g_device_id = std::string(hex, 16);
    ESP_LOGI(TAG, "device_id = %s", g_device_id.c_str());
}

std::string device_id() { return g_device_id; }

std::vector<uint8_t> identity_public_der() { return g_identity_public_der; }

std::vector<uint8_t> sha256(const std::vector<uint8_t>& data) {
    std::vector<uint8_t> out(32);
    mbedtls_sha256(data.data(), data.size(), out.data(), 0);
    return out;
}

std::vector<uint8_t> sign_with_identity(const std::vector<uint8_t>& data) {
    auto digest = sha256(data);
    uint8_t sig[MBEDTLS_ECDSA_MAX_LEN];
    size_t sig_len = 0;
    int rc = mbedtls_pk_sign(
        &g_identity_pk, MBEDTLS_MD_SHA256,
        digest.data(), digest.size(),
        sig, sizeof(sig), &sig_len,
        rng_callback, &g_rng
    );
    if (rc != 0) {
        ESP_LOGE(TAG, "Fallo firmando: -0x%04x", -rc);
        return {};
    }
    return std::vector<uint8_t>(sig, sig + sig_len);
}

bool verify_with_public_der(
    const std::vector<uint8_t>& public_key_der,
    const std::vector<uint8_t>& data,
    const std::vector<uint8_t>& signature_der
) {
    mbedtls_pk_context pk;
    mbedtls_pk_init(&pk);
    bool ok = false;
    if (mbedtls_pk_parse_public_key(&pk, public_key_der.data(), public_key_der.size()) == 0) {
        auto digest = sha256(data);
        ok = mbedtls_pk_verify(
            &pk, MBEDTLS_MD_SHA256,
            digest.data(), digest.size(),
            signature_der.data(), signature_der.size()
        ) == 0;
    }
    mbedtls_pk_free(&pk);
    return ok;
}

struct Ephemeral {
    mbedtls_pk_context pk;
};

Ephemeral* generate_ephemeral() {
    auto* eph = new Ephemeral();
    mbedtls_pk_init(&eph->pk);
    mbedtls_pk_setup(&eph->pk, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY));
    int rc = mbedtls_ecp_gen_key(MBEDTLS_ECP_DP_SECP256R1, mbedtls_pk_ec(eph->pk), rng_callback, &g_rng);
    if (rc != 0) {
        ESP_LOGE(TAG, "No se pudo generar la clave efímera: -0x%04x", -rc);
    }
    return eph;
}

void free_ephemeral(Ephemeral* eph) {
    if (!eph) return;
    mbedtls_pk_free(&eph->pk);
    delete eph;
}

std::vector<uint8_t> ephemeral_public_der(Ephemeral* eph) {
    return write_pubkey_der(&eph->pk);
}

std::vector<uint8_t> ecdh_shared_secret(Ephemeral* my_ephemeral, const std::vector<uint8_t>& other_public_der) {
    mbedtls_pk_context their_pk;
    mbedtls_pk_init(&their_pk);
    if (mbedtls_pk_parse_public_key(&their_pk, other_public_der.data(), other_public_der.size()) != 0) {
        ESP_LOGE(TAG, "Clave pública ajena inválida en ECDH");
        mbedtls_pk_free(&their_pk);
        return {};
    }

    mbedtls_ecp_keypair* mine = mbedtls_pk_ec(my_ephemeral->pk);
    mbedtls_ecp_keypair* theirs = mbedtls_pk_ec(their_pk);

    mbedtls_mpi shared;
    mbedtls_mpi_init(&shared);
    int rc = mbedtls_ecdh_compute_shared(&mine->grp, &shared, &theirs->Q, &mine->d, rng_callback, &g_rng);

    std::vector<uint8_t> out;
    if (rc == 0) {
        // Igual que KeyAgreement("ECDH").generateSecret() en Java: X del punto compartido,
        // big-endian, con relleno de ceros a la izquierda hasta el tamaño del campo (32B para P-256).
        out.resize(32);
        mbedtls_mpi_write_binary(&shared, out.data(), out.size());
    } else {
        ESP_LOGE(TAG, "ECDH falló: -0x%04x", -rc);
    }

    mbedtls_mpi_free(&shared);
    mbedtls_pk_free(&their_pk);
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
    mbedtls_base64_decode(nullptr, 0, &needed,
        reinterpret_cast<const unsigned char*>(text.data()), text.size());
    std::vector<uint8_t> out(needed);
    size_t written = 0;
    mbedtls_base64_decode(out.data(), out.size(), &written,
        reinterpret_cast<const unsigned char*>(text.data()), text.size());
    out.resize(written);
    return out;
}

// ---------------------------------------------------------------------------
// SecureChannel
// ---------------------------------------------------------------------------

SecureChannel::SecureChannel(const std::vector<uint8_t>& shared_secret_raw) {
    std::vector<uint8_t> salted(shared_secret_raw);
    salted.insert(salted.end(), SHARED_SALT, SHARED_SALT + strlen(SHARED_SALT));
    auto digest = sha256(salted);
    memcpy(key_, digest.data(), 32);
}

std::vector<uint8_t> SecureChannel::encrypt(const std::vector<uint8_t>& data) {
    uint8_t iv[12];
    mbedtls_ctr_drbg_random(&g_rng, iv, sizeof(iv));

    std::vector<uint8_t> ciphertext(data.size());
    uint8_t tag[16];

    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);
    mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, key_, 256);
    mbedtls_gcm_crypt_and_tag(
        &gcm, MBEDTLS_GCM_ENCRYPT, data.size(),
        iv, sizeof(iv), nullptr, 0,
        data.data(), ciphertext.data(),
        sizeof(tag), tag
    );
    mbedtls_gcm_free(&gcm);

    // Igual que Java: IV(12) || ciphertext || tag(16) concatenados en un solo array.
    std::vector<uint8_t> out;
    out.reserve(12 + ciphertext.size() + 16);
    out.insert(out.end(), iv, iv + 12);
    out.insert(out.end(), ciphertext.begin(), ciphertext.end());
    out.insert(out.end(), tag, tag + 16);
    return out;
}

std::vector<uint8_t> SecureChannel::decrypt(const std::vector<uint8_t>& iv_ciphertext_tag) {
    if (iv_ciphertext_tag.size() < 12 + 16) return {};
    const uint8_t* iv = iv_ciphertext_tag.data();
    size_t body_len = iv_ciphertext_tag.size() - 12 - 16;
    const uint8_t* ciphertext = iv_ciphertext_tag.data() + 12;
    const uint8_t* tag = iv_ciphertext_tag.data() + 12 + body_len;

    std::vector<uint8_t> plaintext(body_len);

    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);
    mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, key_, 256);
    int rc = mbedtls_gcm_auth_decrypt(
        &gcm, body_len,
        iv, 12, nullptr, 0,
        tag, 16,
        ciphertext, plaintext.data()
    );
    mbedtls_gcm_free(&gcm);

    if (rc != 0) return {};
    return plaintext;
}

} // namespace beam_crypto
