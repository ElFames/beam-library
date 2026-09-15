This is a Kotlin Multiplatform project targeting Android, Desktop (JVM) and iOS.

* [/composeApp](./composeApp/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./composeApp/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./composeApp/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./composeApp/src/jvmMain/kotlin)
    folder is the appropriate location.

### Build and Run Android Application

To build and run the development version of the Android app, use the run configuration from the run widget
in your IDE’s toolbar or build it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :composeApp:assembleDebug
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:assembleDebug
  ```

### Build and Run Desktop (JVM) Application

To build and run the development version of the desktop app, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :composeApp:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:run
  ```

### Build and Run iOS Application

Prueba **Android ↔ Desktop** o **iOS ↔ Desktop** (el emparejamiento por código, §2.4 de
`PROJECT.md`) sin necesitar el puente de red — útil mientras se monta el hardware.

Requiere Xcode (con al menos un simulador de iOS instalado) y que
`beam/native/ios/build.sh` se haya ejecutado al menos una vez para compilar la parte
Swift/CryptoKit de la criptografía (`beam/native/ios/BeamCryptoKit.swift`):

```shell
./beam/native/ios/build.sh
```

Luego abre `iosApp/iosApp.xcodeproj` en Xcode, elige un simulador (o tu iPhone, una vez
configures tu equipo de firma en "Signing & Capabilities") y pulsa ▶. También se puede
compilar/instalar sin abrir Xcode:

```shell
xcodebuild build -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator'
```

El primer build de cada configuración (Debug/Release × dispositivo/simulador) tarda más
porque el "Run Script" del target invoca
`./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`, que compila `composeApp`
entero para ese target antes de que Xcode enlace el `.app`.

Nota: unirse al puente de red (`NetworkBridgeController`, PROJECT.md §3) todavía no
tiene implementación en iOS — el `App` de iOS arranca sin ese controlador. Android↔Desktop
e iOS↔Desktop sí funcionan end a end (por la misma WiFi, o por código sin compartir red).

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…