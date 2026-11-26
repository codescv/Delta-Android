package com.codescv.delta.i18n

import java.util.Locale

/** Concrete resolved UI language supported by Delta Android. */
enum class ResolvedLanguage(
    val tag: String,
    val locale: Locale,
    val nativeName: String,
) {
    EN("en", Locale.ENGLISH, "English"),
    ZH("zh", Locale.SIMPLIFIED_CHINESE, "中文"),
    JA("ja", Locale.JAPANESE, "日本語"),
}

/**
 * User-selectable language preference persisted in `AppPreferences.language`.
 *
 * Defaults to [SYSTEM] (`zh*` -> Simplified Chinese, `ja*` -> Japanese, anything else -> English).
 */
enum class AppLanguage(val raw: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en"),
    JA("ja");

    /** Resolves this preference into a concrete [ResolvedLanguage] given the OS [systemLocale]. */
    fun resolve(systemLocale: Locale = Locale.getDefault()): ResolvedLanguage = when (this) {
        ZH -> ResolvedLanguage.ZH
        EN -> ResolvedLanguage.EN
        JA -> ResolvedLanguage.JA
        SYSTEM -> resolveSystemLocale(systemLocale)
    }

    /** Display label for this option in Settings. Autonyms stay native; System Default is localized. */
    fun displayName(strings: DeltaStrings): String = when (this) {
        SYSTEM -> strings[StrKey.LANGUAGE_SYSTEM]
        ZH -> ResolvedLanguage.ZH.nativeName
        EN -> ResolvedLanguage.EN.nativeName
        JA -> ResolvedLanguage.JA.nativeName
    }

    companion object {
        fun fromRaw(raw: String?): AppLanguage =
            entries.firstOrNull { it.raw.equals(raw?.trim(), ignoreCase = true) } ?: SYSTEM

        fun resolveSystemLocale(locale: Locale?): ResolvedLanguage {
            val lang = locale?.language?.lowercase(Locale.ROOT).orEmpty()
            return when {
                lang == "zh" || lang.startsWith("zh") -> ResolvedLanguage.ZH
                lang == "ja" || lang.startsWith("ja") -> ResolvedLanguage.JA
                else -> ResolvedLanguage.EN
            }
        }
    }
}
