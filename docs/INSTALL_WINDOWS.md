# Instalación del receptor Windows (PhantomPadReceiver)

## Requisitos

- Windows 10 1809+ o Windows 11 con Bluetooth integrado o adaptador BT 4.0+.
- [.NET 8 Desktop Runtime](https://dotnet.microsoft.com/download/dotnet/8.0) (no hace falta si descargas el ZIP self-contained de Actions, que incluye el runtime).
- Driver [ViGEmBus](https://vigem.org/) 1.22+ (una sola vez). Descarga oficial: <https://vigem.org/downloads/> - elige `ViGEmBusSetup_x64`. Sin este driver no existe el mando virtual.
- Teléfono emparejado con el PC por Bluetooth (una vez).

## Pasos

1. **Instala ViGEmBus** y reinicia si el instalador lo pide. Verifica en el Administrador de dispositivos que aparece "Nefarius Virtual Game Controller Emulation Bus".
2. **Empareja el teléfono**: Configuración de Windows -> Bluetooth y dispositivos -> Agregar dispositivo -> Bluetooth; acepta el emparejamiento también en el teléfono.
3. **Descarga el receptor**:
   - Pestaña **Actions** del repo -> workflow **Windows CI** -> artefacto `PhantomPadReceiver-win-x64` (self-contained, no requiere .NET).
4. Descomprime y ejecuta `PhantomPadReceiver.exe`.
5. En **Modo** elige `Xbox 360` (máxima compatibilidad con juegos; el sistema y los juegos lo verán como un mando Xbox). `DualShock 4` existe pero con más limitaciones (ver abajo).
6. Pulsa **Iniciar**. El log debe decir "Servicio RFCOMM publicado. Esperando al teléfono...".
7. Conecta desde la app del teléfono. El panel de la derecha muestra en vivo todos los botones, sticks y gatillos.

## Comprobar que Windows ve el mando

- Pulsa `Win + R`, escribe `joy.cpl` y acepta: debe aparecer "Xbox 360 Controller" con estado "OK" cuando el teléfono esté conectado.
- El botón **Propiedades** de `joy.cpl` permite verificar ejes y botones sin abrir ningún juego.

## Modo DualShock 4: expectativas honestas

- ViGEm puede emular un DS4, pero Windows no lo trata igual que un Xbox 360: muchos juegos modernos solo escuchan XInput.
- El mando DS4 emulado no es un DS4 auténtico para todos los efectos (sin autenticación nativa).
- Recomendación: usa **Xbox 360** salvo que un juego concreto requiera DS4.

## Advertencia importante sobre anti-cheats

ViGEmBus es un driver de kernel. Algunos sistemas anti-cheat con driver propio (por ejemplo Vanguard de Riot) pueden bloquear o penalizar su uso en partidas online. Para juegos online con anti-cheat agresivo, verifica las reglas del juego. Para juego local y la mayoría de títulos de PC no hay problema.

## Ejecutar sin consola de errores

Si Windows SmartScreen avisa sobre una APK/EXE descargada de internet, pulsa "Más información" -> "Ejecutar de todas formas", o desbloquea el archivo con clic derecho -> Propiedades -> Desbloquear.
