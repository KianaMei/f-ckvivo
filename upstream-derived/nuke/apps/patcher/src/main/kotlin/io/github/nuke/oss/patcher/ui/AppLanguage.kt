package io.github.nuke.oss.patcher.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList

// The language the user picked for this app. On Android 13 and later it is the system's per-app language, so the
// choice made here and the one in system settings are the same value and the system recreates the Activity. Earlier
// versions have no such setting: the choice is kept in a preference and applied to the Activity's base context and to
// the application's resources, which the view model and toasts read.
internal object AppLanguage {
    const val SYSTEM = ""
    // Keep in step with res/xml/locales_config.xml.
    val CHOICES = listOf(SYSTEM, "en", "zh")
    private const val PREFERENCES = "settings"
    private const val KEY = "language"

    // The choice that stands for a list of language tags as the system writes it, such as "zh-Hans-CN,en".
    fun choiceOf(tags: String): String {
        val first = tags.substringBefore(',').trim()
        return CHOICES.firstOrNull { it != SYSTEM && (first == it || first.startsWith("$it-")) } ?: SYSTEM
    }

    fun current(context: Context): String = choiceOf(
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        else stored(context),
    )

    fun select(activity: Activity, choice: String) {
        require(choice in CHOICES) { "Unknown language $choice" }
        if (Build.VERSION.SDK_INT >= 33) {
            // An empty list follows the system language again.
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(choice)
        } else {
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, choice).apply()
            applyToApplication(activity.applicationContext)
            activity.recreate()
        }
    }

    // The Activity's base context in the chosen language; the system already provides it on Android 13 and later.
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val choice = choiceOf(stored(base))
        if (choice == SYSTEM) return base
        val configuration = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(choice)) }
        return base.createConfigurationContext(configuration)
    }

    fun applyToApplication(application: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val choice = choiceOf(stored(application))
        val locales = if (choice == SYSTEM) Resources.getSystem().configuration.locales else LocaleList.forLanguageTags(choice)
        val resources = application.resources
        val configuration = Configuration(resources.configuration).apply { setLocales(locales) }
        // createConfigurationContext cannot replace the application's own resources, which is what must change here.
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    private fun stored(context: Context): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, SYSTEM).orEmpty()
}
