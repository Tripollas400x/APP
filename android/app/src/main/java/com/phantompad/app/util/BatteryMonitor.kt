package com.phantompad.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Porcentaje de batería del teléfono para la barra de estado del mando. */
class BatteryMonitor(context: Context) {

    private val appContext = context.applicationContext

    private val _percent = MutableStateFlow<Int?>(null)
    val percent: StateFlow<Int?> = _percent

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) {
                _percent.value = (level * 100) / scale
            }
        }
    }

    init {
        runCatching {
            appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    fun dispose() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }
}
