package com.codescv.delta.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MilitaryTech
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codescv.delta.DeltaApplication
import com.codescv.delta.R
import com.codescv.delta.i18n.AppLanguage
import com.codescv.delta.i18n.DeltaI18n
import com.codescv.delta.i18n.DeltaStrings
import com.codescv.delta.i18n.LocalDeltaStrings
import com.codescv.delta.i18n.StrKey
import com.codescv.delta.i18n.str
import com.codescv.delta.settings.AppPreferences
import com.codescv.delta.settings.PreferencesStore
import com.codescv.delta.ui.components.FormCheckRow
import com.codescv.delta.ui.components.FormDivider
import com.codescv.delta.ui.components.FormRow
import com.codescv.delta.ui.components.FormSection
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType
import com.codescv.delta.update.AgentFeedbackRow
import com.codescv.delta.update.CheckForUpdatesRow
import kotlinx.coroutines.launch

/** Settings destinations (in-sheet navigation stack, like Delta's NavigationStack). */
sealed class SettingsRoute {
    open val key: String get() = this::class.java.simpleName

    abstract fun localizedTitle(strings: DeltaStrings): String

    data object Controllers : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_CONTROLLERS]
    }
    data class PlayerMapping(val player: Int) : SettingsRoute() {
        override val key get() = "PlayerMapping$player"
        override fun localizedTitle(strings: DeltaStrings) = strings.format(StrKey.SETTINGS_PLAYER_TITLE, player)
    }
    data object Skins : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_SKINS]
    }
    data class SkinDetail(val family: String, val displayName: String) : SettingsRoute() {
        override val key get() = "SkinDetail-$family"
        override fun localizedTitle(strings: DeltaStrings) = displayName
    }
    data object TouchHaptics : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_TOUCH_HAPTICS]
    }
    data object Audio : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_AUDIO]
    }
    data object Video : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_VIDEO]
    }
    data object Cores : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_CORES]
    }
    data object MelonDsBios : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_MELONDS_BIOS]
    }
    data class CoreOptions(val coreId: String, val coreName: String) : SettingsRoute() {
        override val key get() = "CoreOptions-$coreId"
        override fun localizedTitle(strings: DeltaStrings) = coreName
    }
    data class CoreOptionValues(val coreId: String, val optionKey: String, val optionTitle: String) : SettingsRoute() {
        override val key get() = "CoreOptionValues-$coreId-$optionKey"
        override fun localizedTitle(strings: DeltaStrings) = optionTitle
    }
    data object OnlineMultiplayer : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_ONLINE_MULTIPLAYER]
    }
    data object AppIcon : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_CHANGE_APP_ICON]
    }
    data object Language : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_LANGUAGE]
    }
    data object DeltaSync : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_DELTA_SYNC]
    }
    data object RetroAchievements : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_RETROACHIEVEMENTS]
    }
    data object LilSettings : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_LIL_SETTINGS]
    }
    data object Advanced : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_ADVANCED]
    }
    data object GameLibrary : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_GAME_LIBRARY]
    }
    data object Experimental : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_EXPERIMENTAL_FEATURES]
    }
    data class ExperimentalDetail(val featureKey: String, val fallbackName: String = featureKey) : SettingsRoute() {
        override val key get() = "ExperimentalDetail-$featureKey"
        override fun localizedTitle(strings: DeltaStrings) =
            SettingsData.experimentalTitle(featureKey, fallbackName, strings)
    }
    data object Contributors : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_CONTRIBUTORS]
    }
    data object Licenses : SettingsRoute() {
        override fun localizedTitle(strings: DeltaStrings) = strings[StrKey.SETTINGS_SOFTWARE_LICENSES]
    }
}

/** What every Settings page needs: preferences, navigation and a snackbar. */
class SettingsNav internal constructor(
    val store: PreferencesStore,
    private val pushRoute: (SettingsRoute) -> Unit,
    private val popRoute: () -> Unit,
    private val showMessage: (String) -> Unit,
) {
    fun push(route: SettingsRoute) = pushRoute(route)
    fun pop() = popRoute()
    fun message(text: String) = showMessage(text)
    fun update(transform: (AppPreferences) -> AppPreferences) = store.update(transform)
}

