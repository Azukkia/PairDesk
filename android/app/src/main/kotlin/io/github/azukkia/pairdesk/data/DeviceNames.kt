package io.github.azukkia.pairdesk.data

import java.util.Locale

/** The default display name of this phone (the desktop uses the host name). */
object DeviceNames {
    /**
     * "Samsung SM-G991B", "Google Pixel 8": the model, prefixed with the
     * manufacturer unless the model already starts with it.
     */
    fun pretty(manufacturer: String?, model: String?): String {
        val man = manufacturer?.trim().orEmpty()
        val mod = model?.trim().orEmpty()
        val name = when {
            mod.isEmpty() -> man
            man.isEmpty() || mod.startsWith(man, ignoreCase = true) -> mod
            else -> "$man $mod"
        }
        val pretty = name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        return pretty.ifEmpty { "Android" }.take(SettingsStore.MAX_NAME)
    }
}
