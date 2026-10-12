package cn.liuheo.twofauth

import android.content.Context
import android.content.res.Configuration

class AppPreferences(private val context: Context) {
    private val storage = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var theme: String
        get() = storage.getString("theme", "system") ?: "system"
        set(value) { storage.edit().putString("theme", value).apply() }
    var lockEnabled: Boolean
        get() = storage.getBoolean("lock", true)
        set(value) { storage.edit().putBoolean("lock", value).apply() }
    var lockDelay: Long
        get() = storage.getLong("delay", 0)
        set(value) { storage.edit().putLong("delay", value).apply() }
    var hideCodes: Boolean
        get() = storage.getBoolean("hide", false)
        set(value) { storage.edit().putBoolean("hide", value).apply() }
    var blockScreenshots: Boolean
        get() = storage.getBoolean("secure", true)
        set(value) { storage.edit().putBoolean("secure", value).apply() }
    var groupedCodes: Boolean
        get() = storage.getBoolean("grouped", true)
        set(value) { storage.edit().putBoolean("grouped", value).apply() }
    fun isDark(): Boolean = when (theme) {
        "dark" -> true
        "light" -> false
        else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
}

