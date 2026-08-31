#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

// ---------------------------------------------------------------------------
// Réplica en C++ de MeshBeamConnection.kt, pero SOLO el lado acceptor: el
// pinganillo nunca conecta hacia fuera (PROJECT.md §3), así que aquí no hay
// arbitraje ni beacon-listen — solo anunciarse (beacon UDP) y aceptar (TCP).
//
// Vinculación (PROJECT.md §2.3): mientras no haya nadie vinculado (NVS vacío),
// cualquiera que complete el handshake criptográfico demuestra ya conocer las
// credenciales del AP -> se vincula sin pasos adicionales. Una vez vinculado,
// solo ESE deviceId puede volver a conectar; cualquier otro se rechaza con un
// ControlMessage LinkStateChanged(DESVINCULADO). Ese mismo mensaje, si llega
// DE quien creíamos que era nuestro Android, dispara la propia desvinculación.
// ---------------------------------------------------------------------------
namespace beam_protocol {

using MessageHandler = std::function<void(const std::string& peer_id, const std::vector<uint8_t>& plaintext)>;

/** Arranca el beacon UDP y el servidor TCP. Llamar una vez, tras beam_crypto::load_or_create_identity(). */
void start(const std::string& device_name, MessageHandler on_message);

/** Envía datos cifrados a TODOS los peers conectados ahora mismo (en la práctica, el único Android vinculado). */
int send_to_all(const std::vector<uint8_t>& data);

int connected_peer_count();

/** ¿Ya hay un Android vinculado (persistido en NVS)? */
bool is_bonded();

/**
 * Pulsación larga del botón: avisa al Android vinculado (si está conectado
 * ahora mismo) de que nos desvinculamos, borra el estado de NVS, y reinicia —
 * vuelve a arrancar en modo FABRICA, listo para un emparejamiento nuevo.
 */
void unlink_and_restart();

} // namespace beam_protocol
