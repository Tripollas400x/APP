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

- Verifica que no haya otro teléfono conectado al receptor (el receptor acepta una sesión activa).
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

- Prueba primero a 60 Hz. Repite a 120 Hz con el mismo equipo y compara RTT, pausas entre INPUT y failsafes.
- Desactiva el ahorro de batería de ColorOS para Phantom Pad (Ajustes -> Batería -> sin optimización). ColorOS mata conexiones en segundo plano agresivamente.
- Mantén la pantalla del modo mando encendida (la app lo hace automáticamente si la opción está activa).

## 7. Botones que quedan pegados

- El receptor libera todo si deja de recibir INPUT válido durante 1 segundo (failsafe).
- Si pasa con la pantalla encendida, avísanos con el log de Diagnóstico: es un bug que queremos ver.

## 8. Diagnóstico del receptor

- El receptor Windows registra cada paso con `[BT] ...`: adaptador, servicio, SDP, listener y publicación. Si algo falla, el log muestra el tipo de excepción, el mensaje real y el HRESULT (0x800710DF = Bluetooth apagado, 0x80070490 = elemento no encontrado...). Usa el botón "Guardar registro..." para adjuntarlo a un reporte.
- Botón **Prueba local (joy.cpl)**: con el receptor iniciado, barre ejes, gatillos y botones del mando virtual SIN teléfono. Observa si los controles se mueven en la pestaña de propiedades y si el panel indica «entrada comprobada» en Xbox 360.
- App: Diagnóstico muestra ahora adaptador, permisos, escaneo, emparejados, UUID del servicio y frames enviados; el registro completo se copia con un botón.

## 9. Xbox 360, índice sin asignar y mandos repetidos

El callback de ViGEm puede entregar 255 mientras Windows asigna el slot. XInput admite 0–3. El panel muestra el resultado de cada consulta (0 = responde, 1167 = desconectado). La prueba local puede atribuir un slot aunque el callback siga indicando 255, si solo un slot reproduce los informes del barrido.

1. Desconecta el teléfono, detén el receptor y anota los mandos que siguen en `joy.cpl`.
2. Inicia el receptor en Xbox 360 y vuelve a abrir `joy.cpl`. Usa **Prueba local** para reconocer qué mando reproduce el barrido.
3. Detén el receptor y comprueba qué mando desaparece. Si quedan otros, identifica el programa o dispositivo propietario antes de cambiarlo.
4. Si queda una entrada que reconoces de una sesión previa, consulta su Id. de instancia y su dispositivo padre en Administrador de dispositivos. Cierra primero la aplicación propietaria. Si está desconectada y aparece como entrada oculta, elimina únicamente esa instancia identificada.
5. Si XInput reconoce el mando y el navegador no lo lista, abre Gamepad Tester con la pestaña enfocada y pulsa un botón. Contrasta el resultado con un juego XInput.

## 10. El receptor muestra un error de Bluetooth

- `Bluetooth está APAGADO`: actívalo en Configuración > Bluetooth.
- `CreateAsync falló`: cierra otros Phantom Pad Receiver abiertos (mismo UUID) o reinicia el Bluetooth del PC.
- `BindServiceNameAsync falló`: reinicia el Bluetooth del PC y vuelve a pulsar Iniciar; el receptor reintenta solo cada 5 s.
- El teléfono debe estar EMPAREJADO con el PC antes de pulsar Iniciar.

## 11. La app no conecta por RFCOMM

- Mira el registro: ahora cada intento (seguro / sin cifrado) deja el motivo real del fallo y el timeout es de 12 s por intento.
- La app cancela el descubrimiento Bluetooth antes de conectar; si el escaneo está en marcha en ajustes del sistema, ciérralo.
- Confirma que el UUID del servicio coincide (ver Diagnóstico: UUID servicio = 8e6f4d2a-1b3c-4e5f-9a7d-2c3b4a5d6e7f).

## 12. Prueba en OPPO A60 y Windows 10/11

- Actualiza primero el receptor y después el APK. Registra la versión del sistema, versión de ViGEmBus y modo del mando.
- Ejecuta **Prueba local** con el teléfono desconectado. Verifica ejes, gatillos y botones en `joy.cpl`, el slot de XInput y que el target se libera al detener.
- Conecta por RFCOMM. Ejecuta una sesión de cinco minutos a 60 Hz y repite a 120 Hz. Registra RTT promedio en Android, pausa máxima y failsafes en Windows. Prueba botones simultáneos y diagonales.
- Con un botón pulsado, corta Bluetooth. Comprueba que se libera el mando y mide la recuperación tras reactivar Bluetooth. Repite pulsando **Desconectar** durante el reintento. En Windows prueba **Detener** mientras el servicio está reintentando.
- En HID directo, mantén Phantom Pad en primer plano, selecciona el PC y verifica el mando HID genérico en `joy.cpl`. Si el sistema retira o rechaza el perfil, guarda el evento exacto y la versión de ColorOS.
- La causa concreta de otros mandos instalados, el RTT después del arreglo y las políticas de ColorOS se verifican con el hardware.
