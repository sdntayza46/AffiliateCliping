package com.dantez.autoclip.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("auto_clip_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_WATERMARK = "watermark"
        private const val KEY_WATERMARK_LIST = "watermark_list_json"
        private const val KEY_DURATION = "duration_range"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_Y_OFFSET = "y_offset"
        private const val KEY_API_KEYS = "api_keys_json"
        private const val KEY_VIBRATE_ON_EXPORT = "vibrate_on_export"
        private const val KEY_DYNAMIC_ZOOM = "enable_dynamic_zoom"
        private const val KEY_SFX_TRANSITION = "sfx_transition"
        private const val KEY_ENABLE_SUBTITLES = "enable_subtitles"
        private const val KEY_ENABLE_ANTI_REUSED = "enable_anti_reused"
        private const val KEY_OUTPUT_VIDEO_COUNT = "output_video_count"
        private const val KEY_CAS_SHARPEN = "cas_sharpen"
        private const val KEY_GITHUB_REPO = "github_repo"
        private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
    }

    init {
        // Bersihkan key lama jika masih tersimpan di SharedPreferences HP
        val map = getAllKeys().toMutableMap()
        var modified = false
        map.forEach { (prov, list) ->
            val cleaned = list.filterNot { it.key.startsWith("vbk_pro_dc22f73b") }
            if (cleaned.size != list.size) {
                map[prov] = cleaned
                modified = true
            }
        }
        if (modified) {
            saveAllKeys(map)
        }
    }

    // --- WATERMARK PERSISTENCE & PRESETS ---
    fun getWatermarkList(): List<String> {
        val json = prefs.getString(KEY_WATERMARK_LIST, null)
        val list = if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            val oldWm = prefs.getString(KEY_WATERMARK, "")?.trim() ?: ""
            if (oldWm.isNotBlank()) listOf(oldWm) else emptyList()
        }
        return list.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    fun saveWatermarkList(list: List<String>) {
        val cleaned = list.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val json = gson.toJson(cleaned)
        prefs.edit().putString(KEY_WATERMARK_LIST, json).apply()
    }

    fun addWatermark(watermark: String): Boolean {
        val trimmed = watermark.trim()
        if (trimmed.isBlank()) return false
        val currentList = getWatermarkList().toMutableList()
        if (!currentList.contains(trimmed)) {
            currentList.add(trimmed)
            saveWatermarkList(currentList)
        }
        setSelectedWatermark(trimmed)
        return true
    }

    fun deleteWatermark(watermark: String) {
        val trimmed = watermark.trim()
        val currentList = getWatermarkList().toMutableList()
        currentList.remove(trimmed)
        saveWatermarkList(currentList)
        if (getSelectedWatermark() == trimmed) {
            setSelectedWatermark("")
        }
    }

    fun getSelectedWatermark(): String {
        return prefs.getString(KEY_WATERMARK, "")?.trim() ?: ""
    }

    fun setSelectedWatermark(watermark: String) {
        prefs.edit().putString(KEY_WATERMARK, watermark.trim()).apply()
    }

    fun getWatermark(): String = getSelectedWatermark()
    fun saveWatermark(watermark: String) = addWatermark(watermark)
    fun deleteWatermark() = setSelectedWatermark("")

    // --- PREFERENCES ---
    fun getDurationRange(): String = prefs.getString(KEY_DURATION, "30-60") ?: "30-60"
    fun setDurationRange(value: String) = prefs.edit().putString(KEY_DURATION, value).apply()

    fun getResolution(): String = prefs.getString(KEY_RESOLUTION, "1080p") ?: "1080p"
    fun setResolution(value: String) = prefs.edit().putString(KEY_RESOLUTION, value).apply()

    fun getZoom(): Float = prefs.getFloat(KEY_ZOOM, 1.0f)
    fun setZoom(value: Float) = prefs.edit().putFloat(KEY_ZOOM, value).apply()

    fun getYOffset(): Float = prefs.getFloat(KEY_Y_OFFSET, 0.0f)
    fun setYOffset(value: Float) = prefs.edit().putFloat(KEY_Y_OFFSET, value).apply()

    fun getCasSharpen(): Float = prefs.getFloat(KEY_CAS_SHARPEN, 0.0f)
    fun setCasSharpen(value: Float) = prefs.edit().putFloat(KEY_CAS_SHARPEN, value).apply()

    fun isVibrateOnExportEnabled(): Boolean = prefs.getBoolean(KEY_VIBRATE_ON_EXPORT, true)
    fun setVibrateOnExportEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_VIBRATE_ON_EXPORT, value).apply()

    fun isDynamicZoomEnabled(): Boolean = prefs.getBoolean(KEY_DYNAMIC_ZOOM, true)
    fun setDynamicZoomEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_DYNAMIC_ZOOM, value).apply()

    fun getSfxTransition(): String = prefs.getString(KEY_SFX_TRANSITION, "whoosh") ?: "whoosh"
    fun setSfxTransition(value: String) = prefs.edit().putString(KEY_SFX_TRANSITION, value).apply()

    fun isSubtitlesEnabled(): Boolean = prefs.getBoolean(KEY_ENABLE_SUBTITLES, true)
    fun setSubtitlesEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ENABLE_SUBTITLES, value).apply()

    fun isAntiReusedEnabled(): Boolean = prefs.getBoolean(KEY_ENABLE_ANTI_REUSED, true)
    fun setAntiReusedEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ENABLE_ANTI_REUSED, value).apply()

    fun getOutputVideoCount(): Int = prefs.getInt(KEY_OUTPUT_VIDEO_COUNT, 1).coerceIn(1, 5)
    fun setOutputVideoCount(count: Int) = prefs.edit().putInt(KEY_OUTPUT_VIDEO_COUNT, count.coerceIn(1, 5)).apply()

    fun getGithubRepo(): String {
        val saved = prefs.getString(KEY_GITHUB_REPO, null)
        return if (saved.isNullOrBlank() || saved == "dantez-dev/dantez-cliping") {
            "sdntayza46/AffiliateCliping"
        } else {
            saved
        }
    }
    fun setGithubRepo(repo: String) = prefs.edit().putString(KEY_GITHUB_REPO, repo.trim()).apply()

    fun isAutoCheckUpdateEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CHECK_UPDATE, true)
    fun setAutoCheckUpdateEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATE, enabled).apply()

    // --- API KEYS ROTATION ---
    fun getAllKeys(): Map<String, List<KeyItem>> {
        val json = prefs.getString(KEY_API_KEYS, null) ?: return mapOf(
            "groq" to emptyList()
        )
        val type = object : TypeToken<Map<String, List<KeyItem>>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveAllKeys(map: Map<String, List<KeyItem>>) {
        val json = gson.toJson(map)
        prefs.edit().putString(KEY_API_KEYS, json).apply()
    }

    fun addKey(provider: String, keyText: String): Int {
        val map = getAllKeys().toMutableMap()
        val list = map[provider]?.toMutableList() ?: mutableListOf()
        val lines = keyText.split("\n", ",", " ")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 5 }

        var added = 0
        for (k in lines) {
            if (list.none { it.key == k }) {
                list.add(KeyItem(id = UUID.randomUUID().toString().substring(0, 8), key = k))
                added++
            }
        }
        map[provider] = list
        saveAllKeys(map)
        return added
    }

    fun getActiveKey(provider: String): KeyItem? {
        val list = getAllKeys()[provider] ?: return null
        return list.firstOrNull { it.status == "active" }
    }

    fun markKeyExhausted(provider: String, keyId: String) {
        val map = getAllKeys().toMutableMap()
        val list = map[provider]?.toMutableList() ?: return
        val item = list.firstOrNull { it.id == keyId } ?: return
        item.status = "exhausted"
        item.failCount++
        map[provider] = list
        saveAllKeys(map)
    }

    fun removeKey(provider: String, keyId: String) {
        val map = getAllKeys().toMutableMap()
        val list = map[provider]?.toMutableList() ?: return
        list.removeAll { it.id == keyId }
        map[provider] = list
        saveAllKeys(map)
    }

    fun clearKeys(provider: String) {
        val map = getAllKeys().toMutableMap()
        map[provider] = emptyList()
        saveAllKeys(map)
    }

    fun clearAllKeys() {
        val map = mutableMapOf(
            "groq" to emptyList<KeyItem>()
        )
        saveAllKeys(map)
    }

    fun resetAllKeys() {
        val map = getAllKeys().toMutableMap()
        val list = map["groq"]?.toMutableList() ?: return
        list.forEach { it.status = "active" }
        map["groq"] = list
        saveAllKeys(map)
    }
}
