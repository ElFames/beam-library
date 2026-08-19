#pragma once

#include <cstdint>
#include <functional>
#include <vector>

// ---------------------------------------------------------------------------
// Captura por I2S (mic) -> empaqueta como AudioMessage (mismo esquema que
// AudioMessage.kt) -> se manda cifrado a todos los peers conectados.
// Reproducción por I2S (altavoz) de los AudioMessage que llegan de un peer.
//
// Simplificación deliberada: no hay detección de silencios/fin de frase (VAD) en
// el pinganillo — eso es mucho más que "capturar y mandar audio", que es lo que
// se pidió. Todo el audio desde el arranque es UN único streamId con isFinal
// siempre a false; el trocear en frases/utterances queda del lado de quien
// escucha (móvil/desktop), que es donde tiene sentido si además se quiere poder
// escribir a mano en vez de hablar.
// ---------------------------------------------------------------------------
namespace audio {

using SendFrame = std::function<int(const std::vector<uint8_t>&)>;

/** Arranca los drivers I2S y la tarea de captura. `send_frame` normalmente es beam_protocol::send_to_all. */
void start(SendFrame send_frame);

/** Si `plaintext` es un AudioMessage (tiene campo "pcm"), lo reproduce por el altavoz. */
void handle_incoming(const std::vector<uint8_t>& plaintext);

} // namespace audio
