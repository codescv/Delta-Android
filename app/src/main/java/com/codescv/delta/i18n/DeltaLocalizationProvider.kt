package com.codescv.delta.i18n

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.codescv.delta.DeltaApplication

val LocalDeltaLanguage = staticCompositionLocalOf { ResolvedLanguage.EN }
val LocalDeltaStrings = staticCompositionLocalOf { DeltaStringCatalog.en }

@Composable
@ReadOnlyComposable
fun str(key: StrKey): String = LocalDeltaStrings.current[key]

@Composable
@ReadOnlyComposable
fun str(key: StrKey, vararg args: Any): String = LocalDeltaStrings.current.format(key, *args)

/**
 * Provides reactive, instant (no-restart) localization across the entire Compose tree:
 * - [LocalDeltaStrings] / [LocalDeltaLanguage] for all Kotlin UI chrome
 * - [LocalContext] + [LocalConfiguration] wrapped with a localized [Configuration] so Android
 *   `stringResource(R.string.*)` (updater + Agent feedback XML) switches immediately as well.
 */
@Composable
fun DeltaLocalizedApp(content: @Composable () -> Unit) {
    val prefs by DeltaApplication.container.preferences.state.collectAsState()
    val baseConfig = LocalConfiguration.current
    val baseContext = LocalContext.current

    val systemLocale = remember(baseConfig) { DeltaI18n.deviceSystemLocale() }
    val preference = remember(prefs.language) { AppLanguage.fromRaw(prefs.language) }
    val resolved = remember(preference, systemLocale) { preference.resolve(systemLocale) }
    val strings = remember(resolved) { DeltaStringCatalog.forLanguage(resolved) }

    // Keep the non-Compose bridge synchronously aligned with the active UI language.
    DeltaI18n.setResolved(resolved)
    SideEffect { DeltaI18n.setResolved(resolved) }

    val localizedConfig = remember(baseConfig, resolved) {
        Configuration(baseConfig).apply {
            setLocale(resolved.locale)
            setLayoutDirection(resolved.locale)
        }
    }
    val localizedContext = remember(baseContext, localizedConfig) {
        LocalizedContextWrapper(baseContext, localizedConfig)
    }

    CompositionLocalProvider(
        LocalDeltaLanguage provides resolved,
        LocalDeltaStrings provides strings,
        LocalConfiguration provides localizedConfig,
        LocalContext provides localizedContext,
        content = content,
    )
}
