# Changelog

Todas las cambios notables de este proyecto se documentan aquí.
El formato está basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/)
y el proyecto sigue [Versionado Semántico](https://semver.org/lang/es/).

## [0.2.1 / Receptor 0.2.1] - 2026-11-15 — BLUETOOTH & CONTROLLER FIX

### Corregido — Receptor Windows
- **Xbox 360: `Xbox360UserIndexNotReportedException` ya no destruye el mando.**
  Verificado contra Nefarius.ViGEm.Client 1.21.256 decompilado: `UserIndex`
  solo lo asigna la notificación asíncrona del bus (cuando Windows enumera el
  mando virtual XUSB); leerlo justo tras `Connect()` lanza y el bucle antiguo
  de 5 intentos con recreación nunca le daba tiempo a llegar.
  - `VirtualController`: creación, conexión y consulta del índice separadas.
    Si `Connect()` no lanza, el mando queda enchufado y operativo; el índice
    se sondea en segundo plano (tolerante, "índice pendiente") sin recrear
    nada. El fallback a DS4 SOLO ocurre si el plug de Xbox 360 falla de
    verdad (bus no encontrado, sin hueco...).
  - Diagnóstico completo: tipo de excepción, mensaje real, HRESULT y stack
    (primera línea) en cada intento.
- **Servicio RFCOMM con errores reales y registro paso a paso** (`[BT] ...`):
  adaptador/radio, creación del servicio, SDP, enlace del listener y
  publicación quedan registrados; cada fallo incluye tipo + mensaje +
  HRESULT (los errores WinRT llegan con `Message` vacío: el `ERROR:` sin
  datos ya no puede ocurrir).
- **SDP corregido**: el atributo 0x0100 (nombre de servicio) ahora se codifica
  como elemento SDP (0x25 + longitud + UTF-8); antes se escribía la cadena en
  bruto y el registro no parseaba bien en Android.
- Protección del listener cambiada de `PlainSocket` a
  `BluetoothEncryptionAllowNullAuthentication` (acepta clientes Android
  seguros y sin cifrado; nivel de la muestra oficial de Microsoft).
- Sesiones con ID: al reemplazarse una conexión, el cierre tardío de la
  sesión vieja ya no marca como desconectada a la nueva.
- Watchdog (failsafe) de una sola vez por pérdida con aviso de recuperación;
  ya no repite el aviso cada 500 ms.
- El servicio Bluetooth y el mando virtual son independientes: si ViGEmBus
  falla, el receptor sigue recibiendo y mostrando los controles; si BT falla,
  reintenta solo cada 5 s.
- Parser incremental `FramePipe` en el receptor: frames fragmentados o
  concatenados, basura y CRC inválido se resincronizan sin perder frames.
- Reintentos del mando virtual reducidos a 3 por llamada (el reintento real
  lo hace el motor en segundo plano cada 5 s).

### Corregido — App Android
- **RFCOMM**: el descubrimiento Bluetooth se cancela (y se espera a que la
  pila lo detenga) ANTES de `socket.connect()`; timeout real de conexión
  (12 s, `connect()` puede bloquearse indefinidamente) cerrando el socket;
  motivo real de cada intento en el registro; una sola conexión a la vez.
- **RFCOMM**: el hilo lector usa el parser incremental `FrameParser`
  (espejo del receptor): fragmentados, concatenados y CRC inválido.
- **RELEASE_ALL consumido completo** (5 bytes; antes se leían 3 de cuerpo y
  se desincronizaba el stream si el receptor lo enviaba).
- **HID**: registro con mutex (una sola operación en curso); la carrera de
  confirmación (Windows confirma antes de que la app empiece a esperar) se
  cubre con un flag y no produce timeouts falsos; el host exigido es el
  pedido exacto (eliminado el fallback a "el primero conectado"); hat switch
  con diagonales correctas (SE=3, SO=5; antes SE=2, SO=3).
- **HID**: diagnóstico por etapas para distinguir bloqueo de ColorOS de
  cualquier otro fallo (perfil no disponible, registro rechazado, conexión
  rechazada, desregistro del sistema).
- **BluetoothManager**: receivers del sistema con `RECEIVER_EXPORTED`
  (broadcasts protegidos; en ColorOS la entrega a NOT_EXPORTED es
  inconsistente); `ACTION_DISCOVERY_FINISHED` cierra el escaneo y refresca
  estados; registro doble evitado; `startDiscovery()` fallido ya no deja
  estado colgado; el emparejamiento solo responde al dispositivo pedido.
- **Controles**: mover un stick ya NO activa L3/R3 (nuevo ajuste opcional
  "L3/R3 al pulsar el stick", desactivado por defecto) y hay dos botones
  L3 y R3 dedicados en pantalla; la cruceta sigue al dedo que inició el
  gesto (por pointer id, no "el primero presionado"); joysticks y cruceta
  liberan todo su estado ante cancelación o pérdida del dedo (try/finally).
- **InputHub**: la cancelación del bucle ya no deja botones pegados:
  RELEASE_ALL sale con `NonCancellable` en el finally.

### Añadido
- Receptor: botón **Prueba local (joy.cpl)** que barre ejes, gatillos y
  botones por el mando virtual sin necesidad de teléfono (Test A); botón
  **Guardar registro...** que exporta el log a archivo sin datos privados;
  estadísticas de frames rechazados y estado del servicio BT en la línea de
  estado.
- App: tarjeta de diagnóstico Bluetooth (adaptador, permisos, escaneo,
  emparejados, UUID del servicio), frames enviados y botón de copiar registro.
- Tests: `FramePipeTests.cs` (8 casos, incl. volumen mixto de 200 frames) y
  `FrameParserTest.kt` (8 casos espejo). Parser validado también con runner
  local (12/12 PASS).

### Corregido — CI
- Los dos workflows tenían `branches: ain]` (YAML corrupto): los push/PR a
  `main` NO disparaban CI. Corregido a `[main]`.

## [0.1.4 / Receptor 0.2.0] - 2026-11-15

### Corregido
- Receptor Windows: "No se pudo crear el mando virtual" con mensajes
  absurdos de la librería ViGEm ("La operación se completó correctamente",
  `Xbox360UserIndexNotReportedException`) aun teniendo el driver instalado.
  - `VirtualController`: reintento (5 intentos, 500 ms) del plug; si el
    modo Xbox 360 no responde, **fallback automático a DualShock 4**.
  - Detección real del driver ViGEmBus (servicio en el registro) con
    mensajes útiles según esté instalado o no.
  - `ReceiverEngine`: si el mando virtual falla, **reintenta solo cada 5 s**
    en segundo plano; ya no hace falta pulsar Detener/Iniciar.
- Teléfono no conectaba con el receptor: la app usaba canal RFCOMM seguro
  y el receptor Windows publica PlainSocket (sin cifrado). `RfcommTransport`
  ahora prueba seguro y, si falla, sin cifrado (fallback) con registro de
  cada intento.

### Mejorado
- Interfaz del receptor (0.2.0): píldora de estado con punto de color,
  registro con colores por severidad (RichTextBox), estado del driver al
  arrancar, botones Limpiar/Copiar registro, versión visible, estadísticas
  con modo efectivo (incluye fallback) y estilo de botones plano con hover.

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
