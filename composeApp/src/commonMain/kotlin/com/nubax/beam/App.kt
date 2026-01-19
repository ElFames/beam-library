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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nubax.beam.library.sdk.models.BeamState
import com.nubax.beam.library.core.Log
import com.nubax.beam.library.sdk.models.onFailure
import com.nubax.beam.library.sdk.models.onSuccess
import com.nubax.beam.library.sdk.BeamSdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

expect fun isAndroid(): Boolean
expect fun requiredWifiPermissions(): Array<String>
expect fun hasWifiPermissions(context: Any): Boolean

@Serializable
data class Payment(val id: String, val amount: Double, val currency: String)
@Serializable
data class PaymentResponse(val id: String, val status: Boolean, val message: String)

@Composable
fun App() {
    val beamApplication by remember { mutableStateOf(BeamSdk.init()) }
    val coroutineScope = rememberCoroutineScope()
    val beamState by beamApplication.state.collectAsState()
    val logs by Log.logs.collectAsState()
    val ownToken = if(isAndroid()) "abcde12345(android-token)" else "jihgfe98765(desktop-token)"
    val targetToken = if(isAndroid()) "jihgfe98765(desktop-token)" else null // desktop no necesita conocer el token de android
    val payment by beamApplication.observeIncoming(Payment.serializer()).collectAsState(null)
    val paymentResponse by beamApplication.observeIncoming(PaymentResponse.serializer()).collectAsState(null)
    var message by remember { mutableStateOf("Listo para procesar pago.") }
    var desktopMessage by remember { mutableStateOf("Listo para enviar pago.") }

    LaunchedEffect(message) {
        if (message == "Respuesta de pago enviada.") {
            delay(1000)
            message = "Listo para recibir pagos."
        }
    }

    LaunchedEffect(paymentResponse) {
        paymentResponse?.let {
            desktopMessage = it.message
            delay(2000)
            desktopMessage = when(beamState) {
                is BeamState.Connected -> "Listo para enviar pago."
                is BeamState.Disabled -> "Offline"
                is BeamState.Connecting -> "Conectando..."
                is BeamState.Error -> "Error: ${(beamState as BeamState.Error).message}"
                is BeamState.Activated -> "Listo para emparejamiento."
            }
            desktopMessage = if (beamState is BeamState.Connected) "Listo para enviar pago." else "Listo para iniciar emparejamiento."
        }
    }

    LaunchedEffect(payment) {
        message = "Pago recibido: ${payment?.amount}${payment?.currency}\nID:${payment?.id}"
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Beam test app",
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            Text(
                text = "Device Id: $ownToken\nStatus:\n" + when (beamState) {
                    is BeamState.Connected -> {
                        val deviceToken = (beamState as BeamState.Connected).deviceToken
                        "Connected with: $deviceToken"
                    }
                    is BeamState.Connecting -> "Connecting..."
                    is BeamState.Disabled -> "Offline"
                    is BeamState.Error -> {
                        val errorMessage = (beamState as BeamState.Error).message
                        "Error: $errorMessage"
                    }
                    is BeamState.Activated -> "Online"
                },
                fontSize = 20.sp,
                color = Color.Black
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.22f),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logs.size) { index ->
                    Text(logs[index])
                }
            }

            if (isAndroid()) Text(message) else Text(desktopMessage)

            Column {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            beamApplication.init(token = ownToken)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.95f),
                    enabled = beamState is BeamState.Disabled || beamState is BeamState.Error,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.Black,
                        containerColor = Color.White,
                        disabledContentColor = Color.Gray,
                        disabledContainerColor = Color.LightGray
                    )
                ) {
                    Text("Activar Wi-Fi Direct")
                }

                OutlinedButton(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            beamApplication.startPairing(targetToken)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.9f),
                    enabled = beamState is BeamState.Activated || beamState is BeamState.Error || beamState is BeamState.Connected,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.Black,
                        containerColor = Color.White,
                        disabledContentColor = Color.Gray,
                        disabledContainerColor = Color.LightGray
                    )
                ) {
                    Text("Iniciar Emparejamiento")
                }

                if (!isAndroid()) {
                    Row {
                        val textFS by remember { mutableStateOf(TextFieldState("")) }

                        OutlinedTextField(
                            state = textFS,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(0.5f),
                            placeholder = { Text("Escribe un mensaje", color = Color.Gray) }
                        )
                        OutlinedButton(
                            modifier = Modifier.padding(10.dp),
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    beamApplication.send(Payment(
                                        id = UUID.randomUUID().toString(),
                                        amount = textFS.text.toString().toDouble(),
                                        currency = "$"
                                    ), Payment.serializer()).onSuccess {
                                        Log.i("Payment sent")
                                        desktopMessage = "Pago enviado. Esperando respuesta..."
                                    }.onFailure { Log.e(it) }
                                }
                            }
                        ) {
                            Text("Enviar pago")
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                beamApplication.send(PaymentResponse(
                                    id = payment?.id ?: "f",
                                    status = true,
                                    message = "Pago procesado correctamente."
                                ), PaymentResponse.serializer()).onSuccess {
                                    message = "Respuesta de pago enviada."
                                    Log.i("Payment response sent")
                                }.onFailure { Log.i(it) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.9f),
                        enabled = beamState is BeamState.Connected,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.Black,
                            containerColor = Color.White,
                            disabledContentColor = Color.Gray,
                            disabledContainerColor = Color.LightGray
                        )
                    ) {
                        Text("Responder Pago OK")
                    }
                }
                OutlinedButton(
                    onClick = beamApplication::disconnect,
                    modifier = Modifier.fillMaxWidth(0.9f),
                    enabled = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.Black,
                        containerColor = Color.White,
                        disabledContentColor = Color.Gray,
                        disabledContainerColor = Color.LightGray
                    )
                ) {
                    Text("Desconectar")
                }
            }
        }
    }
}
