package com.nubax.beam.library.connectivity

import java.net.DatagramSocket
import java.net.Socket

/**
 * Ata un socket recién creado a una interfaz/red concreta del sistema, antes de bind/connect.
 *
 * Hace falta en Android cuando el puente de red vive en una red "solo local" pedida vía
 * WifiNetworkSpecifier: sin atar el socket explícitamente a esa red, el tráfico saldría
 * por la ruta por defecto (datos móviles o la WiFi normal del móvil) y nunca llegaría al
 * puente, aunque su interfaz exista y aparezca en NetworkInterface.getNetworkInterfaces().
 *
 * Es la versión JVM-concreta de [NetworkSocketBinder] (el marcador portable que sí
 * llega a `commonMain`) — vive en `jvmCommon` porque solo [MeshBeamConnection] (el
 * motor de transporte JVM) sabe qué hacer con un [DatagramSocket]/[Socket] real.
 */
internal interface JvmNetworkSocketBinder : NetworkSocketBinder {
    fun bind(socket: DatagramSocket)
    fun bind(socket: Socket)
}
