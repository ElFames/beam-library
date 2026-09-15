package com.nubax.beam

actual fun isAndroid() = false
actual fun isIos() = true

// iOS no tiene un modelo de permisos en tiempo de ejecución para WiFi/red local como
// Android (aquí es el sistema el que muestra el permiso de "Red local" automáticamente
// la primera vez que la app manda tráfico, no algo que la app pida explícitamente).
actual fun requiredWifiPermissions(): Array<String> = arrayOf("")

actual fun hasWifiPermissions(context: Any): Boolean = true
