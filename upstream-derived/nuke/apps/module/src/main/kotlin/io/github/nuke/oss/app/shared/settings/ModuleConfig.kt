package io.github.nuke.oss.app.shared.settings

import io.github.nuke.oss.app.shared.data.MiniJson

// The types a preference is stored as. The host adapter, which owns the store's key layout, says which keys are
// preferences and of which type; every other key never leaves or enters the store through a file.
internal enum class ConfigKind { BOOLEAN, INT, LONG, FLOAT, STRING }

// The store behind the module's preferences in one host, as typed values under flat keys.
internal interface ConfigValues {
    fun all(): Map<String, Any?>
    fun put(key: String, value: Any)
    fun remove(key: String)
    fun clear()
}

internal class ConfigImportRejected(val reason: Reason, val detail: String? = null) : Exception("$reason ${detail.orEmpty()}".trim()) {
    enum class Reason { NOT_CONFIG, OTHER_HOST, NEWER_VERSION, INVALID_SETTING }
}

// How a checked file meets what is stored: REPLACE drops every preference the file does not carry, MERGE keeps them.
internal enum class ConfigImportMode { REPLACE, MERGE }

// What importing a file would do, so replacing and merging are chosen with the numbers at hand. Keys are the store's
// flat names, which is enough for the counts the screen shows and keeps the preview cheap.
internal data class ConfigPreview(
    val added: List<String>,
    val changed: List<String>,
    val unchanged: Int,
    val replacedAway: List<String>,
)

// Export, import and cleaning of the module's preferences in one host. Only the user's choices travel; failure
// records, safe mode, feature memory and the local background image stay behind. The DexKit cache lives in a store of
// its own that is never exported; cleaning is the one thing that reaches it, through forgetDex.
internal class ModuleConfig(
    private val host: String,
    private val values: ConfigValues,
    private val kindOf: (String) -> ConfigKind?,
    private val forgetDex: () -> Unit,
    private val removeFiles: () -> Unit,
) {
    fun export(): String {
        val exported = sortedMapOf<String, Any>()
        for ((key, value) in values.all()) {
            val kind = kindOf(key) ?: continue
            exported[key] = when {
                kind == ConfigKind.BOOLEAN && value is Boolean -> value
                kind == ConfigKind.INT && value is Int -> value.toLong()
                kind == ConfigKind.LONG && value is Long -> value
                // Through its decimal text, so 0.2f is written as 0.2 rather than its double expansion.
                kind == ConfigKind.FLOAT && value is Float -> value.toString().toDouble()
                kind == ConfigKind.STRING && value is String -> value
                else -> continue
            }
        }
        return MiniJson.write(linkedMapOf(FORMAT to FORMAT_ID, VERSION to CURRENT_VERSION, HOST to host, VALUES to exported))
    }

    // The whole file is checked before anything is written, so a rejected file changes nothing. REPLACE replaces the
    // current preferences; MERGE writes only the keys the file carries and leaves the rest as they are. Keys that are
    // not preferences are left as they are either way.
    fun import(text: String, mode: ConfigImportMode = ConfigImportMode.REPLACE): Int {
        val imported = read(text)
        if (mode == ConfigImportMode.REPLACE) current().keys.filter { it !in imported }.forEach(values::remove)
        imported.forEach(values::put)
        return imported.size
    }

    // The same checks as import, without writing anything: what the file would add, change, leave alone and, when the
    // file replaces the store, take away.
    fun preview(text: String): ConfigPreview {
        val imported = read(text)
        val current = current()
        val added = imported.keys.filter { it !in current }
        val changed = imported.filter { (key, value) -> key in current && current[key] != value }.keys.toList()
        val unchanged = imported.size - added.size - changed.size
        val replacedAway = current.keys.filter { it !in imported }
        return ConfigPreview(added, changed, unchanged, replacedAway)
    }

    // The preference values as stored, which is all an import or a preview may touch.
    private fun current(): Map<String, Any?> = values.all().filterKeys { kindOf(it) != null }

    private fun read(text: String): Map<String, Any> {
        val document = MiniJson.obj(MiniJson.parse(text))
        if (document == null || document[FORMAT] != FORMAT_ID) throw ConfigImportRejected(ConfigImportRejected.Reason.NOT_CONFIG)
        val version = document[VERSION] as? Long ?: throw ConfigImportRejected(ConfigImportRejected.Reason.NOT_CONFIG)
        if (version > CURRENT_VERSION) throw ConfigImportRejected(ConfigImportRejected.Reason.NEWER_VERSION)
        val source = document[HOST] as? String ?: throw ConfigImportRejected(ConfigImportRejected.Reason.NOT_CONFIG)
        if (source != host) throw ConfigImportRejected(ConfigImportRejected.Reason.OTHER_HOST, source)
        val entries = MiniJson.obj(document[VALUES]) ?: throw ConfigImportRejected(ConfigImportRejected.Reason.NOT_CONFIG)
        return entries.mapValues { (key, value) ->
            val kind = kindOf(key) ?: throw ConfigImportRejected(ConfigImportRejected.Reason.INVALID_SETTING, key)
            decode(kind, value) ?: throw ConfigImportRejected(ConfigImportRejected.Reason.INVALID_SETTING, key)
        }
    }

    // Every preference, record and memory of the module in this host, its background image and the DexKit cache, so the
    // restart that follows runs the Dex analysis again from the first question.
    fun clean() {
        values.clear()
        forgetDex()
        removeFiles()
    }

    private fun decode(kind: ConfigKind, value: Any?): Any? = when (kind) {
        ConfigKind.BOOLEAN -> value as? Boolean
        ConfigKind.INT -> (value as? Long)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        ConfigKind.LONG -> value as? Long
        ConfigKind.FLOAT -> when (value) {
            is Long -> value.toFloat()
            is Double -> value.toFloat().takeIf { it.isFinite() }
            else -> null
        }
        ConfigKind.STRING -> value as? String
    }

    private companion object {
        const val FORMAT = "format"
        const val FORMAT_ID = "io.github.nuke.oss.config"
        const val VERSION = "version"
        const val CURRENT_VERSION = 1L
        const val HOST = "host"
        const val VALUES = "values"
    }
}
