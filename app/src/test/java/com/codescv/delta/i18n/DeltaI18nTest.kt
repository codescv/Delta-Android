package com.codescv.delta.i18n

import com.codescv.delta.cheats.CheatFormat
import com.codescv.delta.cheats.CheatValidator
import com.codescv.delta.emulation.FastForwardMode
import com.codescv.delta.model.GameSystem
import com.codescv.delta.saves.SaveTransferException
import com.codescv.delta.settings.AppPreferences
import com.codescv.delta.settings.ExperimentalFeatures
import com.codescv.delta.settings.PreferencesStore
import com.codescv.delta.settings.VideoFilter
import com.codescv.delta.sync.ConflictPolicy
import com.codescv.delta.sync.SyncResult
import com.codescv.delta.ui.library.LibraryLogic
import com.codescv.delta.ui.library.LibraryPage
import com.codescv.delta.ui.library.RomMenuInfo
import com.codescv.delta.ui.library.gameMenuEntries
import com.codescv.delta.ui.settings.DeltaIconSection
import com.codescv.delta.ui.settings.SettingsData
import com.codescv.delta.ui.settings.SettingsRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class DeltaI18nTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun resetLanguage() {
        DeltaI18n.setResolved(ResolvedLanguage.EN)
    }

    // ------------------------------------------------------------------- Language resolution

    @Test
    fun systemDefaultResolvesChineseJapaneseAndEnglishFallback() {
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale.SIMPLIFIED_CHINESE))
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale.TRADITIONAL_CHINESE))
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale.forLanguageTag("zh-HK")))
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale.forLanguageTag("zh")))

        assertEquals(ResolvedLanguage.JA, AppLanguage.SYSTEM.resolve(Locale.JAPANESE))
        assertEquals(ResolvedLanguage.JA, AppLanguage.SYSTEM.resolve(Locale.JAPAN))

        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.US))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.UK))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.FRANCE))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.GERMANY))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.KOREA))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.ROOT))
    }

    @Test
    fun explicitLanguageOverridesSystemLocale() {
        assertEquals(ResolvedLanguage.ZH, AppLanguage.ZH.resolve(Locale.US))
        assertEquals(ResolvedLanguage.EN, AppLanguage.EN.resolve(Locale.SIMPLIFIED_CHINESE))
        assertEquals(ResolvedLanguage.JA, AppLanguage.JA.resolve(Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun fromRawDefaultsToSystemAndSupportsCaseInsensitiveValues() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromRaw(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromRaw(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromRaw("fr"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromRaw("system"))
        assertEquals(AppLanguage.ZH, AppLanguage.fromRaw("zh"))
        assertEquals(AppLanguage.ZH, AppLanguage.fromRaw(" ZH "))
        assertEquals(AppLanguage.EN, AppLanguage.fromRaw("en"))
        assertEquals(AppLanguage.JA, AppLanguage.fromRaw("ja"))
    }

    @Test
    fun languageOptionDisplayNamesMatchSpec() {
        assertEquals("System Default", AppLanguage.SYSTEM.displayName(DeltaStringCatalog.en))
        assertEquals("跟随系统", AppLanguage.SYSTEM.displayName(DeltaStringCatalog.zh))
        assertEquals("システムに従う", AppLanguage.SYSTEM.displayName(DeltaStringCatalog.ja))

        for (catalog in listOf(DeltaStringCatalog.en, DeltaStringCatalog.zh, DeltaStringCatalog.ja)) {
            assertEquals("中文", AppLanguage.ZH.displayName(catalog))
            assertEquals("English", AppLanguage.EN.displayName(catalog))
            assertEquals("日本語", AppLanguage.JA.displayName(catalog))
        }
    }

    // ------------------------------------------------------------------- Persistence round-trip

    @Test
    fun languagePreferencePersistsAndDefaultsToSystem() {
        val file = File(tmp.root, "preferences.json")
        val initialStore = PreferencesStore(file)
        assertEquals(AppLanguage.SYSTEM.raw, initialStore.value.language)

        for (lang in listOf(AppLanguage.ZH, AppLanguage.JA, AppLanguage.EN, AppLanguage.SYSTEM)) {
            initialStore.update { it.copy(language = lang.raw) }
            val reloaded = PreferencesStore(file).value
            assertEquals(lang.raw, reloaded.language)
            assertEquals(lang, AppLanguage.fromRaw(reloaded.language))
        }
    }

    @Test
    fun legacyPreferencesJsonWithoutLanguageFieldDefaultsToSystem() {
        val file = File(tmp.root, "legacy_preferences.json")
        file.writeText("""{"filter":"lcdGrid","haptics":false}""")
        val loaded = PreferencesStore(file).value
        assertEquals("lcdGrid", loaded.filter)
        assertFalse(loaded.haptics)
        assertEquals(AppLanguage.SYSTEM.raw, loaded.language)
    }

    // ------------------------------------------------------------------- Key & placeholder parity

    private val placeholderRegex = Regex("""%(\d+\$)?[sd]""")
    private val cjkRegex = Regex("""[\u3040-\u30FF\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]""")
    private val bilingualLeakRegex = Regex("""(更新与反馈\s*/|/[\s]*更新与反馈|\(Check for Updates\)|\(Send Feedback\)|检查更新\s*\(|反馈给\s*Agent\s*\()""")

    @Test
    fun catalogHasFullKeyParityNonEmptyValuesAndMatchingPlaceholders() {
        val allKeys = StrKey.entries.toSet()
        val catalogs = listOf(DeltaStringCatalog.en, DeltaStringCatalog.zh, DeltaStringCatalog.ja)

        for (catalog in catalogs) {
            val missing = allKeys - catalog.keys()
            val extra = catalog.keys() - allKeys
            assertTrue("${catalog.language}: missing keys $missing", missing.isEmpty())
            assertTrue("${catalog.language}: extra keys $extra", extra.isEmpty())
        }

        for (key in StrKey.entries) {
            val enVal = DeltaStringCatalog.en[key]
            val zhVal = DeltaStringCatalog.zh[key]
            val jaVal = DeltaStringCatalog.ja[key]

            assertTrue("EN blank for $key", enVal.isNotBlank())
            assertTrue("ZH blank for $key", zhVal.isNotBlank())
            assertTrue("JA blank for $key", jaVal.isNotBlank())

            // Zero CJK leaks in English strings.
            assertFalse("EN string for $key contains CJK: '$enVal'", cjkRegex.containsMatchIn(enVal))

            // Zero bilingual CN+EN leaks in any language.
            assertFalse("EN bilingual leak for $key: '$enVal'", bilingualLeakRegex.containsMatchIn(enVal))
            assertFalse("ZH bilingual leak for $key: '$zhVal'", bilingualLeakRegex.containsMatchIn(zhVal))
            assertFalse("JA bilingual leak for $key: '$jaVal'", bilingualLeakRegex.containsMatchIn(jaVal))

            // Identical format placeholders across all 3 languages.
            val enPlaceholders = placeholderRegex.findAll(enVal).map { it.value }.toSet()
            val zhPlaceholders = placeholderRegex.findAll(zhVal).map { it.value }.toSet()
            val jaPlaceholders = placeholderRegex.findAll(jaVal).map { it.value }.toSet()
            assertEquals("ZH placeholder mismatch for $key", enPlaceholders, zhPlaceholders)
            assertEquals("JA placeholder mismatch for $key", enPlaceholders, jaPlaceholders)

            // Format execution smoke test (catches malformed % sequences).
            if (enPlaceholders.isNotEmpty()) {
                val maxIndex = enPlaceholders.maxOf { p ->
                    p.removePrefix("%").substringBefore('$').toIntOrNull() ?: 1
                }
                val args = Array<Any>(maxIndex) { idx ->
                    val ordinal = idx + 1
                    if (enPlaceholders.any { it == "%$ordinal\$d" }) ordinal else "sample$ordinal"
                }
                for (catalog in catalogs) {
                    val formatted = catalog.format(key, *args)
                    assertTrue("Formatted $key blank in ${catalog.language}", formatted.isNotBlank())
                }
            }
        }
    }

    // ------------------------------------------------------------------- XML strings_update.xml

    private fun resDir(): File =
        listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }

    private fun parseStringsXml(file: File): LinkedHashMap<String, String> {
        assertTrue("Missing XML resource file: ${file.path}", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val map = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            map[el.getAttribute("name")] = el.textContent
        }
        return map
    }

    @Test
    fun stringsUpdateXmlParityAndZeroBilingualLeaks() {
        val res = resDir()
        val en = parseStringsXml(File(res, "values/strings_update.xml"))
        val zh = parseStringsXml(File(res, "values-zh/strings_update.xml"))
        val zhCn = parseStringsXml(File(res, "values-zh-rCN/strings_update.xml"))
        val ja = parseStringsXml(File(res, "values-ja/strings_update.xml"))

        assertTrue("Expected at least 55 update/feedback strings", en.size >= 55)
        assertEquals("values-zh keys must match values", en.keys, zh.keys)
        assertEquals("values-zh-rCN keys must match values", en.keys, zhCn.keys)
        assertEquals("values-ja keys must match values", en.keys, ja.keys)
        assertEquals("values-zh and values-zh-rCN must stay identical", zh, zhCn)

        // Verify explicitly cleaned strings in default English XML.
        assertEquals("Support", en["support_section_header"])
        assertEquals("Check for Updates", en["update_row_title"])
        assertEquals("Send Feedback", en["agent_feedback_title"])

        for ((name, enVal) in en) {
            val zhVal = zh.getValue(name)
            val jaVal = ja.getValue(name)

            assertTrue("EN blank XML key $name", enVal.isNotBlank())
            assertTrue("ZH blank XML key $name", zhVal.isNotBlank())
            assertTrue("JA blank XML key $name", jaVal.isNotBlank())

            assertFalse("EN XML key $name has CJK leak: '$enVal'", cjkRegex.containsMatchIn(enVal))
            assertFalse("EN XML key $name has bilingual leak: '$enVal'", bilingualLeakRegex.containsMatchIn(enVal))
            assertFalse("ZH XML key $name has bilingual leak: '$zhVal'", bilingualLeakRegex.containsMatchIn(zhVal))
            assertFalse("JA XML key $name has bilingual leak: '$jaVal'", bilingualLeakRegex.containsMatchIn(jaVal))

            val enPh = placeholderRegex.findAll(enVal).map { it.value }.toSet()
            val zhPh = placeholderRegex.findAll(zhVal).map { it.value }.toSet()
            val jaPh = placeholderRegex.findAll(jaVal).map { it.value }.toSet()
            assertEquals("ZH XML placeholder mismatch for $name", enPh, zhPh)
            assertEquals("JA XML placeholder mismatch for $name", enPh, jaPh)
        }
    }

    // ------------------------------------------------------------------- Reactive domain helpers

    @Test
    fun domainHelpersAndRoutesLocalizeCleanlyAcrossEnZhJa() {
        // Library & context menu
        assertEquals("收藏", LibraryPage.Favorites.localizedTitle(DeltaStringCatalog.zh))
        assertEquals("お気に入り", LibraryPage.Favorites.localizedTitle(DeltaStringCatalog.ja))
        assertEquals("搜索", LibraryLogic.topTitle("mario", null, DeltaStringCatalog.zh))
        assertEquals("検索", LibraryLogic.topTitle("mario", null, DeltaStringCatalog.ja))

        val info = RomMenuInfo("auto1", hasSaveRam = true, latestState = null)
        assertEquals("开始游戏", gameMenuEntries(false, info, DeltaStringCatalog.zh).first().title)
        assertEquals("プレイ", gameMenuEntries(false, info, DeltaStringCatalog.ja).first().title)

        // Settings routes & enums
        assertEquals("语言", SettingsRoute.Language.localizedTitle(DeltaStringCatalog.zh))
        assertEquals("言語", SettingsRoute.Language.localizedTitle(DeltaStringCatalog.ja))
        assertEquals("锐利像素", VideoFilter.SHARP_PIXELS.localizedTitle(DeltaStringCatalog.zh))
        assertEquals("シャープピクセル", VideoFilter.SHARP_PIXELS.localizedTitle(DeltaStringCatalog.ja))
        assertEquals("点按切换", FastForwardMode.TOGGLE.localizedTitle(DeltaStringCatalog.zh))
        assertEquals("タップで切り替え", FastForwardMode.TOGGLE.localizedTitle(DeltaStringCatalog.ja))
        assertEquals("每次询问", ConflictPolicy.ASK.localizedTitle(DeltaStringCatalog.zh))
        assertEquals("毎回確認", ConflictPolicy.ASK.localizedTitle(DeltaStringCatalog.ja))
        assertEquals("玻璃质感", DeltaIconSection.GLASS.localizedTitle(DeltaStringCatalog.zh))
        assertEquals("グラス", DeltaIconSection.GLASS.localizedTitle(DeltaStringCatalog.ja))

        // Experimental feature presentation
        val gpuFeature = ExperimentalFeatures.defaults().first { it.key == "metal" }
        assertEquals("GPU 渲染器", SettingsData.present(gpuFeature, DeltaStringCatalog.zh).name)
        assertEquals("GPU レンダラー", SettingsData.present(gpuFeature, DeltaStringCatalog.ja).name)

        // Proper nouns stay untranslated across all 3 languages
        assertEquals("Delta Sync", SettingsRoute.DeltaSync.localizedTitle(DeltaStringCatalog.zh))
        assertEquals("Delta Sync", SettingsRoute.DeltaSync.localizedTitle(DeltaStringCatalog.ja))
        assertEquals("RetroAchievements", SettingsRoute.RetroAchievements.localizedTitle(DeltaStringCatalog.zh))
        assertEquals("RetroAchievements", SettingsRoute.RetroAchievements.localizedTitle(DeltaStringCatalog.ja))
        assertEquals("Game Boy Advance", GameSystem.GBA.displayName)
        assertEquals("melonDS", GameSystem.NDS.coreDisplayName)

        // Non-Compose errors follow active DeltaI18n language immediately
        DeltaI18n.setResolved(ResolvedLanguage.ZH)
        assertEquals("该游戏尚无存档。", SaveTransferException.noSaveYet().message)
        assertEquals("已是最新", SyncResult().summaryText())
        val invalidZh = CheatValidator.validate("", GameSystem.GBA, CheatFormat.GAME_SHARK) as CheatValidator.Result.Invalid
        assertEquals("代码不能为空。", invalidZh.reason)

        DeltaI18n.setResolved(ResolvedLanguage.JA)
        assertEquals("このゲームにはまだセーブがありません。", SaveTransferException.noSaveYet().message)
        assertEquals("最新の状態です", SyncResult().summaryText())
        val invalidJa = CheatValidator.validate("", GameSystem.GBA, CheatFormat.GAME_SHARK) as CheatValidator.Result.Invalid
        assertEquals("コードが空です。", invalidJa.reason)

        DeltaI18n.setResolved(ResolvedLanguage.EN)
        assertNotEquals(DeltaStringCatalog.en[StrKey.SETTINGS_TITLE], DeltaStringCatalog.zh[StrKey.SETTINGS_TITLE])
        assertNotEquals(DeltaStringCatalog.en[StrKey.SETTINGS_TITLE], DeltaStringCatalog.ja[StrKey.SETTINGS_TITLE])
    }
}
