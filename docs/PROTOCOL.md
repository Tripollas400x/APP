# Phantom Pad Protocol v1

Especificación del protocolo binario entre la app Android y el receptor Windows.
Transporte: RFCOMM/SPP (UUID de servicio `8e6f4d2a-1b3c-4e5f-9a7d-2c3b4a5d6e7f`).
El teléfono es el cliente RFCOMM; el PC es el servidor.

Todos los enteros multi-byte son **little-endian**.

## Formato de frame

| Offset | Tamaño | Campo |
|---|---|---|
| 0 | 1 | Magic0 = `0x50` |
| 1 | 1 | Magic1 = `0x50` |
| 2 | 1 | Versión = `0x01` |
| 3 | 1 | Tipo (ver abajo) |
| 4.. | n | Payload según tipo |
| última | 1 | CRC-8 (polinomio `0x07`, init `0x00`, sin reflexión) sobre todos los bytes anteriores |

Tipos:

| Tipo | Valor | Payload | Longitud total | Dirección |
|---|---|---|---|---|
| INPUT | `0x01` | 25 bytes | 30 | Teléfono -> PC |
| PING | `0x02` | 2 bytes | 7 | Teléfono -> PC |
| PONG | `0x03` | 2 bytes | 7 | PC -> Teléfono |
| RELEASE_ALL | `0x04` | 0 bytes | 5 | Ambos |
| RUMBLE | `0x10` | 2 bytes | 7 | PC -> Teléfono |

## INPUT (25 bytes de payload)

| Offset | Tamaño | Campo | Notas |
|---|---|---|---|
| +0 | 2 | seq | u16, se incrementa por trama |
| +2 | 4 | botones | u32 máscara de bits |
| +6 | 1 | LX | i8, -128..127, 0 = centro |
| +7 | 1 | LY | i8, **negativo = empujar hacia arriba** (convención de pantalla); el receptor invierte para XInput |
| +8 | 1 | RX | i8 |
| +9 | 1 | RY | i8, igual que LY |
| +10 | 1 | L2 | u8, 0..255 |
| +11 | 1 | R2 | u8, 0..255 |
| +12 | 1 | flags | bit0 giroscopio presente, bit1 contacto en panel táctil |
| +13 | 2 | pitch | i16, grados/s x 100 |
| +15 | 2 | roll | i16 |
| +17 | 2 | yaw | i16 |
| +19 | 2 | accX | i16, m/s^2 x 100 |
| +21 | 2 | accY | i16 |
| +23 | 2 | accZ | i16 |

## Máscara de botones

| Bit | Botón | Bit | Botón |
|---|---|---|---|
| 0 | Triángulo | 8 | L1 |
| 1 | Círculo | 9 | R1 |
| 2 | Cruz | 10 | L3 |
| 3 | Cuadrado | 11 | R3 |
| 4 | Cruceta arriba | 12 | Options (Start) |
| 5 | Cruceta abajo | 13 | Share (Select) |
| 6 | Cruceta izquierda | 14 | Botón central (PS) |
| 7 | Cruceta derecha | 15 | Click panel táctil |

## PING / PONG

Payload: `seq` (u16). El teléfono envía PING periódicamente y el PC responde con PONG con la misma secuencia. El teléfono calcula el RTT. Si no hay PONG en 3 segundos, la conexión se considera degradada y se registra en el log.

## RUMBLE

Payload: `small` (u8, motor pequeño) y `large` (u8, motor grande). El receptor lo genera cuando el mando virtual recibe feedback (solo modo Xbox 360 en v0.1) y el teléfono responde con una vibración breve local.

## RELEASE_ALL

Sin payload. Se envía al pausar, desconectar o por el watchdog. El receptor también aplica failsafe propio: si no recibe tramas durante 1000 ms, libera el mando virtual (protección contra botones atascados).

## Reglas de conversión

- Eje analógico float [-1, 1] -> i8: `round(v * 127)`, siempre en [-127, 127].
- Gatillo float [0, 1] -> u8: `round(v * 255)`.
- XInput (eje i16): `b == -128 ? -32768 : b * 257`; Y se invierte respecto a la pantalla.
- DualShock 4 (eje u8): `b + 128` (0..255, 128 = centro); Y no se invierte (0 = arriba).

## Vectores de prueba (golden vectors)

Generados por `scripts/protocol_reference.py` y verificados en los tests de Kotlin y C#:

| Caso | Frame | CRC |
|---|---|---|
| CRC-8 del estándar ("123456789") | - | `0xF4` |
| INPUT (seq=1, botones=0x0000010F, LX=-1, RX=1, L2=0.5, R2=0.25) | `50 50 01 01 01 00 0F 01 00 00 81 00 7F 00 80 40 00 00 00 00 00 00 00 00 00 00 00 00 00` | `0xB5` |
| PING (seq=0x2A) | `50 50 01 02 2A 00` | `0xFE` |
| RUMBLE (small=0x40, large=0x80) | `50 50 01 10 40 80` | `0x74` |
| RELEASE_ALL | `50 50 01 04` | `0xD1` |

## Versionado

El byte de versión permite evolucionar (más teléfonos, más ejes, rumble bidireccional) sin romper receptores antiguos: si el receptor ve una versión que no conoce, descarta el frame y lo registra.
