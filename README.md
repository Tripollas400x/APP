# PHANTOM PAD

**Transforma tu teléfono Android en un mando Bluetooth para PC.** Sin internet, sin Wi-Fi, sin cables: solo Bluetooth.

| | |
|---|---|
| Plataforma móvil | Android 9+ (probado en proyecto para OPPO A60 / ColorOS) |
| Plataforma PC | Windows 10/11 |
| Conexión | Bluetooth (RFCOMM/SPP o HID) |
| Interfaz móvil | Kotlin + Jetpack Compose |
| Interfaz PC | C# / .NET 8 (WinForms) |
| Emulación PC | ViGEmBus (Xbox 360 / DualShock 4) |

## Estado del proyecto (v0.2.2)

Este proyecto se mantiene honesto: nada de funciones simuladas ni promesas sin medir.

| Función | Estado |
|---|---|
| App Android compilable (APK por GitHub Actions) | Implementada. La compilación verificable es la de CI. |
| UI de mando horizontal con multitáctil real | Implementada (joysticks analógicos, 16 botones, L2/R2 analógicos). |
| Método B: RFCOMM/SPP + receptor Windows + ViGEm | Implementado. Requiere prueba en hardware real. |
| Método A: HID Bluetooth directo | Implementado como experimental. Depende del soporte del fabricante. |
| Medición de latencia (RTT ping/pong) | Implementada en ambos extremos. Cifras reales: solo las que veas en tu equipo. |
| Giroscopio | Captura y envío implementados; el receptor v0.1 no lo consume aún. |
| Editor de distribución | Básico: mover, escalar y ajustar opacidad de cada control. |
| Perfiles predefinidos | PlayStation Classic, FPS Pro, Racing, Custom. |
| Probar en dispositivos reales (OPPO A60 + PC Windows) | **Pendiente.** No afirmamos que funcione hasta validarlo. |

## Cómo funciona

```
+---------------------+   Bluetooth    +----------------------+   ViGEmBus   +----------------+
|   PHANTOM PAD       |  RFCOMM/SPP    |  PHANTOM PAD         |  (driver)    |   Windows      |
|   Android (Kotlin)  | -------------> |  Receiver (C# .NET)  | -----------> |   Juegos PC    |
|                     |  30 B @ 30-240 |                      |  Xbox 360 /  |                |
|  UI Compose         |  Hz + CRC-8    |  Mando virtual       |  DualShock 4 |  XInput/DInput |
+---------------------+                +----------------------+              +----------------+
```

### Método A - HID Bluetooth directo (experimental)

La app se registra como dispositivo HID (`BluetoothHidDevice`, Android 9+) y Windows ve un **mando HID genérico** (DirectInput). Limitaciones honestas:

- Windows NO lo identificará como un DualShock 4 auténtico: es un HID genérico.
- Los juegos que solo aceptan XInput normalmente lo ignorarán.
- Algunas capas de fabricante (ColorOS incluido) restringen el perfil HID; la app lo detecta y te avisa.

Ver `docs/HID_NOTES.md`.

### Método B - Receptor Windows (recomendado)

La app envía estados binarios por RFCOMM/SPP a un receptor C# en el PC, que alimenta un mando virtual Xbox 360 (XInput) o DualShock 4 mediante [ViGEmBus](https://vigem.org/). Es el camino con máxima compatibilidad de juegos. Requisitos: instalar el driver ViGEmBus (una vez) y emparejar teléfono y PC.

**Modo Xbox 360 (recomendado):** compatible con XInput, rumble bidireccional completo y/player LED desde el juego.

**Modo DualShock 4:** los juegos que filtran por DualShock 4 lo detectan como tal; el rumble hacia el teléfono es *mejor esfuerzo* porque la librería ViGEm marca su evento de feedback como obsoleto (limitación documentada en el código).

## Instalación

- App Android: `docs/INSTALL_ANDROID.md` (APK generada en la pestaña Actions).
- Receptor Windows: `docs/INSTALL_WINDOWS.md` (.NET 8 + ViGEmBus + emparejamiento).
- Problemas frecuentes: `docs/TROUBLESHOOTING.md`.

## Uso rápido

1. Empareja el teléfono con el PC (ajustes Bluetooth de Windows o del teléfono).
2. Abre `PhantomPadReceiver.exe` en el PC y pulsa **Iniciar**.
3. Abre Phantom Pad en el teléfono, concede los permisos Bluetooth.
4. Elige el PC en la lista y pulsa conectar.
5. En modo Xbox 360, usa **Prueba local (joy.cpl)** antes de conectar el teléfono. Después conecta y verifica que los controles llegan al juego.
6. Ajusta perfiles, tamaño y opacidad desde el editor.
7. Juega.

## Protocolo binario (resumen)

Frame de 30 bytes: cabecera `0x50 0x50 0x01`, tipo, secuencia, máscara de 16 botones, 4 ejes (i8), 2 gatillos (u8), flags, giroscopio (6 x i16) y CRC-8. Detalle completo en `docs/PROTOCOL.md`, con vectores de prueba compartidos entre Kotlin, C# y Python.

## Personalización

- **PlayStation Classic**: distribución tradicional.
- **FPS Pro**: sticks altos, acción y cruceta abajo.
- **Racing**: sticks interiores y gatillos grandes.
- **Custom**: editor con arrastre, escala y opacidad por control. Todo se guarda localmente (DataStore), sin internet.

## Latencia

Cada extremo mide el RTT de RFCOMM mediante PING/PONG. Android muestra la última muestra, promedio y expiraciones. La frecuencia es configurable entre 30 y 240 Hz. Los ajustes nuevos comienzan en 60 Hz. El receptor neutraliza el mando tras 1 segundo sin INPUT válido.

## Estructura del repositorio

```
android/    App Kotlin + Compose (Phantom Pad)
windows/    Receptor C# .NET 8 (PhantomPadReceiver)
docs/       Protocolo, instalación, solución de problemas, notas HID
.github/    Workflows de CI (APK + receptor + releases)
scripts/    Referencia del protocolo en Python (vectores de prueba)
```

## Desarrollo

- **Android**: abre `android/` en Android Studio (Koala o superior) o ejecuta `./gradlew assembleDebug`.
- **Windows**: `dotnet build windows/src/PhantomPadReceiver` (requiere Windows + SDK .NET 8).
- **Tests**: `./gradlew testDebugUnitTest` y `dotnet test windows/tests/PhantomPadReceiver.Tests`.
- **CI**: cada push compila y publica artefactos; los tags `v*` generan un release.

## Hoja de ruta

1. v0.1 - base funcional, CI, protocolo estable (este release).
2. Pruebas reales en OPPO A60 + PC Windows y ajuste de latencia.
3. Consumo del giroscopio en el receptor (modo DS4) y rumble bidireccional afinado.
4. Varios teléfonos por PC (el protocolo ya reserva versión y secuencia).
5. Editor avanzado (rotación, formas, zonas muertas por stick).

## Licencia

MIT. Ver `LICENSE`. ViGEmBus es un driver separado con su propia licencia: [vigem.org](https://vigem.org/).
