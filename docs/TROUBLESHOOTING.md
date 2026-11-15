# Solución de problemas de conexión

Asistente rápido: sigue los pasos en orden y detente en el primero que falle.

## 1. Bluetooth

- El Bluetooth del teléfono y del PC está activado.
- Modo avión desactivado (puede dejar el BT del teléfono en un estado raro).
- En el PC, reinicia el servicio: Administrador de dispositivos -> Bluetooth -> deshabilita y habilita el adaptador.

## 2. Emparejamiento

- El teléfono aparece en la lista de dispositivos del PC y viceversa.
- Si el emparejamiento falla: elimina el emparejamiento en ambos lados y repítelo con el teléfono cerca del PC.
- ColorOS a veces pide confirmación dos veces: acepta ambas.

## 3. Método B (receptor Windows)

Síntoma: la app del teléfono dice "conectando" y no termina.

- ¿El receptor está abierto y con **Iniciar** pulsado? El log debe mostrar "Servicio RFCOMM publicado".
- ¿Instalaste ViGEmBus? Si falta, el receptor lo detecta al iniciar y muestra un aviso con el enlace de descarga.
- Cierra y reabre el receptor; luego reconecta desde el teléfono (reconexión rápida).
- Otros programas con mando virtual (DS4Windows, x360ce) pueden interferir: ciérralos mientras pruebas.

Síntoma: se conecta y se desconecta en segundos.

- Verifica que no haya otro teléfono conectado al receptor (v0.1 acepta una conexión).
- Acerca el teléfono al PC; los adaptadores BT de bajo consumo sufren con paredes.

## 4. Método A (HID directo)

- Es experimental y depende del fabricante: en muchos ColorOS el registro HID falla y la app lo informa con el mensaje exacto.
- Windows verá un "mando HID genérico" (DirectInput), no un DualShock 4 y no un mando Xbox. Para juegos XInput usa el Método B.
- Si el PC no lo reconoce: elimina el emparejamiento, empareja de nuevo e inténtalo otra vez tras abrir el modo mando.

## 5. El mando no aparece en los juegos

- Abre `joy.cpl` y verifica que hay un mando con estado OK.
- Modo receptor: el juego debe ser compatible con XInput (casi todos). En ajustes del juego elige "Xbox controller".
- Modo HID: solo funcionará en juegos con soporte DirectInput/genérico.

## 6. Latencia o cortes

- Sube la frecuencia de envío en Ajustes (por defecto 120 Hz) y observa el RTT en Diagnóstico.
- Desactiva el ahorro de batería de ColorOS para Phantom Pad (Ajustes -> Batería -> sin optimización). ColorOS mata conexiones en segundo plano agresivamente.
- Mantén la pantalla del modo mando encendida (la app lo hace automáticamente si la opción está activa).

## 7. Botones que quedan pegados

- El receptor libera todo si deja de recibir tramas durante 1 segundo (failsafe).
- Si pasa con la pantalla encendida, avísanos con el log de Diagnóstico: es un bug que queremos ver.

## 8. Nuevas herramientas de diagnóstico (v0.2.1)

- El receptor Windows registra cada paso con `[BT] ...`: adaptador, servicio, SDP, listener y publicación. Si algo falla, el log muestra el tipo de excepción, el mensaje real y el HRESULT (0x800710DF = Bluetooth apagado, 0x80070490 = elemento no encontrado...). Usa el botón "Guardar registro..." para adjuntarlo a un reporte.
- Botón **Prueba local (joy.cpl)**: con el receptor iniciado, barre ejes, gatillos y botones del mando virtual SIN teléfono. Si joy.cpl muestra el barrido, el mando virtual está bien y el problema está en el Bluetooth; si no lo muestra, el problema es de ViGEmBus/driver.
- App: Diagnóstico muestra ahora adaptador, permisos, escaneo, emparejados, UUID del servicio y frames enviados; el registro completo se copia con un botón.

## 9. Xbox 360: "índice del jugador pendiente"

`Xbox360UserIndexNotReportedException` no es un error de creación: el índice
llega asíncronamente cuando Windows termina de enumerar el mando virtual. En
v0.2.1 el receptor ya NO destruye el mando por eso: muestra "índice pendiente"
y el mando queda operativo. Comprobaciones si el índice nunca llega:

- Abre `joy.cpl`: si el mando aparece con estado OK, todo funciona.
- Cierra DS4Windows, reWASD, x360ce o cualquier otra app con mandos virtuales.
- Reinstala ViGEmBus (https://vigem.org/downloads/) y reinicia el PC.
- El fallback a DualShock 4 solo ocurre si el plug de Xbox 360 falla de verdad.

## 10. El receptor muestra un error de Bluetooth

- `Bluetooth está APAGADO`: actívalo en Configuración > Bluetooth.
- `CreateAsync falló`: cierra otros Phantom Pad Receiver abiertos (mismo UUID) o reinicia el Bluetooth del PC.
- `BindServiceNameAsync falló`: reinicia el Bluetooth del PC y vuelve a pulsar Iniciar; el receptor reintenta solo cada 5 s.
- El teléfono debe estar EMPAREJADO con el PC antes de pulsar Iniciar.

## 11. La app no conecta por RFCOMM

- Mira el registro: ahora cada intento (seguro / sin cifrado) deja el motivo real del fallo y el timeout es de 12 s por intento.
- La app cancela el descubrimiento Bluetooth antes de conectar; si el escaneo está en marcha en ajustes del sistema, ciérralo.
- Confirma que el UUID del servicio coincide (ver Diagnóstico: UUID servicio = 8e6f4d2a-1b3c-4e5f-9a7d-2c3b4a5d6e7f).
