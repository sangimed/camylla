package org.camylla.proof

import android.content.Context
import org.json.JSONObject

internal data class CameraOption(val key: String, val label: String, val min: Double = 0.0,
    val max: Double = 1.0, val step: Double = 1.0, val unit: String = "",
    val choices: Map<Int, String> = emptyMap(), val reason: String? = null) {
    fun validate(value: Double): String? = when {
        reason != null -> reason
        !value.isFinite() || value < min || value > max -> "Outside supported range"
        choices.isNotEmpty() && (value != value.toInt().toDouble() || !choices.containsKey(value.toInt())) -> "Unsupported choice"
        step == 1.0 && value != value.toLong().toDouble() -> "An integer value is required"
        else -> null
    }
    fun json() = JSONObject().put("key", key).put("label", label).put("min", min).put("max", max)
        .put("step", step).put("unit", unit).put("reason", reason ?: JSONObject.NULL)
        .put("choices", JSONObject(choices.mapKeys { it.key.toString() }))
}

/** Preferences never include active capture, pending commands, locks or torch. */
internal class CameraSettings(context: Context) {
    private val store = context.getSharedPreferences("camera-settings-v1", Context.MODE_PRIVATE)
    var preferredPeer: String
        get() = store.getString("peer", "").orEmpty()
        set(value) { store.edit().putString("peer", value).apply() }
    var preferredCamera: String
        get() = store.getString("camera", "").orEmpty()
        set(value) { store.edit().putString("camera", value).apply() }
    var preferredMode: String
        get() = store.getString("mode", "HD").orEmpty()
        set(value) { store.edit().putString("mode", value).apply() }
    fun values(camera: String, mode: String): Map<String, Double> = decode(store.getString("values:$camera:$mode", "{}").orEmpty())
    fun save(camera: String, mode: String, values: Map<String, Double>) {
        store.edit().putString("values:$camera:$mode", JSONObject(values.filterKeys { it !in VOLATILE }).toString()).apply()
    }
    fun presets(): Map<String, String> {
        val json = runCatching { JSONObject(store.getString("presets", "{}").orEmpty()) }.getOrDefault(JSONObject())
        return json.keys().asSequence().associateWith { json.getString(it) }
    }
    fun savePreset(name: String, camera: String, mode: String, values: Map<String, Double>) {
        val existing = presets()
        require(name.trim().length in 1..40 && (existing.size < 16 || existing.containsKey(name.trim())))
        val all = JSONObject(existing)
        all.put(name.trim(), JSONObject().put("camera", camera).put("mode", mode)
            .put("values", JSONObject(values.filterKeys { it !in VOLATILE })).toString())
        store.edit().putString("presets", all.toString()).apply()
    }
    fun deletePreset(name: String) { val all = JSONObject(presets()); all.remove(name); store.edit().putString("presets", all.toString()).apply() }
    fun reset(camera: String, mode: String) { store.edit().remove("values:$camera:$mode").apply() }
    companion object {
        val VOLATILE = setOf("torch", "torchStrength", "focusLock", "aeLock", "wbLock", "focusTarget", "focusPoint", "capture", "captureAuthorized", "start")
        fun decode(text: String): Map<String, Double> = runCatching {
            val json = JSONObject(text)
            json.keys().asSequence().take(40).associateWith { json.getDouble(it) }.filterValues { it.isFinite() }
        }.getOrDefault(emptyMap())
    }
}
