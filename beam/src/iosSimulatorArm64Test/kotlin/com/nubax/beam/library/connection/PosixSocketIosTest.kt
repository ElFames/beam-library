package com.nubax.beam.library.connection

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Valida la capa de sockets BSD crudos antes de construir IosMeshBeamConnection encima:
 * TCP en loopback (connect/accept/write/read) y UDP en loopback (bind/sendto/recvfrom).
 *
 * No hace falta un hilo aparte para accept(): una vez `listen()` está activo (dentro de
 * openTcpServer), el kernel completa el handshake TCP en cuanto el cliente hace connect(),
 * y accept() simplemente saca de la cola una conexión que el kernel ya dejó lista.
 */
class PosixSocketIosTest {

    @Test
    fun `TCP loopback conecta y transfiere datos en ambas direcciones`() {
        val port = 19921
        val serverFd = openTcpServer(port)
        try {
            val client = connectTcp("127.0.0.1", port)
            val server = acceptConnection(serverFd)

            val toServer = "hola servidor".encodeToByteArray()
            client.write(toServer)
            assertEquals("hola servidor", server.readExactly(toServer.size).decodeToString())

            val toClient = "hola cliente".encodeToByteArray()
            server.write(toClient)
            assertEquals("hola cliente", client.readExactly(toClient.size).decodeToString())

            client.close()
            server.close()
        } finally {
            closeServer(serverFd)
        }
    }

    @Test
    fun `UDP loopback envia y recibe un datagrama`() {
        val portA = 19931
        val portB = 19932
        val socketA = openUdpBeaconSocket(portA)
        val socketB = openUdpBeaconSocket(portB)
        try {
            socketA.sendTo("127.0.0.1", portB, "beacon de prueba".encodeToByteArray())
            val received = socketB.receive()
            requireNotNull(received)
            assertEquals("beacon de prueba", received.first.decodeToString())
        } finally {
            socketA.close()
            socketB.close()
        }
    }
}
