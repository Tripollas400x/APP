# Changelog

Todas las cambios notables de este proyecto se documentan aquí.
El formato está basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/)
y el proyecto sigue [Versionado Semántico](https://semver.org/lang/es/).

## [0.1.3] - 2026-11-15

### Corregido
- HID directo: la app no reflejaba la conexión que ya existía a nivel
  sistema (Windows mostraba el mando y el celular aparecía "conectado a
  DESKTOP-XXXX", pero la app decía "Desconectado").
  - `HidTransport`: añadido `onConnectionStateChanged` al callback HID;
    ahora cualquier conexión/desconexión del host actualiza el estado en
    vivo sin pulsar nada.
  - `HidTransport.prepare()`: al abrir la app (modo HID) se registra el
    servicio y se consulta `getConnectedDevices()`, de modo que un PC ya
    conectado se detecta al instante y el botón Conectar no repite el
    registro (antes expiraba a los 12 s esperando "plugged").
  - `PadViewModel`: supervisión automática en modo HID (arranque, cambio
    de ajustes, activación de Bluetooth y concesión de permisos); el bucle
    de envío arranca solo cuando la conexión aparece y se detiene al caer.
  - El botón Conectar reutiliza el servicio HID ya registrado en vez de
    des-registrarlo y volver a registrarlo; detecta "ya conectado" al
    mismo host y maneja el cambio de host.
  - Pantalla Conectar muestra el nombre del host ("Conectado a
    DESKTOP-XXXX").

## [0.1.2] - 2026-11-15

### Corregido
- APK release ahora firmado (keystore versionado en el repo, firma
  estable): el error "el paquete no es válido" al instalar venía del
  artefacto `release-unsigned`. La CI publica el APK firmado y en tags
  crea un Release con descarga directa.

## [0.1.1] - 2026-11-15

### Corregido
- Receptor Windows (errores de compilación detectados por CI):
  - `RfcommServer`: la API WinRT real no publica eventos en
    `RfcommServiceProvider`; ahora usa un `StreamSocketListener` enlazado al
    servicio con `StartAdvertising(listener)` (patrón del sample oficial) y
    recibe conexiones por `StreamSocketListenerConnectionReceivedEventArgs`.
  - `ReceiverEngine`: ambigüedad de `Timer` resuelta con alias explícito a
    `System.Threading.Timer`; el frame del bucle de lectura usa
    `ReadOnlyMemory<byte>` (un `Span` no puede cruzar el `await` del PING).
  - `VirtualController`: migrado a la API real de Nefarius.ViGEm.Client
    1.21.x (clases concretas internas): nombres de enums corregidos
    (`Up/Down/Left/Right`, `ShoulderLeft/Right`, `ThumbLeft/Right`),
    PS/Touchpad vía `SetSpecialButtonsFull`, y **un solo envío nativo por
    frame** escribiendo por propiedades `ref` y setters en lote. Rumble
    habilitado también en modo DS4 (mejor esfuerzo, evento obsoleto
    documentado). `MainForm`: `using System.Diagnostics` y estado DS4.
- App Android (primer build real, 65 errores de compilación):
  - Añadido `app/build.gradle.kts` que faltaba (el módulo `:app` no existía
    para Gradle y por eso la CI no encontraba `assembleDebug`).
  - Dependencias: `navigation-compose`, `material-icons-extended`
    (iconos Bluetooth/Speed), fijadas con Compose BOM 2024.06.00.
  - `HidTransport`: API correcta de `BluetoothHidDevice` (connect,
    `BluetoothHidDeviceAppSdpSettings`, `SUBCLASS2_GAMEPAD`) y literals
    hexadecimales del descriptor convertidos a `Byte`.
  - Visibilidad de `Context.dataStore` y `ProfileStore`, import de
    `MutableStateFlow`, `when` exhaustivo en `PadViewModel`, imports de
    `changedToUp` y `minDimension` en los componentes Compose.
- CI: actions actualizadas a versiones Node 24 (checkout@v7,
  setup-dotnet@v6, setup-java@v6, setup-gradle@v6, upload-artifact@v7,
  download-artifact@v8, action-gh-release@v3).
- Gradle: compilación Kotlin en-proceso y heap ajustado para
  reproducibilidad en equipos modestos.

### Verificación
- `dotnet build` del receptor y de los tests: 0 errores, 0 warnings.
- `gradlew assembleDebug assembleRelease testDebugUnitTest` en local:
  APK debug (55 MB) y release sin firmar (41 MB) generados, 10/10 tests
  unitarios en verde.

## [0.1.0] - 2026-11-15

### Añadido
- App Android (Kotlin + Jetpack Compose, minSdk 28):
  - Interfaz de mando horizontal con 2 joysticks analógicos, 4 botones de
    acción con colores PlayStation, cruceta, L1/R1, L2/R2 analógicos,
    L3/R3, Share, Options, botón central y panel táctil.
  - Multitáctil por componente (cada control sigue su propio dedo).
  - Transmisión binaria compacta a 30 Hz-240 Hz configurable con CRC-8,
    ping/pong para medir RTT y watchdog anti-botones-atascados.
  - Método A: HID Bluetooth directo (BluetoothHidDevice, experimental).
  - Método B: cliente RFCOMM/SPP hacia el receptor de Windows (recomendado).
  - Perfiles PlayStation Classic / FPS Pro / Racing / Custom con editor
    básico (mover, escalar, opacidad) persistido en DataStore.
  - Giroscopio opcional, vibración local, indicador de batería,
    reconexión automática, pantalla de diagnóstico y registro de eventos.
- Receptor Windows (C# / .NET 8, WinForms):
  - Servidor RFCOMM/SPP (WinRT) que acepta la conexión del teléfono.
  - Emulación de mando virtual Xbox 360 (XInput) y DualShock 4 con
    ViGEmBus (Nefarius.ViGEm.Client).
  - Ventana con estado, velocidad de tramas, RTT, failsafe y panel de
    prueba de todos los botones y ejes.
- Protocolo binario Phantom Pad v1 documentado con vectores de prueba
  compartidos entre Kotlin, C# y Python.
- GitHub Actions: compilación de APK, publicación del receptor, tests y
  artefactos descargables; releases automáticos en tags `v*`.

### Notas
- No se ha probado en hardware real todavía: la compilación verificable es
  la de GitHub Actions. Ver la tabla de estado del README.
