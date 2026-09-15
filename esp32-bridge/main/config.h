#pragma once

// ---------------------------------------------------------------------------
// Identidad de red del puente. Única por unidad física — se entrega en una
// tarjeta impresa al fabricar/flashear (mismo modelo que una SIM). Estos son
// solo valores de ejemplo para desarrollo: antes de flashear una unidad real,
// cambia SSID/password a algo único para esa unidad y anótalo en su tarjeta.
//
// A diferencia del diseño anterior (el "pinganillo"), este dispositivo NO
// tiene identidad criptográfica propia ni habla el protocolo Aircom — es
// infraestructura de red pura (AP + NAT opcional), transparente para el
// protocolo. Ver PROJECT.md §3 para el porqué de este cambio.
// ---------------------------------------------------------------------------
#define BRIDGE_AP_SSID     "Aircom-Bridge"
#define BRIDGE_AP_PASSWORD "cambia-esto-por-unidad"

// NVS: namespace y claves de configuración persistente. Solo se guarda la
// WiFi de casa opcional (para el modo STA+NAT) — no hay ningún estado de
// vinculación que persistir, este dispositivo no vincula con nadie.
#define NVS_NAMESPACE        "bridge"
#define NVS_KEY_STA_SSID     "sta_ssid"
#define NVS_KEY_STA_PASSWORD "sta_pass"
