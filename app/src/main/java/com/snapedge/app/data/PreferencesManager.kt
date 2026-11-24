package com.snapedge.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.snapedge.app.model.EdgeConfig
import com.snapedge.app.model.EdgeSide
import com.snapedge.app.model.SavedClip
import java.util.UUID

/**
 * Persists edge sidebar settings and the local clip vault using SharedPreferences.
 */
class PreferencesManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getEdgeConfig(): EdgeConfig {
        val sideName = prefs.getString(KEY_EDGE_SIDE, EdgeSide.RIGHT.name) ?: EdgeSide.RIGHT.name
        val side = runCatching { EdgeSide.valueOf(sideName) }.getOrDefault(EdgeSide.RIGHT)
        return EdgeConfig(
            enabled = prefs.getBoolean(KEY_EDGE_ENABLED, false),
            side = side,
            verticalPercent = prefs.getInt(KEY_VERTICAL_POS, 35),
            opacityPercent = prefs.getInt(KEY_OPACITY, 65),
            stealthMode = prefs.getBoolean(KEY_STEALTH_MODE, true)
        )
    }

    fun saveEdgeConfig(config: EdgeConfig) {
        prefs.edit()
            .putBoolean(KEY_EDGE_ENABLED, config.enabled)
            .putString(KEY_EDGE_SIDE, config.side.name)
            .putInt(KEY_VERTICAL_POS, config.verticalPercent)
            .putInt(KEY_OPACITY, config.opacityPercent)
            .putBoolean(KEY_STEALTH_MODE, config.stealthMode)
            .apply()
    }

    fun setEdgeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EDGE_ENABLED, enabled).apply()
    }

    fun getClips(): List<SavedClip> {
        val json = prefs.getString(KEY_CLIPS, null) ?: return emptyList()
        val type = object : TypeToken<List<SavedClip>>() {}.type
        return runCatching { gson.fromJson<List<SavedClip>>(json, type) }.getOrDefault(emptyList())
    }

    fun addClip(text: String, source: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val current = getClips().toMutableList()
        // Avoid duplicate consecutive entries
        current.removeAll { it.text == trimmed }
        current.add(
            0,
            SavedClip(
                id = UUID.randomUUID().toString(),
                text = trimmed,
                source = source,
                timestampMillis = System.currentTimeMillis()
            )
        )
        val bounded = if (current.size > MAX_CLIPS) current.subList(0, MAX_CLIPS) else current
        prefs.edit().putString(KEY_CLIPS, gson.toJson(bounded)).apply()
    }

    fun clearClips() {
        prefs.edit().remove(KEY_CLIPS).apply()
    }

    companion object {
        private const val PREFS_NAME = "snapedge_prefs"
        private const val KEY_EDGE_ENABLED = "edge_enabled"
        private const val KEY_EDGE_SIDE = "edge_side"
        private const val KEY_VERTICAL_POS = "vertical_pos"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_STEALTH_MODE = "stealth_mode"
        private const val KEY_CLIPS = "saved_clips"
        private const val MAX_CLIPS = 40
    }
}
