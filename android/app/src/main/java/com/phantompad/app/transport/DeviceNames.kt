package com.phantompad.app.transport

import android.bluetooth.BluetoothDevice

/**
 * Nombre seguro de un dispositivo: requiere BLUETOOTH_CONNECT en Android 12+;
 * si no está concedido devolvemos la MAC para no romper la UI.
 */
fun BluetoothDevice.safeDisplayName(): String = try {
    name ?: address
} catch (_: SecurityException) {
    address
}
