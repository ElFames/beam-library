#pragma once

#include <string>

// ---------------------------------------------------------------------------
// El puente SIEMPRE levanta su propio AP con credenciales fijas de fábrica
// (BRIDGE_AP_SSID/PASSWORD, únicas por unidad — ver config.h) — así cualquier
// dispositivo (móvil o Desktop, cualquier plataforma) se une a él como a
// cualquier WiFi normal, sin necesitar ningún protocolo especial de por medio.
//
// Si además hay una WiFi de casa/oficina configurada (ver provisioning_http.h
// para cómo se guarda la primera vez), el puente TAMBIÉN se conecta a ella
// como cliente y hace NAT entre ambas interfaces: quien se une a su AP recibe
// internet de forma transparente. Sin esa WiFi configurada, el puente sigue
// funcionando en modo AP puro (solo tráfico local entre quien se une a él,
// sin internet de por medio) y expone un portal HTTP en 192.168.4.1 para
// configurarla.
//
// Este dispositivo NO es un peer de Aircom: no tiene identidad criptográfica,
// no hace handshake, no aparece en ningún historial de vinculación. Es pura
// infraestructura de red — el protocolo Aircom (beacon UDP + handshake TCP
// entre móvil y Desktop) funciona exactamente igual aquí que en cualquier
// otra WiFi, sin ningún código especial. Ver PROJECT.md §3.
// ---------------------------------------------------------------------------
namespace wifi_setup {

void start();

bool has_sta_credentials();
void save_sta_credentials(const std::string& ssid, const std::string& password);

} // namespace wifi_setup
