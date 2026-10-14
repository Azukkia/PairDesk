package io.github.azukkia.pairdesk.data

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * The few persistence primitives [SettingsStore] needs, so that it runs on
 * the JVM in unit tests ([MemoryKeyValueStore]) and on SharedPreferences in
 * the app ([SharedPreferencesStore]). Implementations are thread-safe.
 */
interface KeyValueStore {
    fun getString(key: String): String?

    fun getBoolean(key: String, default: Boolean): Boolean

    /** Applies the changes of [block] at once (asynchronously persisted). */
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        /** Stores [value], or removes [key] when it is null. */
        fun putString(key: String, value: String?)

        fun putBoolean(key: String, value: Boolean)

        fun remove(key: String)
    }
}

class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun edit(block: KeyValueStore.Editor.() -> Unit) {
        val editor = prefs.edit()
        object : KeyValueStore.Editor {
            override fun putString(key: String, value: String?) {
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }

            override fun putBoolean(key: String, value: Boolean) {
                editor.putBoolean(key, value)
            }

            override fun remove(key: String) {
                editor.remove(key)
            }
        }.block()
        editor.apply()
    }
}

/** In-memory store (unit tests, previews). */
class MemoryKeyValueStore : KeyValueStore {
    val values = ConcurrentHashMap<String, Any>()

    override fun getString(key: String): String? = values[key] as? String

    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    @Synchronized
    override fun edit(block: KeyValueStore.Editor.() -> Unit) {
        object : KeyValueStore.Editor {
            override fun putString(key: String, value: String?) {
                if (value == null) values.remove(key) else values[key] = value
            }

            override fun putBoolean(key: String, value: Boolean) {
                values[key] = value
            }

            override fun remove(key: String) {
                values.remove(key)
            }
        }.block()
    }
}
