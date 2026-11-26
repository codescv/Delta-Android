package com.codescv.delta.i18n

/** Typed string bundle for one concrete [ResolvedLanguage]. */
class DeltaStrings(
    val language: ResolvedLanguage,
    private val table: Map<StrKey, String>,
) {
    operator fun get(key: StrKey): String =
        table[key] ?: STRINGS_EN[key] ?: error("Missing localization key: $key")

    fun format(key: StrKey, vararg args: Any): String {
        val pattern = this[key]
        return if (args.isEmpty()) pattern else String.format(language.locale, pattern, *args)
    }

    fun keys(): Set<StrKey> = table.keys

    fun rawTable(): Map<StrKey, String> = table
}

/** Singleton catalog providing cached [DeltaStrings] for `en`, `zh`, and `ja`. */
object DeltaStringCatalog {
    val en = DeltaStrings(ResolvedLanguage.EN, STRINGS_EN)
    val zh = DeltaStrings(ResolvedLanguage.ZH, STRINGS_ZH)
    val ja = DeltaStrings(ResolvedLanguage.JA, STRINGS_JA)

    fun forLanguage(language: ResolvedLanguage): DeltaStrings = when (language) {
        ResolvedLanguage.EN -> en
        ResolvedLanguage.ZH -> zh
        ResolvedLanguage.JA -> ja
    }
}
