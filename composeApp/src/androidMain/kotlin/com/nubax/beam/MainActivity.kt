package com.nubax.beam

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

actual fun isAndroid() = true
enum class PermissionsState {
    REQUESTING, GRANTED, DENIED
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val initState = if(hasWifiPermissions(this)) PermissionsState.GRANTED else PermissionsState.REQUESTING
            var state by remember { mutableStateOf(initState) }
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                val granted = permissions.values.all { it }
                state = if (granted) {
                    PermissionsState.GRANTED
                } else {
                    PermissionsState.DENIED
                }
            }

            if (state == PermissionsState.GRANTED) {
                App()
            } else {
                LaunchedEffect(Unit, state) {
                    permissionLauncher.launch(requiredWifiPermissions())
                }
            }
        }

    }
}

actual fun requiredWifiPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf("android.permission.NEARBY_WIFI_DEVICES")
    } else {
        arrayOf("android.permission.ACCESS_FINE_LOCATION")
    }
}

actual fun hasWifiPermissions(context: Any): Boolean {
    return requiredWifiPermissions().all {
        ContextCompat.checkSelfPermission(
            context as Context,
            it
        ) == PackageManager.PERMISSION_GRANTED
    }
}