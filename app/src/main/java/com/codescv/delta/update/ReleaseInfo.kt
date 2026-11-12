package com.codescv.delta.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/** One downloadable asset attached to a GitHub release. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val contentType: String = "",
)

/** Parsed GitHub release metadata needed by the in-app updater. */
data class ReleaseInfo(
    val tagName: String,
    /** [tagName] without a leading `v`/`V`. */
    val version: String,
    val title: String,
    val body: String,
    val publishedAt: String? = null,
    val htmlUrl: String,
    /** Preferred APK asset ([ReleaseJson.pickApk]); null when the release has no `.apk`. */
    val apkAsset: ReleaseAsset?,
    /** `SHA256SUMS.txt` asset when attached to the release (the APK must then match its line). */
    val checksumsAsset: ReleaseAsset? = null,
    val isPreRelease: Boolean = false,
)

/** Result of [AppUpdater.checkLatestRelease]. */
data class UpdateCheckResult(
    val currentVersion: String,
    val latest: ReleaseInfo,
    val updateAvailable: Boolean,
)

/**
 * Manual `JsonObject` parser for GitHub Releases payloads (avoids `@Serializable` codegen so R8
 * needs zero extra serializer rules).
 */
object ReleaseJson {
    const val DEFAULT_RELEASE_HTML_URL = "https://github.com/codescv/Delta-Android/releases"
    const val CHECKSUMS_ASSET_NAME = "SHA256SUMS.txt"

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /** Universal release APK: `Delta-Android-v2.0.5.apk` (no ABI suffix). */
    private val UNIVERSAL_APK = Regex("^Delta-Android-v?\\d+(\\.\\d+)*(-[0-9A-Za-z.]+)?\\.apk$", RegexOption.IGNORE_CASE)
    private val ABI_SUFFIX = Regex("-(arm64-v8a|armeabi-v7a|x86_64|x86)\\.apk$", RegexOption.IGNORE_CASE)
    private val DELTA_APK = Regex("^Delta-Android-.*\\.apk$", RegexOption.IGNORE_CASE)

    /**
     * Parses a single release object (`/releases/latest`) or a release array (`/releases?per_page=5`),
     * preferring the first non-draft non-prerelease entry and falling back to the first non-draft entry.
     */
    fun parse(text: String): ReleaseInfo {
        val root =
            try {
                json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw UpdateException(UpdateErrorCode.INVALID_RESPONSE, "Invalid release JSON", e)
            }

        val obj =
            when (root) {
                is JsonObject -> {
                    if (root.bool("draft")) {
                        throw UpdateException(UpdateErrorCode.NOT_FOUND, "Release is a draft")
                    }
                    root
                }
                is JsonArray -> {
                    if (root.isNotEmpty() && root.none { it is JsonObject }) {
                        throw UpdateException(UpdateErrorCode.INVALID_RESPONSE, "Release array contains non-objects")
                    }
                    val nonDraft = root.filterIsInstance<JsonObject>().filter { !it.bool("draft") }
                    nonDraft.firstOrNull { !it.bool("prerelease") }
                        ?: nonDraft.firstOrNull()
                        ?: throw UpdateException(UpdateErrorCode.NOT_FOUND, "No non-draft release found")
                }
                else -> throw UpdateException(UpdateErrorCode.INVALID_RESPONSE, "Release JSON is not an object")
            }

        val tag =
            obj.str("tag_name")?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw UpdateException(UpdateErrorCode.INVALID_RESPONSE, "Release JSON missing tag_name")

        val assets =
            (obj["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
                val assetObj = element as? JsonObject ?: return@mapNotNull null
                val name = assetObj.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = assetObj.str("browser_download_url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ReleaseAsset(
                    name = name,
                    downloadUrl = url,
                    sizeBytes = (assetObj["size"] as? JsonPrimitive)?.longOrNull ?: -1L,
                    contentType = assetObj.str("content_type").orEmpty(),
                )
            }

        return ReleaseInfo(
            tagName = tag,
            version = tag.removePrefix("v").removePrefix("V"),
            title = obj.str("name")?.takeIf { it.isNotBlank() } ?: tag,
            body = obj.str("body").orEmpty(),
            publishedAt = obj.str("published_at"),
            htmlUrl = obj.str("html_url")?.takeIf { it.isNotBlank() } ?: DEFAULT_RELEASE_HTML_URL,
            apkAsset = pickApk(assets),
            checksumsAsset = pickChecksums(assets),
            isPreRelease = obj.bool("prerelease"),
        )
    }

    /**
     * Picks the best APK asset:
     * 1) universal `Delta-Android-v<ver>.apk`
     * 2) any other `Delta-Android-*.apk` without an ABI suffix
     * 3) any `Delta-Android-*.apk` (per-ABI split)
     * 4) any `*.apk` (case-insensitive)
     */
    fun pickApk(assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { UNIVERSAL_APK.matches(it.name) && !ABI_SUFFIX.containsMatchIn(it.name) }
            ?: assets.firstOrNull { DELTA_APK.matches(it.name) && !ABI_SUFFIX.containsMatchIn(it.name) }
            ?: assets.firstOrNull { DELTA_APK.matches(it.name) }
            ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    /** Picks `SHA256SUMS.txt` (also accepts `SHA256SUMS` without extension), case-insensitive. */
    fun pickChecksums(assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { it.name.equals(CHECKSUMS_ASSET_NAME, ignoreCase = true) }
            ?: assets.firstOrNull { it.name.equals("SHA256SUMS", ignoreCase = true) }

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.str(key: String): String? {
        val el: JsonElement = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.contentOrNull
    }
}

/** Parser for `sha256sum` output (`<64 hex>  <name>` / `<64 hex> *<name>`), keyed by lowercase file name. */
object Sha256Sums {
    private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")
    private val LINE = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")

    fun parse(rawText: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (rawLine in rawText.removePrefix("\uFEFF").lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val m = LINE.matchEntire(trimmed) ?: continue
            val hash = m.groupValues[1].lowercase(Locale.ROOT)
            val name = m.groupValues[2].trim().replace('\\', '/').substringAfterLast('/')
            if (name.isNotEmpty()) out[name.lowercase(Locale.ROOT)] = hash
        }
        return out
    }

    /** Expected digest for [assetName] or null when the file lists no such entry. */
    fun lookup(
        rawText: String,
        assetName: String,
    ): String? = parse(rawText)[assetName.trim().lowercase(Locale.ROOT)]

    fun isValidHex64(s: String?): Boolean = s != null && HEX_64.matches(s.trim())
}
