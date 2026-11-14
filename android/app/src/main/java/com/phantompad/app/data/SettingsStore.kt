package com.phantompad.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val Context.dataStore by preferencesDataStore(name = "phantom_pad")

/** Modos de emulación que puede pedir el receptor. */
@Serializable
enum class PadModeRequest { XBOX360, DUALSHOCK4 }

@Serializable
enum class ProfileId(val title: String) {
    PS_CLASSIC("PlayStation Classic"),
    FPS_PRO("FPS Pro"),
    RACING("Racing"),
    CUSTOM("Custom")
}

/**
 * Ajustes globales persistidos en DataStore como JSON. Todo local, sin red.
 */
@Serializable
data class AppSettings(
    val sendRateHz: Int = 120,               // 30..240
    val deadzone: Float = 0.08f,             // 0..0.30
    val sensitivity: Float = 1.0f,           // 0.6..1.6
    val gyroEnabled: Boolean = false,
    val vibrationEnabled: Boolean = true,
    val keepAwake: Boolean = true,
    val energySaver: Boolean = false,
    val autoReconnect: Boolean = true,
    val transportMode: TransportModeWire = TransportModeWire.RECEIVER,
    val padModeRequest: PadModeRequest = PadModeRequest.XBOX360,
    val activeProfile: ProfileId = ProfileId.PS_CLASSIC,
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null
)

/** Serialización estable del modo de transporte (enum local sin dependencias). */
@Serializable
enum class TransportModeWire { RECEIVER, HID }

private val SETTINGS_KEY = stringPreferencesKey("settings_json")

class SettingsStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    init {
        scope.launch {
            appContext.dataStore.data
                .map { prefs -> prefs[SETTINGS_KEY] }
                .collect { raw ->
                    if (raw != null) {
                        runCatching { json.decodeFromString<AppSettings>(raw) }
                            .onSuccess { _settings.value = it }
                    }
                }
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        appContext.dataStore.edit { prefs ->
            prefs[SETTINGS_KEY] = json.encodeToString(AppSettings.serializer(), next)
        }
    }

    suspend fun current(): AppSettings = _settings.value
}
