#pragma once

#include <cstdint>
#include <functional>
#include <vector>

// ---------------------------------------------------------------------------
// Botón físico de 3 estados (PROJECT.md §3.4) + captura/reproducción I2S:
//
//   IDLE --pulsación--> ESCUCHANDO --pulsación--> ESPERANDO_RESPUESTA
//    ^                                                    |
//    |<---------- respuesta recibida / timeout 30s -------|
//    |<---------- pulsación (cancela, graba de nuevo) -----+
//
// Pulsación LARGA (independiente de los 3 estados): desvincula y reinicia
// (delegado en beam_protocol::unlink_and_restart()).
//
// Mientras ESCUCHANDO: captura del mic -> AudioMessage (mismo esquema que
// AudioMessage.kt) -> se manda cifrado. Al llegar a ESPERANDO_RESPUESTA se
// manda el trozo final con isFinal=true. Una respuesta que llegue estando ya
// de nuevo en ESCUCHANDO (por cancelación) se descarta, no se reproduce encima
// de la grabación en curso.
// ---------------------------------------------------------------------------
namespace audio {

using SendFrame = std::function<int(const std::vector<uint8_t>&)>;

/** Arranca los drivers I2S, el botón y la tarea de la máquina de estados. */
void start(SendFrame send_frame);

/** Si `plaintext` es un AudioMessage (tiene campo "pcm"), lo reproduce por el altavoz — solo si estábamos ESPERANDO_RESPUESTA. */
void handle_incoming(const std::vector<uint8_t>& plaintext);

} // namespace audio
