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
import com.codescv.delta.settings.AppPreferences
import com.codescv.delta.settings.PreferencesStore
import com.codescv.delta.ui.components.FormDivider
import com.codescv.delta.ui.components.FormRow
import com.codescv.delta.ui.components.FormSection
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType
import com.codescv.delta.update.AgentFeedbackRow
import com.codescv.delta.update.CheckForUpdatesRow
import kotlinx.coroutines.launch

/** Settings destinations (in-sheet navigation stack, like Delta's NavigationStack). */
sealed class SettingsRoute(val title: String) {
    open val key: String get() = this::class.java.simpleName

    data object Controllers : SettingsRoute("Controllers")
    data class PlayerMapping(val player: Int) : SettingsRoute("Player $player") {
        override val key get() = "PlayerMapping$player"
    }
    data object Skins : SettingsRoute("Skins")
    data class SkinDetail(val family: String, val displayName: String) : SettingsRoute(displayName) {
        override val key get() = "SkinDetail-$family"
    }
    data object TouchHaptics : SettingsRoute("Touch & Haptics")
    data object Audio : SettingsRoute("Audio")
    data object Video : SettingsRoute("Video")
    data object Cores : SettingsRoute("Cores")
    data object MelonDsBios : SettingsRoute("melonDS BIOS Settings")
    data class CoreOptions(val coreId: String, val coreName: String) : SettingsRoute(coreName) {
        override val key get() = "CoreOptions-$coreId"
    }
    data class CoreOptionValues(val coreId: String, val optionKey: String, val optionTitle: String) : SettingsRoute(optionTitle) {
        override val key get() = "CoreOptionValues-$coreId-$optionKey"
    }
    data object OnlineMultiplayer : SettingsRoute("Online Multiplayer")
    data object AppIcon : SettingsRoute("Change App Icon")
    data object DeltaSync : SettingsRoute("Delta Sync")
    data object RetroAchievements : SettingsRoute("RetroAchievements")
    data object LilSettings : SettingsRoute("Lil’ Settings")
    data object Advanced : SettingsRoute("Advanced")
    data object GameLibrary : SettingsRoute("Game Library")
    data object Experimental : SettingsRoute("Experimental Features")
    data class ExperimentalDetail(val featureKey: String, val name: String) : SettingsRoute(name) {
        override val key get() = "ExperimentalDetail-$featureKey"
    }
    data object Contributors : SettingsRoute("Contributors")
    data object Licenses : SettingsRoute("Software Licenses")
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
                            val backTitle = routes.getOrNull(routes.size - 2)?.title ?: "Settings"
                            SettingsNavBar(route.title, backTitle, onBack = { nav.pop() })
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
        SettingsRoute.DeltaSync -> DeltaSyncSettings()
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

/** Root list: Android **Support** (更新与反馈) section first, then Delta 2.0.5 `SettingsView` sections. */
@Composable
private fun SettingsRoot(prefs: AppPreferences, nav: SettingsNav, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    val collapsed by remember { derivedStateOf { scroll.value > 120 } }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) { SettingsCloseButton(onClose) }
            Text(
                "Settings",
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
                "Settings",
                style = DeltaType.LargeTitle,
                color = Color.White,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
            )

            // 更新与反馈 / Support first, so Check for Updates + Send Feedback are visible without scrolling.
            FormSection(header = stringResource(R.string.support_section_header)) {
                CheckForUpdatesRow()
                FormDivider(inset = true)
                AgentFeedbackRow()
            }
            FormSection(header = "Controls") {
                NavRow("Controllers", Icons.Outlined.SportsEsports, DeltaColors.BadgePurple) { nav.push(SettingsRoute.Controllers) }
                FormDivider(inset = true)
                NavRow("Skins", Icons.Outlined.Palette, DeltaColors.BadgePink) { nav.push(SettingsRoute.Skins) }
                FormDivider(inset = true)
                NavRow("Touch & Haptics", Icons.Outlined.TouchApp, DeltaColors.BadgeRed) { nav.push(SettingsRoute.TouchHaptics) }
            }
            FormSection(header = "Emulation") {
                NavRow("Audio", Icons.AutoMirrored.Outlined.VolumeUp, DeltaColors.BadgeGreen) { nav.push(SettingsRoute.Audio) }
                FormDivider(inset = true)
                NavRow("Video", Icons.Outlined.Monitor, DeltaColors.BadgeTeal) { nav.push(SettingsRoute.Video) }
                FormDivider(inset = true)
                NavRow("Cores", Icons.Outlined.Memory, DeltaColors.BadgeCyan) { nav.push(SettingsRoute.Cores) }
            }
            FormSection {
                NavRow("Online Multiplayer", Icons.Outlined.Language, DeltaColors.BadgeOrange) { nav.push(SettingsRoute.OnlineMultiplayer) }
            }
            FormSection(header = "Display") {
                NavRow("App Icon", Icons.Outlined.GridView, DeltaColors.BadgeBlue) { nav.push(SettingsRoute.AppIcon) }
            }
            FormSection {
                NavRow("Delta Sync", Icons.Outlined.Sync, DeltaColors.BadgeIndigo) { nav.push(SettingsRoute.DeltaSync) }
                FormDivider(inset = true)
                NavRow(
                    "RetroAchievements", Icons.Outlined.MilitaryTech, DeltaColors.BadgeIndigo,
                    value = prefs.retroAchievementsUsername.ifEmpty { null },
                ) { nav.push(SettingsRoute.RetroAchievements) }
            }
            FormSection {
                NavRow("Lil’ Settings", Icons.Outlined.Tune, DeltaColors.BadgeGray) { nav.push(SettingsRoute.LilSettings) }
                FormDivider(inset = true)
                NavRow("Advanced", Icons.Outlined.Settings, DeltaColors.BadgeGray) { nav.push(SettingsRoute.Advanced) }
                FormDivider(inset = true)
                NavRow("Experimental", Icons.Outlined.Science, DeltaColors.BadgeGray) { nav.push(SettingsRoute.Experimental) }
            }
            FormSection(header = "Credits") {
                FormRow(title = "Contributors", chevron = true, onClick = { nav.push(SettingsRoute.Contributors) })
                FormDivider()
                FormRow(title = "Software Licenses", chevron = true, onClick = { nav.push(SettingsRoute.Licenses) })
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
private fun NavRow(title: String, icon: ImageVector, color: Color, value: String? = null, onClick: () -> Unit) {
    FormRow(title = title, icon = icon, iconColor = color, value = value, chevron = true, onClick = onClick)
}
