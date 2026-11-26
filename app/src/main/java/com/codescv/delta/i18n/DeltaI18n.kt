package com.codescv.delta.i18n

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * Process-wide i18n bridge for non-Compose callers (MainActivity, LibraryController,
 * SaveFileManager, EmulatorSession, GoogleDriveSync*, UpdateManager, AgentFeedbackHelper).
 *
 * Defaults to [ResolvedLanguage.EN] so headless JVM unit tests stay deterministic regardless of
 * host OS locale; `DeltaApplication.onCreate` and `DeltaLocalizedApp` bind it to the real
 * Android system locale and persisted `AppPreferences.language`.
 */
object DeltaI18n {
    @Volatile
    var currentLanguage: ResolvedLanguage = ResolvedLanguage.EN
        private set

    fun currentStrings(): DeltaStrings = DeltaStringCatalog.forLanguage(currentLanguage)

    fun currentLocale(): Locale = currentLanguage.locale

    fun tr(key: StrKey): String = currentStrings()[key]

    fun tr(key: StrKey, vararg args: Any): String = currentStrings().format(key, *args)

    fun setResolved(language: ResolvedLanguage) {
        currentLanguage = language
    }

    fun applyPreference(
        preference: AppLanguage,
        systemLocale: Locale = deviceSystemLocale(),
    ): ResolvedLanguage {
        val resolved = preference.resolve(systemLocale)
        currentLanguage = resolved
        return resolved
    }

    /** Returns the OS system locale (unaffected by in-app ContextWrapper overrides). */
    fun deviceSystemLocale(): Locale {
        return runCatching {
            val sys = Resources.getSystem()?.configuration
            if (sys != null && !sys.locales.isEmpty) {
                sys.locales[0]
            } else {
                Locale.getDefault()
            }
        }.getOrElse { Locale.getDefault() }
    }

    /**
     * Wraps [base] in a [ContextWrapper] whose [Resources] match [language] while keeping
     * [findActivity] intact. Used both for Compose `LocalContext` and non-Compose XML toasts.
     */
    fun localizedContext(
        base: Context,
        language: ResolvedLanguage = currentLanguage,
    ): Context {
        val baseConfig = base.resources?.configuration ?: return base
        val config = Configuration(baseConfig).apply {
            setLocale(language.locale)
            setLayoutDirection(language.locale)
        }
        return LocalizedContextWrapper(base, config)
    }
}

/**
 * [ContextWrapper] that serves [Resources] from `createConfigurationContext(config)` while
 * keeping `baseContext` (typically `MainActivity`) intact so `findActivity()`, `startActivity()`,
 * `getSystemService()`, and `assets` all behave normally.
 */
internal class LocalizedContextWrapper(
    base: Context,
    config: Configuration,
) : ContextWrapper(base) {
    private val localizedResources: Resources = base.createConfigurationContext(config).resources

    override fun getResources(): Resources = localizedResources
}

/** Unwraps nested [ContextWrapper]s to locate the hosting [Activity], if any. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
