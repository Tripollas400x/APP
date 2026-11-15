package com.phantompad.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as SystemBluetoothManager
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
 *
 * Detalles de registro de receivers: los tres broadcasts que escuchamos
 * (ACTION_STATE_CHANGED, ACTION_FOUND, ACTION_BOND_STATE_CHANGED y
 * ACTION_DISCOVERY_FINISHED) son broadcasts PROTEGIDOS del sistema: solo el
 * sistema puede enviarlos. Se registran con RECEIVER_EXPORTED porque en
 * capas de fabricante (ColorOS incluida) la entrega a receivers
 * NOT_EXPORTED es inconsistente para broadcasts de sistema, y usar EXPORTED
 * aquí no abre superficie de ataque (la acción está protegida por la
 * plataforma).
 */
@SuppressLint("MissingPermission") // se comprueban permisos en la UI antes de usar
class BluetoothManager(
    private val context: Context,
    private val log: ConnectionLog
) {

    private val systemManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? SystemBluetoothManager

    val adapter: BluetoothAdapter? get() = systemManager?.adapter

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _devices = MutableStateFlow<List<BtDeviceUi>>(emptyList())
    val devices: StateFlow<List<BtDeviceUi>> = _devices

    @Volatile
    private var scanning = false

    @Volatile
    private var foundRegistered = false

    @Volatile
    private var bondRegistered = false

    @Volatile
    private var bondAddress: String? = null

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
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
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
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // Fin de escaneo: cerrar el receiver y refrescar estados de
                    // emparejamiento (puede haber cambiado durante el escaneo).
                    if (scanning) {
                        scanning = false
                        unregisterFound()
                        refreshBonded()
                        log.add("Escaneo terminado: ${_devices.value.size} dispositivos en la lista.")
                    }
                }
            }
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
            // Solo la petición en curso resuelve la espera; otros cambios de
            // emparejamiento del entorno no interfieren.
            if (device?.address != bondAddress) {
                if (state == BluetoothDevice.BOND_BONDED) device?.let { addDevice(it) }
                return
            }
            when (state) {
                BluetoothDevice.BOND_BONDED -> {
                    device?.let { addDevice(it) }
                    refreshBonded()
                    resumeBond(true)
                }
                BluetoothDevice.BOND_NONE -> resumeBond(false)
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context, stateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
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

    /** ¿Hay un descubrimiento en curso? (los transportes lo consultan) */
    fun isDiscovering(): Boolean = try {
        adapter?.isDiscovering == true
    } catch (_: SecurityException) {
        false
    }

    // --------------------------------------------------------------- escaneo

    fun startScan() {
        val a = adapter ?: return
        if (scanning) return
        if (!foundRegistered) {
            ContextCompat.registerReceiver(
                context, foundReceiver,
                IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
            foundRegistered = true
        }
        scanning = true
        refreshBonded() // estados de emparejamiento frescos antes de listar
        log.add("Buscando dispositivos Bluetooth...")
        val started = try {
            a.startDiscovery()
        } catch (e: Exception) {
            log.add("No se pudo iniciar el escaneo: ${e.message}")
            false
        }
        if (!started) {
            // startDiscovery puede fallar en silencio: avisar y limpiar.
            scanning = false
            unregisterFound()
            log.add("Escaneo no iniciado (permisos de ubicación o Bluetooth ocupado).")
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        unregisterFound()
        runCatching { adapter?.cancelDiscovery() }
    }

    private fun unregisterFound() {
        if (foundRegistered) {
            runCatching { context.unregisterReceiver(foundReceiver) }
            foundRegistered = false
        }
    }

    // ------------------------------------------------------------ emparejado

    suspend fun ensureBonded(device: BluetoothDevice, timeoutMs: Long = 20_000): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        if (!bondRegistered) {
            ContextCompat.registerReceiver(
                context, bondReceiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
            bondRegistered = true
        }
        bondAddress = device.address
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
        bondRegistered = false
        bondAddress = null
        bondContinuation = null
        return result
    }

    fun dispose() {
        stopScan()
        runCatching { context.unregisterReceiver(stateReceiver) }
        if (bondRegistered) {
            runCatching { context.unregisterReceiver(bondReceiver) }
            bondRegistered = false
        }
        resumeBond(false)
    }

    // ------------------------------------------------------------------ interno

    private fun resumeBond(value: Boolean) {
        val cont = bondContinuation ?: return
        bondContinuation = null
        if (cont.isActive) cont.resume(value)
    }

    private fun addDevice(device: BluetoothDevice) {
        val name = device.displayName()
        val bonded = try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            false
        }
        val updated = _devices.value.filter { it.address != device.address } + BtDeviceUi(name, device.address, bonded)
        _devices.value = updated.sortedWith(compareByDescending<BtDeviceUi> { it.bonded }.thenBy { it.name })
    }

    private fun BluetoothDevice.displayName(): String = try {
        name ?: address
    } catch (_: SecurityException) {
        address
    }
}
