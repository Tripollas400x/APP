# Notas sobre el método A: HID Bluetooth directo

## Qué hace la app

`BluetoothHidDevice` (API 28+) permite al teléfono registrarse como dispositivo HID. La app registra un descriptor de mando de juego estándar:

- 4 ejes de 8 bits (X, Y, Z, Rz) con rango -127..127 (dos sticks).
- 2 ejes de 8 bits sin signo (Rx, Ry) usados como gatillos L2/R2.
- 16 botones.
- Hat switch de 4 bits (cruceta) con estado nulo.

Reporte de 10 bytes: `[id=0x01, lx, ly, rz, rx(l2), ry(r2), botones_lo, botones_hi, hat|pad]`.

## Qué puede esperar (y qué no)

| Expectativa | Realidad |
|---|---|
| Windows empareja el teléfono como dispositivo de entrada | Cierto cuando el fabricante permite el perfil HID device. |
| Windows lo ve como DualShock 4 | **Falso.** El teléfono se presenta con la identidad que da Android; es un HID genérico "Bluetooth Wireless Gamepad". No se puede falsificar la identidad DS4 de forma fiable. |
| Los juegos XInput lo detectan | Generalmente no. Los juegos DirectInput/genéricos sí. |
| Rumble desde el PC | No disponible por esta vía en v0.1. |
| Latencia | Similar al método B, pero sin receptor: el sistema consume los reportes directamente. |

## Restricciones conocidas

- **ColorOS / OPPO**: capas de fabricante pueden bloquear el registro de la app HID o pedir confirmación de seguridad. La app detecta el fallo (`onAppStatusChanged` con `registered=false`, excepciones de seguridad) y muestra el motivo en pantalla y en el log. Si falla, usa el método B.
- El host (PC) debe aceptar la conexión entrante; en algunos equipos aparece un prompt de emparejamiento adicional.
- Solo un host registrado a la vez.
- No se puede operar en segundo plano indefinidamente sin que el sistema mate el proceso (sin servicio en primer plano en v0.1; planificado).

## Descriptor (resumen hex)

```
05 01 09 05 A1 01 85 01
09 01 A1 00 09 30 09 31 09 32 09 35
15 81 25 7F 75 08 95 04 81 02 C0
09 33 09 34 15 00 26 FF 00 75 08 95 02 81 02
05 09 19 01 29 10 15 00 25 01 75 01 95 10 81 02
05 01 09 39 15 00 25 07 35 00 46 3B 01 65 14 75 04 95 01 81 42
75 04 95 01 81 03 C0
```

## Cuándo usar cada método

- **Método A**: juegos antiguos con soporte DirectInput, pruebas rápidas, o si no quieres instalar nada en el PC.
- **Método B**: máxima compatibilidad (XInput), rumble, medición de latencia yfailsafe. Es el recomendado y el que seguirá mejorando.
