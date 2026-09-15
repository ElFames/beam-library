#pragma once

// ---------------------------------------------------------------------------
// Portal HTTP mínimo en http://192.168.4.1/ para darle al puente las
// credenciales de la WiFi de casa la primera vez (no hay pantalla en el
// dispositivo, así que esto se hace desde el navegador del móvil/portátil tras
// unirse al AP de fábrica del puente). No es un captive portal completo
// (no hay redirección DNS automática ni el popup que abren iOS/Android solos):
// hay que visitar la dirección a mano. Es suficiente para una configuración de
// una sola vez hecha por quien monta el hardware.
// ---------------------------------------------------------------------------
namespace provisioning_http {

void start();

} // namespace provisioning_http
