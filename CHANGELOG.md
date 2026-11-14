# Changelog

Todas las cambios notables de este proyecto se documentan aquí.
El formato está basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/)
y el proyecto sigue [Versionado Semántico](https://semver.org/lang/es/).

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
