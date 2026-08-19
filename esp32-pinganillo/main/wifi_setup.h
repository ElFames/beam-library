#pragma once

#include <string>

// ---------------------------------------------------------------------------
// El pinganillo SIEMPRE levanta su propio AP con credenciales fijas de fábrica
// (PINGANILLO_AP_SSID/PASSWORD) — así es como móvil/desktop se unen sin que el
// pinganillo tenga pantalla. Si además hay una WiFi de casa configurada (ver
// provisioning_http.h para cómo se guarda la primera vez), el pinganillo TAMBIÉN
// se conecta a ella como cliente y hace NAT entre ambas interfaces: quien se une
// a su AP recibe internet de forma transparente. Sin esa WiFi configurada, el
// pinganillo sigue funcionando en modo AP puro (solo tráfico local, sin internet
// de por medio) y expone un portal HTTP en 192.168.4.1 para configurarla.
// ---------------------------------------------------------------------------
namespace wifi_setup {

void start();

bool has_sta_credentials();
void save_sta_credentials(const std::string& ssid, const std::string& password);

} // namespace wifi_setup
