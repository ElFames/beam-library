package com.nubax.beam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nubax.beam.library.connection.PairingRequestEvent
import com.nubax.beam.library.connectivity.HotspotController
import com.nubax.beam.library.connectivity.PinganilloConnectionState
import com.nubax.beam.library.connectivity.PinganilloController
import com.nubax.beam.library.connectivity.PinganilloDefaults
import com.nubax.beam.library.core.BeamStorage
import com.nubax.beam.library.core.Log
import com.nubax.beam.library.sdk.BeamSdk
import com.nubax.beam.library.sdk.models.BeamState
import com.nubax.beam.library.sdk.models.MediaMessage
import com.nubax.beam.library.sdk.models.onFailure
import com.nubax.beam.media.ImagePicker
import com.nubax.beam.media.ReceivedFileSaver
import com.nubax.beam.media.WifiJoiner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

expect fun isAndroid(): Boolean
expect fun requiredWifiPermissions(): Array<String>
expect fun hasWifiPermissions(context: Any): Boolean

@Serializable
data class ChatMessage(val text: String)

@Composable
fun App(
    storage: BeamStorage,
    imagePicker: ImagePicker,
    fileSaver: ReceivedFileSaver,
    hotspotController: HotspotController? = null,
    wifiJoiner: WifiJoiner? = null,
    pinganilloController: PinganilloController? = null
) {
    val beamApplication by remember { mutableStateOf(BeamSdk.init(storage)) }
    val coroutineScope = rememberCoroutineScope()
    val beamState by beamApplication.state.collectAsState()
    val logs by Log.logs.collectAsState()
    val discovered by beamApplication.discoveredDevices.collectAsState()
    val connectedPeers by beamApplication.connectedPeers.collectAsState()
    val deviceName = if (isAndroid()) "Android" else "Desktop"

    var pendingPairing by remember { mutableStateOf<PairingRequestEvent?>(null) }
    val incomingChat by beamApplication.observeIncoming(ChatMessage.serializer()).collectAsState(null)
    val incomingMedia by beamApplication.observeIncoming(MediaMessage.serializer()).collectAsState(null)
    val chatLog = remember { mutableStateListOf<String>() }
    var statusMessage by remember { mutableStateOf("Pulsa Iniciar SDK para anunciarte y empezar a descubrir.") }
    var sendProgress by remember { mutableStateOf<Float?>(null) }

    var manualHost by remember { mutableStateOf("") }
    var messageField by remember { mutableStateOf("") }
    var joinSsid by remember { mutableStateOf("") }
    var joinPassword by remember { mutableStateOf("") }

    val hotspotInfo = hotspotController?.hotspot?.collectAsState()?.value
    val hotspotError = hotspotController?.error?.collectAsState()?.value

    val pinganilloState = pinganilloController?.state?.collectAsState()?.value
    val pinganilloBinder = pinganilloController?.networkBinder?.collectAsState()?.value

    LaunchedEffect(Unit) {
        beamApplication.pairingRequests.collect { event ->
            pendingPairing = event
        }
    }

    // El binder solo existe en Android (red "solo local" del pinganillo); en Desktop
    // pinganilloBinder es siempre null y esto no hace nada — unirse a la WiFi ya basta.
    LaunchedEffect(pinganilloBinder) {
        pinganilloBinder?.let { beamApplication.attachNetwork(it) } ?: beamApplication.detachNetwork()
    }

    LaunchedEffect(incomingChat) {
        incomingChat?.let { (peerId, message) -> chatLog.add("${peerId.take(8)}: ${message.text}") }
    }

    LaunchedEffect(incomingMedia) {
        incomingMedia?.let { (peerId, media) ->
            val path = fileSaver.save(media.name, media.bytes)
            chatLog.add("${peerId.take(8)} envió ${media.name} (${media.bytes.size / 1024} KB) -> $path")
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Beam test app ($deviceName)", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.Black)

            Text(
                text = "Id: ${runCatching { beamApplication.deviceId }.getOrDefault("(sin iniciar)")}\n" +
                    "Estado: ${beamState.describe()}\nConectados: ${connectedPeers.joinToString().ifEmpty { "ninguno" }}",
                fontSize = 16.sp,
                color = Color.Black
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.12f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logs.size) { index -> Text(logs[index], fontSize = 11.sp) }
            }

            Text(statusMessage, fontSize = 13.sp)

            Column {
                OutlinedButton(
                    onClick = { beamApplication.start(deviceName) },
                    modifier = Modifier.fillMaxWidth(0.95f),
                    enabled = beamState is BeamState.Disabled,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Iniciar SDK") }

                if (hotspotController != null) {
                    OutlinedButton(
                        onClick = { hotspotController.start() },
                        modifier = Modifier.fillMaxWidth(0.95f),
                        enabled = hotspotInfo == null
                    ) { Text("Activar hotspot local (sin misma red)") }

                    hotspotInfo?.let {
                        Text("Red: ${it.ssid}  ·  clave: ${it.passphrase}", fontSize = 13.sp)
                    }
                    hotspotError?.let { Text("Error: $it", fontSize = 12.sp, color = Color.Red) }
                }

                if (wifiJoiner != null) {
                    // Android elige el SSID/contraseña al azar cada vez (no se puede fijar desde
                    // una app normal), así que aquí se teclean una vez los que muestre la pantalla
                    // del móvil — como conectar a cualquier WiFi nueva, no una IP a mano.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = joinSsid,
                            onValueChange = { joinSsid = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("SSID del móvil") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = joinPassword,
                            onValueChange = { joinPassword = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Contraseña") },
                            singleLine = true
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                wifiJoiner.join(joinSsid, joinPassword)
                                    .onSuccess { statusMessage = "Unido a $joinSsid" }
                                    .onFailure { statusMessage = "No se pudo unir a $joinSsid: ${it.message}" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.95f),
                        enabled = joinSsid.isNotBlank()
                    ) { Text("Unirse a esa red") }
                }

                if (pinganilloController != null) {
                    // Credenciales fijas de fábrica (PinganilloDefaults): a diferencia del
                    // hotspot del móvil, el pinganillo siempre se anuncia con el mismo
                    // SSID/clave, así que aquí no hace falta teclear nada.
                    OutlinedButton(
                        onClick = { pinganilloController.connect() },
                        modifier = Modifier.fillMaxWidth(0.95f),
                        enabled = pinganilloState !is PinganilloConnectionState.Connected &&
                            pinganilloState !is PinganilloConnectionState.Connecting
                    ) { Text("Vincular pinganillo (${PinganilloDefaults.AP_SSID})") }

                    when (pinganilloState) {
                        is PinganilloConnectionState.Connecting -> Text("Conectando con el pinganillo...", fontSize = 12.sp)
                        is PinganilloConnectionState.Connected -> Text("Pinganillo conectado ✓", fontSize = 12.sp)
                        is PinganilloConnectionState.Failed -> Text("Error: ${pinganilloState.message}", fontSize = 12.sp, color = Color.Red)
                        else -> {}
                    }
                }

                Text("Dispositivos descubiertos:", fontSize = 13.sp)
                LazyColumn(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.1f)) {
                    items(discovered.size) { index ->
                        val device = discovered[index]
                        Text(
                            "${device.name} (${device.id.take(8)})" +
                                if (device.trusted) " ✓" else " sin emparejar",
                            fontSize = 12.sp
                        )
                    }
                }

                pendingPairing?.let { request ->
                    Column {
                        Text("Emparejamiento con ${request.name}: código ${request.fingerprint}")
                        Row {
                            OutlinedButton(onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    beamApplication.confirmPairing(request.peerId)
                                    pendingPairing = null
                                }
                            }) { Text("Coincide, confirmar") }
                            OutlinedButton(onClick = {
                                beamApplication.rejectPairing(request.peerId)
                                pendingPairing = null
                            }) { Text("Rechazar") }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it },
                        modifier = Modifier.weight(2f),
                        label = { Text("IP manual (fallback)") },
                        singleLine = true
                    )
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                beamApplication.connectTo(manualHost)
                                    .onFailure { statusMessage = "Error conectando: $it" }
                            }
                        },
                        enabled = beamState !is BeamState.Disabled
                    ) { Text("Conectar") }
                }

                Text("Chat:", fontSize = 13.sp)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.15f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(chatLog.size) { index -> Text(chatLog[index], fontSize = 13.sp) }
                }

                Row {
                    OutlinedTextField(
                        value = messageField,
                        onValueChange = { messageField = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Escribe un mensaje") }
                    )
                    OutlinedButton(
                        modifier = Modifier.padding(10.dp),
                        enabled = connectedPeers.isNotEmpty() && messageField.isNotBlank(),
                        onClick = {
                            val target = connectedPeers.firstOrNull() ?: return@OutlinedButton
                            val text = messageField
                            coroutineScope.launch(Dispatchers.IO) {
                                beamApplication.send(target, ChatMessage(text), ChatMessage.serializer())
                                    .onFailure { statusMessage = "Error enviando: $it" }
                            }
                            chatLog.add("yo: $text")
                            messageField = ""
                        }
                    ) { Text("Enviar") }
                }

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    enabled = connectedPeers.isNotEmpty(),
                    onClick = {
                        val target = connectedPeers.firstOrNull() ?: return@OutlinedButton
                        coroutineScope.launch(Dispatchers.IO) {
                            val picked = imagePicker.pick() ?: return@launch
                            val media = MediaMessage(
                                id = UUID.randomUUID().toString(),
                                name = picked.name,
                                description = "",
                                mimeType = picked.mimeType,
                                bytes = picked.bytes
                            )
                            sendProgress = 0f
                            beamApplication.send(target, media, MediaMessage.serializer()) { progress ->
                                sendProgress = progress
                            }.onFailure { statusMessage = "Error enviando imagen: $it" }
                            sendProgress = null
                            chatLog.add("yo envié ${picked.name} (${picked.bytes.size / 1024} KB)")
                        }
                    }
                ) { Text(sendProgress?.let { "Enviando... ${(it * 100).toInt()}%" } ?: "Enviar imagen") }

                OutlinedButton(
                    onClick = { beamApplication.shutdown() },
                    modifier = Modifier.fillMaxWidth(0.9f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                ) { Text("Desconectar todo") }
            }
        }
    }
}

private fun BeamState.describe(): String = when (this) {
    is BeamState.Connected -> "Conectado con ${this.deviceToken}"
    is BeamState.Connecting -> "Conectando..."
    is BeamState.Disabled -> "Apagado"
    is BeamState.Error -> "Error: ${this.message}"
    is BeamState.Activated -> "Descubriendo..."
}
