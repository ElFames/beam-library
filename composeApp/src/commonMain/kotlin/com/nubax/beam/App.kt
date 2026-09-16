package com.nubax.beam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.nubax.beam.library.connectivity.HotspotController
import com.nubax.beam.library.core.BeamStorage
import com.nubax.beam.library.core.Log
import com.nubax.beam.library.core.PeerKind
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

expect fun isAndroid(): Boolean
expect fun isIos(): Boolean
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
    wifiJoiner: WifiJoiner? = null
) {
    val beamApplication by remember { mutableStateOf(BeamSdk.init(storage)) }
    val coroutineScope = rememberCoroutineScope()
    val beamState by beamApplication.state.collectAsState()
    val logs by Log.logs.collectAsState()
    val discovered by beamApplication.discoveredDevices.collectAsState()
    val connectedPeers by beamApplication.connectedPeers.collectAsState()
    val deviceName = when {
        isAndroid() -> "Android"
        isIos() -> "iOS"
        else -> "Desktop"
    }
    val myKind = when {
        isAndroid() -> PeerKind.MOBILE
        isIos() -> PeerKind.MOBILE
        else -> PeerKind.DESKTOP
    }

    val incomingChat by beamApplication.observeIncoming(ChatMessage.serializer()).collectAsState(null)
    val incomingMedia by beamApplication.observeIncoming(MediaMessage.serializer()).collectAsState(null)
    val pairingCode by beamApplication.pairingCode.collectAsState()
    val chatLog = remember { mutableStateListOf<String>() }
    var statusMessage by remember { mutableStateOf("Pulsa Iniciar SDK para anunciarte y empezar a descubrir.") }
    var sendProgress by remember { mutableStateOf<Float?>(null) }

    var manualHost by remember { mutableStateOf("") }
    var messageField by remember { mutableStateOf("") }
    var joinSsid by remember { mutableStateOf("") }
    var joinPassword by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    val codeFieldByDeviceId = remember { mutableStateMapOf<String, String>() }

    val hotspotInfo = hotspotController?.hotspot?.collectAsState()?.value
    val hotspotError = hotspotController?.error?.collectAsState()?.value

    LaunchedEffect(Unit) {
        beamApplication.linkEvents.collect { event -> chatLog.add("[vinculación] $event") }
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Beam test app ($deviceName)", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black)

            Text(
                text = "Id: ${runCatching { beamApplication.deviceId }.getOrDefault("(sin iniciar)")}\n" +
                    "Estado: ${beamState.describe()}\nConectados: ${connectedPeers.joinToString().ifEmpty { "ninguno" }}",
                fontSize = 14.sp,
                color = Color.Black,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = { beamApplication.start(deviceName, myKind) },
                modifier = Modifier.fillMaxWidth(),
                enabled = beamState is BeamState.Disabled,
                shape = RoundedCornerShape(12.dp)
            ) { Text("Iniciar SDK") }

            Section(title = "Logs") {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(110.dp).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(logs.size) { index -> Text(logs[index], fontSize = 11.sp) }
                }
            }

            if (statusMessage.isNotBlank()) {
                Text(statusMessage, fontSize = 13.sp, color = Color.Black, modifier = Modifier.fillMaxWidth())
            }

            // Solo aplica al lado Desktop: mientras no haya ningún móvil vinculado,
            // Aircom genera y muestra este código; el móvil lo teclea para emparejar.
            pairingCode?.let { code ->
                Text(
                    "Código de emparejamiento: $code",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Section(title = "Dispositivos descubiertos") {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(150.dp).padding(8.dp)) {
                    items(discovered.size) { index ->
                        val device = discovered[index]
                        Column {
                            Text(
                                "${device.name} (${device.id.take(8)}) [${device.kind}]" +
                                    if (device.trusted) " ✓ vinculado" else " sin emparejar",
                                fontSize = 12.sp
                            )
                            if (!device.trusted) {
                                when (device.kind) {
                                    PeerKind.DESKTOP -> {
                                        val codeField = codeFieldByDeviceId[device.id] ?: ""
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedTextField(
                                                value = codeField,
                                                onValueChange = { codeFieldByDeviceId[device.id] = it },
                                                modifier = Modifier.weight(1f),
                                                label = { Text("Código mostrado en el Desktop") },
                                                singleLine = true
                                            )
                                            OutlinedButton(
                                                enabled = codeField.isNotBlank(),
                                                onClick = {
                                                    coroutineScope.launch(Dispatchers.Default) {
                                                        beamApplication.pairDesktopWithCode(device.address, device.port, codeField)
                                                            .onFailure { statusMessage = "Error emparejando: $it" }
                                                    }
                                                }
                                            ) { Text("Emparejar") }
                                        }
                                    }
                                    PeerKind.MOBILE -> {} // Desktop no inicia emparejamiento hacia un móvil (sin flujo móvil↔móvil todavía)
                                }
                            } else {
                                OutlinedButton(onClick = { beamApplication.unlink(device.id) }) { Text("Desvincular") }
                            }
                        }
                    }
                }
            }

            // Menú oculto: unirse manualmente a una red (hotspot de reencuentro, IP a
            // mano) — nada de esto forma parte del camino normal de uso.
            OutlinedButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (showAdvanced) "▾ Opciones avanzadas de red" else "▸ Opciones avanzadas de red") }

            if (showAdvanced) {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (hotspotController != null) {
                        OutlinedButton(
                            onClick = { hotspotController.start() },
                            modifier = Modifier.fillMaxWidth(),
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
                                coroutineScope.launch(Dispatchers.Default) {
                                    wifiJoiner.join(joinSsid, joinPassword)
                                        .onSuccess { statusMessage = "Unido a $joinSsid" }
                                        .onFailure { statusMessage = "No se pudo unir a $joinSsid: ${it.message}" }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = joinSsid.isNotBlank()
                        ) { Text("Unirse a esa red") }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("IP manual (fallback)") },
                            singleLine = true
                        )
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch(Dispatchers.Default) {
                                    beamApplication.connectTo(manualHost)
                                        .onFailure { statusMessage = "Error conectando: $it" }
                                }
                            },
                            enabled = beamState !is BeamState.Disabled
                        ) { Text("Conectar") }
                    }
                }
            }

            Section(title = "Chat") {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(170.dp).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(chatLog.size) { index -> Text(chatLog[index], fontSize = 13.sp) }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = messageField,
                    onValueChange = { messageField = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Escribe un mensaje") }
                )
                OutlinedButton(
                    modifier = Modifier.padding(start = 8.dp),
                    enabled = connectedPeers.isNotEmpty() && messageField.isNotBlank(),
                    onClick = {
                        val target = connectedPeers.firstOrNull() ?: return@OutlinedButton
                        val text = messageField
                        coroutineScope.launch(Dispatchers.Default) {
                            beamApplication.send(target, ChatMessage(text), ChatMessage.serializer())
                                .onFailure { statusMessage = "Error enviando: $it" }
                        }
                        chatLog.add("yo: $text")
                        messageField = ""
                    }
                ) { Text("Enviar") }
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = connectedPeers.isNotEmpty(),
                onClick = {
                    val target = connectedPeers.firstOrNull() ?: return@OutlinedButton
                    coroutineScope.launch(Dispatchers.Default) {
                        val picked = imagePicker.pick() ?: return@launch
                        val media = MediaMessage(
                            id = randomMediaId(),
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
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
            ) { Text("Desconectar todo") }
        }
    }
}

/** Título + caja con borde alrededor de [content] — mismo aspecto para logs/dispositivos/chat. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
        OutlinedCard(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun randomMediaId(): String = Uuid.random().toString()

private fun BeamState.describe(): String = when (this) {
    is BeamState.Connected -> "Conectado con ${this.deviceToken}"
    is BeamState.Connecting -> "Conectando..."
    is BeamState.Disabled -> "Apagado"
    is BeamState.Error -> "Error: ${this.message}"
    is BeamState.Activated -> "Descubriendo..."
}