/** Settings sheet (ui_spec §6, design §5.5): large title, close circle, iOS grouped-inset cards. */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val store = DeltaApplication.container.preferences
    val prefs by store.state.collectAsState()
    val strings = LocalDeltaStrings.current
    val stack = remember { mutableStateListOf<SettingsRoute>() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    val nav = remember(store) {
        SettingsNav(
            store = store,
            pushRoute = { stack.add(it) },
            popRoute = { if (stack.isNotEmpty()) holder.removeState(stack.removeAt(stack.lastIndex).key) },
            showMessage = { text ->
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(text)
                }
            },
        )
    }

    BackHandler(enabled = stack.isNotEmpty()) { nav.pop() }

    Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(DeltaColors.GroupedBackground),
        ) {
            AnimatedContent(
                targetState = stack.toList(),
                contentKey = { it.lastOrNull()?.key ?: "root" },
                transitionSpec = {
                    val forward = targetState.size >= initialState.size
                    val enter = slideInHorizontally(tween(280)) { w -> if (forward) w else -w / 3 } + fadeIn(tween(280))
                    val exit = slideOutHorizontally(tween(280)) { w -> if (forward) -w / 3 else w } + fadeOut(tween(200))
                    enter togetherWith exit
                },
                label = "settings-nav",
            ) { routes ->
                val route = routes.lastOrNull()
                holder.SaveableStateProvider(route?.key ?: "root") {
                    if (route == null) {
                        SettingsRoot(prefs, nav, onClose)
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            val backTitle = routes.getOrNull(routes.size - 2)?.localizedTitle(strings)
                                ?: strings[StrKey.SETTINGS_TITLE]
                            SettingsNavBar(route.localizedTitle(strings), backTitle, onBack = { nav.pop() })
                            SettingsDestination(route, prefs, nav)
                        }
                    }
                }
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFF3A3A3C),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsDestination(route: SettingsRoute, prefs: AppPreferences, nav: SettingsNav) {
    when (route) {
        SettingsRoute.Controllers -> ControllersSettings(prefs, nav)
        is SettingsRoute.PlayerMapping -> PlayerMappingSettings(route.player, prefs, nav)
        SettingsRoute.Skins -> SkinsSettings(prefs, nav)
        is SettingsRoute.SkinDetail -> SkinDetailSettings(route.family, prefs, nav)
        SettingsRoute.TouchHaptics -> TouchHapticsSettings(prefs, nav)
        SettingsRoute.Audio -> AudioSettings(prefs, nav)
        SettingsRoute.Video -> VideoSettings(prefs, nav)
        SettingsRoute.Cores -> CoresSettings(prefs, nav)
        SettingsRoute.MelonDsBios -> MelonDsBiosSettings(prefs, nav)
        is SettingsRoute.CoreOptions -> CoreOptionsSettings(route.coreId, prefs, nav)
        is SettingsRoute.CoreOptionValues -> CoreOptionValuesSettings(route.coreId, route.optionKey, prefs, nav)
        SettingsRoute.OnlineMultiplayer -> OnlineMultiplayerSettings(prefs, nav)
        SettingsRoute.AppIcon -> AppIconSettings(prefs, nav)
        SettingsRoute.Language -> LanguageSettings(prefs, nav)
        SettingsRoute.DeltaSync -> DeltaSyncSettings(prefs, nav)
        SettingsRoute.RetroAchievements -> RetroAchievementsSettings(prefs, nav)
        SettingsRoute.LilSettings -> LilSettings(prefs, nav)
        SettingsRoute.Advanced -> AdvancedSettings(nav)
        SettingsRoute.GameLibrary -> GameLibrarySettings(prefs, nav)
        SettingsRoute.Experimental -> ExperimentalFeaturesSettings(prefs, nav)
        is SettingsRoute.ExperimentalDetail -> ExperimentalFeatureDetail(route.featureKey, prefs, nav)
        SettingsRoute.Contributors -> ContributorsSettings()
        SettingsRoute.Licenses -> SoftwareLicensesSettings()
    }
}

