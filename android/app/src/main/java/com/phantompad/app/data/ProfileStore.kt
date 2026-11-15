package com.phantompad.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phantompad.app.gamepad.LayoutProfiles
import com.phantompad.app.gamepad.PadLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Guarda overrides de layouts por perfil. Sin override se usa la
 * distribución predefinida de LayoutProfiles.
 */
class ProfileStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val _overrides = MutableStateFlow<Map<String, PadLayout>>(emptyMap())
    val overrides: StateFlow<Map<String, PadLayout>> = _overrides

    init {
        scope.launch {
            appContext.dataStore.data
                .map { prefs ->
                    prefs.asMap().entries
                        .filter { it.key.name.startsWith(PREFIX) }
                        .associate { entry ->
                            val profile = entry.key.name.removePrefix(PREFIX)
                            val decoded = runCatching {
                                json.decodeFromString(PadLayout.serializer(), entry.value as String)
                            }.getOrNull()
                            profile to decoded
                        }
                        .filterValues { it != null }
                        .mapValues { it.value!! }
                }
                .collect { _overrides.value = it }
        }
    }

    fun layoutOf(profile: ProfileId): PadLayout {
        val saved = _overrides.value[profile.name] ?: return LayoutProfiles.default(profile)
        val defaults = LayoutProfiles.default(profile)
        return if (saved.geometryVersion >= 2) {
            saved.copy(elements = defaults.elements + saved.elements)
        } else {
            saved
        }
    }

    suspend fun save(profile: ProfileId, layout: PadLayout) {
        appContext.dataStore.edit { prefs ->
            prefs[stringPreferencesKey(PREFIX + profile.name)] =
                json.encodeToString(PadLayout.serializer(), layout)
        }
    }

    suspend fun reset(profile: ProfileId) {
        appContext.dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(PREFIX + profile.name))
        }
    }

    companion object {
        private const val PREFIX = "layout_"
    }
}
