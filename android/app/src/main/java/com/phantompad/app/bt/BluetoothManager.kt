package com.phantompad.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.phantompad.app.util.ConnectionLog
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class BtDeviceUi(
    val name: String,
    val address: String,
    val bonded: Boolean
)

/**
 * Fachada de Bluetooth: permisos, estado del adaptador, descubrimiento,
 * emparejamiento y acceso a dispositivos. No gestiona sockets (eso es de los
 * transportes).
 */
@SuppressLint("MissingPermission") // se comprueban permisos en la UI antes de usar
class BluetoothManager(
    private val context: Context,
    private val log: ConnectionLog
) {

    private val systemManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    val adapter: BluetoothAdapter? get() = systemManager?.adapter

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _devices = MutableStateFlow<List<BtDeviceUi>>(emptyList())
    val devices: StateFlow<List<BtDeviceUi>> = _devices

    @Volatile
    private var scanning = false

    private var bondContinuation: CancellableContinuation<Boolean>? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                _enabled.value = state == BluetoothAdapter.STATE_ON
                if (state == BluetoothAdapter.STATE_OFF) stopScan()
            }
        }
    }

    private val foundReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_FOUND) return
            val device: BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
            device ?: return
            addDevice(device)
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device: BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            if (state == BluetoothDevice.BOND_BONDED) {
                device?.let { addDevice(it) }
                resumeBond(true)
            } else if (state == BluetoothDevice.BOND_NONE) {
                resumeBond(false)
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context, stateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        _enabled.value = adapter?.isEnabled == true
        refreshBonded()
    }

    // ---------------------------------------------------------------- permisos

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------------------- dispositivos

    fun refreshBonded() {
        val bonded = try {
            adapter?.bondedDevices?.orEmpty() ?: emptySet()
        } catch (_: SecurityException) {
            emptySet()
        }
        val bondedUi = bonded.map { BtDeviceUi(it.displayName(), it.address, true) }
        val known = _devices.value.filter { !it.bonded }
        _devices.value = (bondedUi + known.filter { known -> bondedUi.none { it.address == known.address } })
            .sortedWith(compareByDescending<BtDeviceUi> { it.bonded }.thenBy { it.name })
    }

    fun getDevice(address: String): BluetoothDevice? = try {
        adapter?.getRemoteDevice(address)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun isBonded(address: String): Boolean =
        _devices.value.any { it.address == address && it.bonded }

    fun enableIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    // --------------------------------------------------------------- escaneo

    fun startScan() {
        val a = adapter ?: return
        if (scanning) return
        ContextCompat.registerReceiver(
            context, foundReceiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scanning = true
        log.add("Buscando dispositivos Bluetooth...")
        try {
            a.startDiscovery()
        } catch (e: Exception) {
            log.add("No se pudo iniciar el escaneo: ${e.message}")
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { context.unregisterReceiver(foundReceiver) }
        runCatching { adapter?.cancelDiscovery() }
    }

    // ------------------------------------------------------------ emparejado

    suspend fun ensureBonded(device: BluetoothDevice, timeoutMs: Long = 20_000): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        ContextCompat.registerReceiver(
            context, bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                bondContinuation = cont
                val started = try {
                    device.createBond()
                } catch (e: Exception) {
                    log.add("createBond falló: ${e.message}")
                    false
                }
                if (!started) {
                    bondContinuation = null
                    cont.resume(false)
                }
            }
        } ?: false
        runCatching { context.unregisterReceiver(bondReceiver) }
        return result
    }

    fun dispose() {
        stopScan()
        runCatching { context.unregisterReceiver(stateReceiver) }
        runCatching { context.unregisterReceiver(bondReceiver) }
    }

    // ------------------------------------------------------------------ interno

    private fun resumeBond(value: Boolean) {
        val cont = bondContinuation ?: return
        bondContinuation = null
        if (cont.isActive) cont.resume(value)
    }

    private fun addDevice(device: BluetoothDevice) {
        val name = device.displayName()
        _devices.value = _devices.value
            .filter { it.address != device.address } +
            BtDeviceUi(name, device.address, device.bondState == BluetoothDevice.BOND_BONDED)
        _devices.value = _devices.value
            .sortedWith(compareByDescending<BtDeviceUi> { it.bonded }.thenBy { it.name })
    }

    private fun BluetoothDevice.displayName(): String = try {
        name ?: address
    } catch (_: SecurityException) {
        address
    }
}
