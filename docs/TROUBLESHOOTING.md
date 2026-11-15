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
