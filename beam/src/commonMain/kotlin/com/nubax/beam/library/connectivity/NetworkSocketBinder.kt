package com.nubax.beam.library.connectivity

import java.net.DatagramSocket
import java.net.Socket

/**
 * Ata un socket recién creado a una interfaz/red concreta del sistema, antes de bind/connect.
 *
 * Hace falta en Android cuando el pinganillo vive en una red "solo local" pedida vía
 * WifiNetworkSpecifier: sin atar el socket explícitamente a esa red, el tráfico saldría
 * por la ruta por defecto (datos móviles o la WiFi normal del móvil) y nunca llegaría al
 * pinganillo, aunque su interfaz exista y aparezca en NetworkInterface.getNetworkInterfaces().
 *
 * Desktop no lo necesita: ahí la única red activa ya es la del pinganillo (con NAT hacia
 * internet si el pinganillo tiene detrás una WiFi conocida), así que el socket por defecto
 * (sin atar a nada) ya sale por donde tiene que salir.
 */
interface NetworkSocketBinder {
    fun bind(socket: DatagramSocket)
    fun bind(socket: Socket)
}
