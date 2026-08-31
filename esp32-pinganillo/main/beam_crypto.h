#pragma once

// mbedTLS >= 3.0 renombra los campos internos de sus structs (X, Y, Z, grp, Q, d...)
// tras la macro MBEDTLS_PRIVATE a menos que se pida explícitamente acceso directo
// "a la antigua" — necesario porque beam_crypto.cpp accede a mine->grp/Q/d a mano
// para el ECDH en vez de pasar por la API de más alto nivel.
#define MBEDTLS_ALLOW_PRIVATE_ACCESS

#include <cstdint>
#include <string>
#include <vector>

#include "mbedtls/pk.h"

// ---------------------------------------------------------------------------
// Réplica en C++ (mbedTLS) del esquema criptográfico de BeamCrypto.kt /
// SecureChannel.kt: EC P-256 (secp256r1), ECDH crudo, firma SHA256withECDSA
// (ECDSA sobre SHA-256, salida ASN.1 DER — formato por defecto tanto de Java
// como de mbedTLS, así que son compatibles sin conversión), y AES-256-GCM con
// la clave de sesión derivada igual que en Kotlin: SHA-256(secretoECDH || SALT).
//
// Solo la codificación de la CLAVE PÚBLICA necesita ser byte-compatible con
// Java (viaja por la red dentro del handshake, y el otro lado la parsea con
// KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(...))): se usa
// el formato SubjectPublicKeyInfo DER estándar (RFC 5480), que es exactamente
// lo que produce mbedtls_pk_write_pubkey_der(). La clave PRIVADA nunca sale del
// dispositivo, así que su formato de guardado en NVS es un detalle interno.
// ---------------------------------------------------------------------------
namespace beam_crypto {

/** Debe llamarse una vez al arrancar, antes de cualquier otra función de aquí. */
void init();

/** Carga la identidad persistida en NVS o genera una nueva la primera vez. */
void load_or_create_identity();

/** hex[:16] = sha256(clave pública DER)[:8 bytes] — igual que deviceIdFromPublicKey en Kotlin. */
std::string device_id();

/** SubjectPublicKeyInfo DER de la clave de IDENTIDAD (estable, persistida). */
std::vector<uint8_t> identity_public_der();

/** Firma SHA256withECDSA de `data` con la clave privada de identidad. Salida: ASN.1 DER. */
std::vector<uint8_t> sign_with_identity(const std::vector<uint8_t>& data);

/** Verifica una firma SHA256withECDSA (ASN.1 DER) con una clave pública SPKI DER ajena. */
bool verify_with_public_der(
    const std::vector<uint8_t>& public_key_der,
    const std::vector<uint8_t>& data,
    const std::vector<uint8_t>& signature_der
);

/** Opaco: clave efímera EC de UNA sesión. Nunca se persiste. */
struct Ephemeral;

Ephemeral* generate_ephemeral();
void free_ephemeral(Ephemeral* eph);
std::vector<uint8_t> ephemeral_public_der(Ephemeral* eph);

/** Secreto ECDH crudo (X del punto compartido, 32 bytes, big-endian, zero-padded). */
std::vector<uint8_t> ecdh_shared_secret(Ephemeral* my_ephemeral, const std::vector<uint8_t>& other_public_der);

std::vector<uint8_t> sha256(const std::vector<uint8_t>& data);

std::string to_base64(const std::vector<uint8_t>& bytes);
std::vector<uint8_t> from_base64(const std::string& text);

/**
 * Sesión cifrada de UN peer — réplica de SecureChannel.kt. Clave de sesión =
 * SHA-256(secretoECDH || SALT); formato de mensaje = IV(12) || ciphertext || tag(16),
 * igual que produce/consume Cipher.getInstance("AES/GCM/NoPadding") en Java.
 */
class SecureChannel {
public:
    explicit SecureChannel(const std::vector<uint8_t>& shared_secret_raw);

    std::vector<uint8_t> encrypt(const std::vector<uint8_t>& data);
    std::vector<uint8_t> decrypt(const std::vector<uint8_t>& iv_ciphertext_tag);

private:
    uint8_t key_[32];
};

} // namespace beam_crypto
