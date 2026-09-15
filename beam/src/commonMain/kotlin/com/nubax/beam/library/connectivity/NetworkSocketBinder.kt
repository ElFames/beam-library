package com.nubax.beam.library.connectivity

/**
 * Marcador portable de "hay un mecanismo para atar sockets a una red concreta del
 * sistema" (hace falta en Android cuando el puente de red vive en una red "solo
 * local" pedida vía WifiNetworkSpecifier — ver [NetworkBridgeController]). La firma
 * real con los tipos de socket concretos (JVM: [java.net.DatagramSocket]/[java.net.Socket])
 * vive en `JvmNetworkSocketBinder`, en el source set compartido de Android/Desktop
 * — este marcador es lo único que necesita cruzar a `commonMain` para que
 * [com.nubax.beam.library.sdk.BeamApplication.attachNetwork] tenga un tipo portable
 * que aceptar sin filtrar tipos JVM-only a la API pública.
 *
 * Desktop no lo necesita (no tiene el concepto de "red solo-local" de Android): ahí
 * la única red activa ya es la del puente, así que el socket por defecto (sin atar a
 * nada) ya sale por donde tiene que salir. iOS tampoco lo implementa todavía —
 * hueco pendiente, ver la cabecera de `IosMeshBeamConnection`.
 */
interface NetworkSocketBinder
