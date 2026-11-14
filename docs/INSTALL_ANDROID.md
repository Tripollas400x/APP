# Instalación de la app Android (Phantom Pad)

## Requisitos

- Android 9 (API 28) o superior. El proyecto se orienta a OPPO A60 (Android 14 / ColorOS) pero funciona en cualquier teléfono con Bluetooth.
- Bluetooth activado en el teléfono y en el PC.
- No se necesita internet en ningún momento (solo para descargar la APK la primera vez, si usas GitHub).

## Opción 1: descargar la APK compilada (recomendado)

1. Entra a la pestaña **Actions** del repositorio.
2. Abre el workflow **Android CI** más reciente.
3. En **Artifacts** descarga `PhantomPad-APK-debug`.
4. Descomprime el ZIP y copia `app-debug.apk` al teléfono.
5. En el teléfono, abre el archivo y acepta "instalar de origen desconocido".
6. Listo: aparece "Phantom Pad" en tus apps.

Nota: la APK debug es válida para pruebas. Para una release firmada, añade un keystore (ver sección de releases en el workflow).

## Opción 2: compilar tú mismo

```bash
cd android
./gradlew assembleDebug        # APK en app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # tests unitarios del protocolo y matemáticas de stick
```

o abre la carpeta `android/` en Android Studio.

## Permisos que pide la app (y por qué)

| Permiso | Motivo |
|---|---|
| Bluetooth connect / scan | Encontrar y conectar con el PC. |
| Ubicación precisa (Android 10 o anterior) | Requisito del sistema para descubrir dispositivos Bluetooth. |
| Vibración | Feedback táctil y rumble local. |
| Mantener encendido | Evita que la pantalla se apague jugando (configurable). |
| Sensores a alta frecuencia | Giroscopio opcional. |

La app no pide internet y no lo usa.

## Emparejamiento inicial

1. Abre Phantom Pad.
2. Pulsa **Comenzar** y concede los permisos.
3. Si el Bluetooth está apagado, la app te ofrece encenderlo.
4. Elige el PC en la lista (emparejado o nuevo; si es nuevo se inicia el emparejamiento).
5. Selecciona el modo: **Receptor Windows** (recomendado) o **HID directo** (experimental).
6. Conectar. En reconexiones posteriores, el dispositivo conocido aparece arriba para reconectar con un toque.

Si el PC no aparece o falla la conexión, ver `docs/TROUBLESHOOTING.md`.