/** Root list: Android Support section first, then Delta 2.0.5 `SettingsView` sections. */
@Composable
private fun SettingsRoot(prefs: AppPreferences, nav: SettingsNav, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    val collapsed by remember { derivedStateOf { scroll.value > 120 } }
    val syncState by DeltaApplication.container.sync.uiState.collectAsState()
    val strings = LocalDeltaStrings.current
    val currentLanguage = AppLanguage.fromRaw(prefs.language)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) { SettingsCloseButton(onClose) }
            Text(
                str(StrKey.SETTINGS_TITLE),
                style = DeltaType.Headline,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).alpha(if (collapsed) 1f else 0f),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
        ) {
            Text(
                str(StrKey.SETTINGS_TITLE),
                style = DeltaType.LargeTitle,
                color = Color.White,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
            )

            // Support section first, so Check for Updates + Send Feedback are visible without scrolling.
            FormSection(header = stringResource(R.string.support_section_header)) {
                CheckForUpdatesRow()
                FormDivider(inset = true)
                AgentFeedbackRow()
            }
            FormSection(header = str(StrKey.SETTINGS_SECTION_CONTROLS)) {
                NavRow(str(StrKey.SETTINGS_CONTROLLERS), Icons.Outlined.SportsEsports, DeltaColors.BadgePurple) { nav.push(SettingsRoute.Controllers) }
                FormDivider(inset = true)
                NavRow(str(StrKey.SETTINGS_SKINS), Icons.Outlined.Palette, DeltaColors.BadgePink) { nav.push(SettingsRoute.Skins) }
                FormDivider(inset = true)
                NavRow(str(StrKey.SETTINGS_TOUCH_HAPTICS), Icons.Outlined.TouchApp, DeltaColors.BadgeRed) { nav.push(SettingsRoute.TouchHaptics) }
            }
            FormSection(header = str(StrKey.SETTINGS_SECTION_EMULATION)) {
                NavRow(str(StrKey.SETTINGS_AUDIO), Icons.AutoMirrored.Outlined.VolumeUp, DeltaColors.BadgeGreen) { nav.push(SettingsRoute.Audio) }
                FormDivider(inset = true)
                NavRow(str(StrKey.SETTINGS_VIDEO), Icons.Outlined.Monitor, DeltaColors.BadgeTeal) { nav.push(SettingsRoute.Video) }
                FormDivider(inset = true)
                NavRow(str(StrKey.SETTINGS_CORES), Icons.Outlined.Memory, DeltaColors.BadgeCyan) { nav.push(SettingsRoute.Cores) }
            }
            FormSection {
                NavRow(str(StrKey.SETTINGS_ONLINE_MULTIPLAYER), Icons.Outlined.Language, DeltaColors.BadgeOrange) { nav.push(SettingsRoute.OnlineMultiplayer) }
            }
            FormSection(header = str(StrKey.SETTINGS_SECTION_DISPLAY)) {
                NavRow(str(StrKey.SETTINGS_APP_ICON), Icons.Outlined.GridView, DeltaColors.BadgeBlue) { nav.push(SettingsRoute.AppIcon) }
                FormDivider(inset = true)
                NavRow(
                    str(StrKey.SETTINGS_LANGUAGE),
                    Icons.Outlined.Translate,
                    DeltaColors.BadgeTeal,
                    value = currentLanguage.displayName(strings),
                ) { nav.push(SettingsRoute.Language) }
            }
            FormSection {
                NavRow(
                    str(StrKey.SETTINGS_DELTA_SYNC), Icons.Outlined.Sync, DeltaColors.BadgeIndigo,
                    value = when {
                        syncState.pendingConflicts.isNotEmpty() ->
                            strings.format(StrKey.SETTINGS_CONFLICTS_BADGE, syncState.pendingConflicts.size)
                        syncState.isSignedIn -> syncState.accountEmail.ifBlank { "Google Drive" }
                        else -> null
                    },
                ) { nav.push(SettingsRoute.DeltaSync) }
                FormDivider(inset = true)
                NavRow(
                    str(StrKey.SETTINGS_RETROACHIEVEMENTS), Icons.Outlined.MilitaryTech, DeltaColors.BadgeIndigo,
                    value = prefs.retroAchievementsUsername.ifEmpty { null },
                ) { nav.push(SettingsRoute.RetroAchievements) }
            }
            FormSection {
                NavRow(str(StrKey.SETTINGS_LIL_SETTINGS), Icons.Outlined.Tune, DeltaColors.BadgeGray) { nav.push(SettingsRoute.LilSettings) }
                FormDivider(inset = true)
                NavRow(str(StrKey.SETTINGS_ADVANCED), Icons.Outlined.Settings, DeltaColors.BadgeGray) { nav.push(SettingsRoute.Advanced) }
                FormDivider(inset = true)
                NavRow(str(StrKey.SETTINGS_EXPERIMENTAL), Icons.Outlined.Science, DeltaColors.BadgeGray) { nav.push(SettingsRoute.Experimental) }
            }
            FormSection(header = str(StrKey.SETTINGS_SECTION_CREDITS)) {
                FormRow(title = str(StrKey.SETTINGS_CONTRIBUTORS), chevron = true, onClick = { nav.push(SettingsRoute.Contributors) })
                FormDivider()
                FormRow(title = str(StrKey.SETTINGS_SOFTWARE_LICENSES), chevron = true, onClick = { nav.push(SettingsRoute.Licenses) })
            }
            Spacer(Modifier.height(12.dp))
            Text(
                SettingsData.FOOTER,
                style = DeltaType.Footnote,
                color = DeltaColors.SystemGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

@Composable
internal fun LanguageSettings(prefs: AppPreferences, nav: SettingsNav) {
    val strings = LocalDeltaStrings.current
    val selected = AppLanguage.fromRaw(prefs.language)
    val systemResolved = remember { AppLanguage.SYSTEM.resolve(DeltaI18n.deviceSystemLocale()) }

    SettingsPage {
        FormSection(footer = str(StrKey.LANGUAGE_FOOTER)) {
            AppLanguage.entries.forEachIndexed { index, option ->
                if (index > 0) FormDivider()
                val subtitle = if (option == AppLanguage.SYSTEM) {
                    strings.format(StrKey.LANGUAGE_SYSTEM_SUBTITLE, systemResolved.nativeName)
                } else {
                    null
                }
                FormCheckRow(
                    title = option.displayName(strings),
                    subtitle = subtitle,
                    selected = option == selected,
                ) {
                    DeltaI18n.applyPreference(option)
                    nav.update { it.copy(language = option.raw) }
                }
            }
        }
    }
}

@Composable
private fun NavRow(title: String, icon: ImageVector, color: Color, value: String? = null, onClick: () -> Unit) {
    FormRow(title = title, icon = icon, iconColor = color, value = value, chevron = true, onClick = onClick)
}
