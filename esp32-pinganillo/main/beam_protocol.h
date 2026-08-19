#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

// ---------------------------------------------------------------------------
// Réplica en C++ de MeshBeamConnection.kt: beacon UDP (puerto 8888), handshake
// TCP (puerto 9999) con el mismo framing de 4 bytes big-endian + longitud, y el
// mismo protocolo de intercambio (HandshakeHello -> EphemeralOffer -> ECDH ->
// AES-GCM). El pinganillo juega el MISMO rol simétrico que Android/Desktop en
// MeshBeamConnection: se anuncia, descubre, acepta y conecta.
//
// Diferencia deliberada con el lado Kotlin: aquí no hay TrustStore ni pantalla
// para confirmar un fingerprint, así que cualquier peer que complete el
// handshake criptográfico se registra de inmediato. La puerta de seguridad real
// vive en el móvil/desktop (confirmar el fingerprint la primera vez, quedar
// vinculado para siempre después) — el pinganillo es un periférico de función
// fija en una red ya protegida por contraseña.
// ---------------------------------------------------------------------------
namespace beam_protocol {

using MessageHandler = std::function<void(const std::string& peer_id, const std::vector<uint8_t>& plaintext)>;

/** Arranca los sockets UDP/TCP y las tareas de discovery/aceptación. Llamar una vez. */
void start(const std::string& device_name, MessageHandler on_message);

/** Envía datos cifrados a TODOS los peers conectados ahora mismo (fan-out simple). */
int send_to_all(const std::vector<uint8_t>& data);

int connected_peer_count();

} // namespace beam_protocol
