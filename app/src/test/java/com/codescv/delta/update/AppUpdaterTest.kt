package com.codescv.delta.update

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class AppUpdaterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rawServer: ServerSocket

    private val validApkBytes: ByteArray by lazy {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(Random(42).nextBytes(600 * 1024))
            zip.closeEntry()
        }
        bos.toByteArray()
    }

    private val zipWithoutManifestBytes: ByteArray by lazy {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("not an apk".toByteArray())
            zip.closeEntry()
        }
        bos.toByteArray()
    }

    /** Test updater: tiny size floor (fixtures are ~600 KB) and loopback HTTP allowed for the socket server. */
    private fun updater(
        releasesListUrl: String = AppUpdater.DEFAULT_RELEASES_LIST_URL,
        open: (URL) -> HttpURLConnection,
    ) = AppUpdater(
        releasesListUrl = releasesListUrl,
        minApkBytes = 1L,
        allowInsecureLoopback = true,
        openConnection = open,
    )

    private fun socketUpdater() = AppUpdater(minApkBytes = 1L, allowInsecureLoopback = true)

    @Before
    fun setUp() {
        rawServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!rawServer.isClosed) {
                val socket = runCatching { rawServer.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        socket.use { s ->
                            val ins = s.getInputStream()
                            val headerBuf = ByteArrayOutputStream()
                            var last4 = 0
                            while (last4 != 0x0D0A0D0A) {
                                val b = ins.read()
                                if (b < 0) break
                                headerBuf.write(b)
                                last4 = (last4 shl 8) or b
                            }
                            val reqLine =
                                headerBuf.toString(Charsets.UTF_8.name()).lineSequence().firstOrNull().orEmpty()
                            val out = s.getOutputStream()
                            if (reqLine.contains("/truncated")) {
                                val head =
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/octet-stream\r\n" +
                                        "Content-Length: 1000\r\n\r\n"
                                out.write(head.toByteArray())
                                out.write(validApkBytes, 0, 500)
                            } else {
                                val head =
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/vnd.android.package-archive\r\n" +
                                        "Content-Length: ${validApkBytes.size}\r\n\r\n"
                                out.write(head.toByteArray())
                                out.write(validApkBytes)
                            }
                            out.flush()
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    @After
    fun tearDown() {
        runCatching { rawServer.close() }
    }

    @Test
    fun endpointsAndUserAgentTargetDeltaAndroidRepo() {
        assertEquals(
            "https://api.github.com/repos/codescv/Delta-Android/releases/latest",
            AppUpdater.DEFAULT_LATEST_RELEASE_URL,
        )
        assertEquals(
            "https://api.github.com/repos/codescv/Delta-Android/releases?per_page=5",
            AppUpdater.DEFAULT_RELEASES_LIST_URL,
        )
        assertEquals("Delta-Android/2.0.5", AppUpdater.userAgent("2.0.5"))
        assertEquals("Delta-Android/2.0.5", AppUpdater.userAgent("2.0.5-DEBUG"))
        // Header injection attempts are neutralised (CR/LF/space/colon stripped).
        assertEquals("Delta-Android/2.0.5Xy", AppUpdater.userAgent("2.0.5\r\nX: y"))
        assertEquals("Delta-Android", AppUpdater.userAgent(null))
        assertEquals(20L * 1024 * 1024, AppUpdater.MIN_APK_BYTES)
        assertEquals(200L * 1024 * 1024, AppUpdater.MAX_APK_BYTES)
    }

    @Test
    fun releaseJsonPicksUniversalDeltaApkAndChecksumsAndParsesArrays() {
        val universal = ReleaseAsset("Delta-Android-v2.0.6.apk", "https://x/u.apk", 100L)
        val arm = ReleaseAsset("Delta-Android-v2.0.6-arm64-v8a.apk", "https://x/a.apk", 60L)
        val x86 = ReleaseAsset("Delta-Android-v2.0.6-x86_64.apk", "https://x/x.apk", 60L)
        val otherApk = ReleaseAsset("debug.APK", "https://x/3.apk", 50L)
        val archive = ReleaseAsset("source.zip", "https://x/src.zip", 10L)
        val sums = ReleaseAsset("SHA256SUMS.txt", "https://x/SHA256SUMS.txt", 300L)

        assertEquals(universal, ReleaseJson.pickApk(listOf(archive, otherApk, arm, x86, universal)))
        assertEquals(arm, ReleaseJson.pickApk(listOf(archive, otherApk, arm, x86)))
        assertEquals(otherApk, ReleaseJson.pickApk(listOf(archive, otherApk)))
        assertNull(ReleaseJson.pickApk(listOf(archive, sums)))
        assertEquals(sums, ReleaseJson.pickChecksums(listOf(universal, sums)))
        assertNull(ReleaseJson.pickChecksums(listOf(universal)))

        val parsed =
            ReleaseJson.parse(
                githubPayload(
                    "v2.0.6",
                    "https://github.com/codescv/Delta-Android/releases/download/v2.0.6/Delta-Android-v2.0.6.apk",
                    42L,
                ),
            )
        assertEquals("v2.0.6", parsed.tagName)
        assertEquals("2.0.6", parsed.version)
        assertEquals("Delta-Android-v2.0.6.apk", parsed.apkAsset?.name)
        assertEquals("SHA256SUMS.txt", parsed.checksumsAsset?.name)

        val listJson =
            """
            [
              {"tag_name":"v2.1.0","draft":true},
              {"tag_name":"v2.0.7-rc1","prerelease":true},
              {"tag_name":"v2.0.6","prerelease":false}
            ]
            """.trimIndent()
        assertEquals("v2.0.6", ReleaseJson.parse(listJson).tagName)
        assertEquals("v2.0.7-rc1", ReleaseJson.parse("""[{"tag_name":"v2.0.7-rc1","prerelease":true}]""").tagName)

        val draftOnlyErr =
            assertThrows(UpdateException::class.java) {
                ReleaseJson.parse("""[{"tag_name":"v2.1.0","draft":true}]""")
            }
        assertEquals(UpdateErrorCode.NOT_FOUND, draftOnlyErr.code)
    }

    @Test
    fun sha256SumsParserHandlesTextAndBinaryModesAndPaths() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        val text =
            "\uFEFF# comment\n" +
                "$a  Delta-Android-v2.0.6.apk\n" +
                "$b *dist/Delta-Android-v2.0.6-arm64-v8a.apk\r\n" +
                "garbage line\n"
        val parsed = Sha256Sums.parse(text)
        assertEquals(a, parsed["delta-android-v2.0.6.apk"])
        assertEquals("b".repeat(64), Sha256Sums.lookup(text, "Delta-Android-v2.0.6-arm64-v8a.apk"))
        assertNull(Sha256Sums.lookup(text, "Delta-Android-v2.0.6-x86_64.apk"))
        assertEquals(2, parsed.size)
        assertTrue(Sha256Sums.isValidHex64(a))
        assertFalse(Sha256Sums.isValidHex64("abc"))
    }

    @Test
    fun checkLatestReleaseStripsDebugSuffixAndFallsBackOn404() {
        val seenRequests = Collections.synchronizedList(mutableListOf<Pair<String, Map<String, String>>>())
        val payload = githubPayload("v2.0.6", "https://github.com/dl/a.apk", validApkBytes.size.toLong())

        val updater =
            updater { url ->
                FakeHttpURLConnection(
                    url = url,
                    code = 200,
                    body = payload.toByteArray(),
                    onConnect = { headers -> seenRequests += url.toString() to headers },
                )
            }

        val available = updater.checkLatestRelease("2.0.5")
        assertTrue(available.updateAvailable)
        assertEquals("2.0.6", available.latest.version)
        val (reqUrl, reqHeaders) = seenRequests.single()
        assertEquals("https://api.github.com/repos/codescv/Delta-Android/releases/latest", reqUrl)
        assertEquals("application/vnd.github+json", reqHeaders["accept"])
        assertEquals("2022-11-28", reqHeaders["x-github-api-version"])
        assertEquals("Delta-Android/2.0.6", reqHeaders["user-agent"])

        // Same base version with -DEBUG suffix must NOT report an update.
        val debugSame = updater.checkLatestRelease("2.0.6-DEBUG")
        assertFalse(debugSame.updateAvailable)
        assertEquals("2.0.6", debugSame.currentVersion)

        // When /releases/latest returns 404 (e.g. only prereleases exist), falls back to /releases?per_page=5.
        val listJson =
            """
            [
              {"tag_name":"v2.1.0","draft":true},
              {"tag_name":"v2.0.6","prerelease":true,"name":"Delta v2.0.6",
               "assets":[{"name":"Delta-Android-v2.0.6.apk",
                          "browser_download_url":"https://x/a.apk","size":10}]}
            ]
            """.trimIndent()
        val fallbackVisited = mutableListOf<String>()
        val fallbackUpdater =
            updater { url ->
                fallbackVisited += url.toString()
                if (url.path.endsWith("/latest")) {
                    FakeHttpURLConnection(url = url, code = 404, errorBody = """{"message":"Not Found"}""".toByteArray())
                } else {
                    FakeHttpURLConnection(url = url, code = 200, body = listJson.toByteArray())
                }
            }
        val fallbackResult = fallbackUpdater.checkLatestRelease("2.0.5")
        assertTrue(fallbackResult.updateAvailable)
        assertEquals("2.0.6", fallbackResult.latest.version)
        assertEquals(2, fallbackVisited.size)
        assertEquals("https://api.github.com/repos/codescv/Delta-Android/releases?per_page=5", fallbackVisited[1])
    }

    @Test
    fun checkLatestReleaseMapsErrorsIncludingGithubRateLimit() {
        fun checkWith(connFactory: (URL) -> HttpURLConnection): UpdateException =
            assertThrows(UpdateException::class.java) {
                updater(releasesListUrl = "", open = connFactory).checkLatestRelease("2.0.5")
            }

        assertEquals(UpdateErrorCode.NOT_FOUND, checkWith { FakeHttpURLConnection(it, code = 404) }.code)
        assertEquals(
            UpdateErrorCode.RATE_LIMITED,
            checkWith { FakeHttpURLConnection(it, code = 403, headers = mapOf("X-RateLimit-Remaining" to "0")) }.code,
        )
        assertEquals(
            UpdateErrorCode.RATE_LIMITED,
            checkWith {
                FakeHttpURLConnection(
                    it,
                    code = 403,
                    errorBody = """{"message":"API rate limit exceeded for 1.2.3.4"}""".toByteArray(),
                )
            }.code,
        )
        assertEquals(UpdateErrorCode.RATE_LIMITED, checkWith { FakeHttpURLConnection(it, code = 429) }.code)
        assertEquals(UpdateErrorCode.HTTP, checkWith { FakeHttpURLConnection(it, code = 403) }.code)
        assertEquals(UpdateErrorCode.HTTP, checkWith { FakeHttpURLConnection(it, code = 500) }.code)
        assertEquals(
            UpdateErrorCode.INVALID_RESPONSE,
            checkWith {
                FakeHttpURLConnection(it, code = 200, body = ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() })
            }.code,
        )
        assertEquals(
            UpdateErrorCode.INVALID_RESPONSE,
            checkWith { FakeHttpURLConnection(it, code = 200, body = "<html>error</html>".toByteArray()) }.code,
        )
    }

    @Test
    fun downloadFollowsCrossHostHttpsRedirectsAndValidatesBeforeAtomicRename() {
        val destFile = File(tempFolder.root, "updates/delta-android-2.0.6.apk")
        val visitedUrls = mutableListOf<String>()
        val cdnHeaders = mutableMapOf<String, String>()
        val progressUpdates = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        var validatedTempFile: File? = null

        val updater =
            updater { url ->
                visitedUrls += url.toString()
                when (url.host) {
                    "github.com" ->
                        if (url.path.endsWith("/start.apk")) {
                            FakeHttpURLConnection(
                                url = url,
                                code = 302,
                                headers = mapOf("Location" to "/codescv/Delta-Android/releases/download/v2.0.6/hop2.apk"),
                            )
                        } else {
                            FakeHttpURLConnection(
                                url = url,
                                code = 302,
                                headers = mapOf("Location" to "https://objects.githubusercontent.com/production/delta.apk"),
                            )
                        }
                    "objects.githubusercontent.com" ->
                        FakeHttpURLConnection(
                            url = url,
                            code = 200,
                            headers =
                                mapOf(
                                    "Content-Type" to "application/octet-stream",
                                    "Content-Length" to validApkBytes.size.toString(),
                                ),
                            body = validApkBytes,
                            onConnect = { headers -> cdnHeaders.putAll(headers) },
                        )
                    else -> error("Unexpected host: ${url.host}")
                }
            }

        val out =
            updater.downloadApk(
                url = "https://github.com/codescv/Delta-Android/releases/download/v2.0.6/start.apk",
                destFile = destFile,
                expectedSize = validApkBytes.size.toLong(),
                expectedSha256 = AppUpdater.sha256Hex(validApkBytes),
                validator = { tmpFile ->
                    validatedTempFile = tmpFile
                    assertTrue(tmpFile.isFile)
                    assertFalse("destFile must not exist before validator passes", destFile.exists())
                },
                onProgress = { done, total -> progressUpdates += done to total },
            )

        assertEquals(destFile, out)
        assertNotNull(validatedTempFile)
        assertArrayEquals(validApkBytes, out.readBytes())
        assertFalse(File(destFile.path + ".tmp").exists())
        assertEquals(3, visitedUrls.size)
        assertEquals("https://objects.githubusercontent.com/production/delta.apk", visitedUrls.last())
        assertEquals("identity", cdnHeaders["accept-encoding"])
        assertEquals("Delta-Android/2.0.6", cdnHeaders["user-agent"])
        assertNull(cdnHeaders["authorization"])
        assertEquals(validApkBytes.size.toLong(), progressUpdates.last().first)

        // Real TCP socket download via loopback ServerSocket (cleartext allowed for loopback in tests only).
        val realSocketDest = File(tempFolder.root, "updates/delta-socket.apk")
        val socketOut =
            socketUpdater().downloadApk(
                url = "http://127.0.0.1:${rawServer.localPort}/valid.apk",
                destFile = realSocketDest,
                expectedSize = validApkBytes.size.toLong(),
            )
        assertArrayEquals(validApkBytes, socketOut.readBytes())

        // Validator failure (SIGNATURE_MISMATCH) cleans up .tmp and never creates destFile.
        val sigDest = File(tempFolder.root, "updates/delta-bad-sig.apk")
        val sigErr =
            assertThrows(UpdateException::class.java) {
                updater.downloadApk(
                    url = "https://github.com/codescv/Delta-Android/releases/download/v2.0.6/start.apk",
                    destFile = sigDest,
                    validator = { throw UpdateException(UpdateErrorCode.SIGNATURE_MISMATCH, "Cert mismatch") },
                )
            }
        assertEquals(UpdateErrorCode.SIGNATURE_MISMATCH, sigErr.code)
        assertFalse(sigDest.exists())
        assertFalse(File(sigDest.path + ".tmp").exists())
    }

    @Test
    fun httpsToHttpRedirectIsRejectedAsInsecureRedirect() {
        val destFile = File(tempFolder.root, "updates/guard.apk")
        val visited = mutableListOf<String>()
        val downgradeUpdater =
            updater { url ->
                visited += url.toString()
                FakeHttpURLConnection(
                    url = url,
                    code = 302,
                    headers = mapOf("Location" to "http://objects.githubusercontent.com/delta.apk"),
                )
            }
        val err =
            assertThrows(UpdateException::class.java) {
                downgradeUpdater.downloadApk("https://github.com/codescv/Delta-Android/releases/download/v2.0.6/a.apk", destFile)
            }
        assertEquals(UpdateErrorCode.INSECURE_REDIRECT, err.code)
        assertTrue(err.detail.contains("https -> http"))
        assertEquals("the http URL must never be opened", 1, visited.size)
        assertFalse(destFile.exists())

        // Even with the loopback test exemption, an https -> http://127.0.0.1 downgrade is refused.
        val loopbackDowngrade =
            updater { url ->
                FakeHttpURLConnection(url = url, code = 301, headers = mapOf("Location" to "http://127.0.0.1:1/x.apk"))
            }
        assertEquals(
            UpdateErrorCode.INSECURE_REDIRECT,
            assertThrows(UpdateException::class.java) {
                loopbackDowngrade.downloadApk("https://github.com/a.apk", destFile)
            }.code,
        )

        // The release JSON check is protected the same way.
        val jsonDowngrade =
            updater { url ->
                FakeHttpURLConnection(url = url, code = 307, headers = mapOf("Location" to "http://api.github.com/evil"))
            }
        assertEquals(
            UpdateErrorCode.INSECURE_REDIRECT,
            assertThrows(UpdateException::class.java) { jsonDowngrade.checkLatestRelease("2.0.5") }.code,
        )
    }

    @Test
    fun moreThanFiveRedirectHopsAreRejected() {
        val destFile = File(tempFolder.root, "updates/loop.apk")
        var hopsSeen = 0
        val loopUpdater =
            updater { url ->
                hopsSeen++
                FakeHttpURLConnection(url = url, code = 302, headers = mapOf("Location" to "/hop/$hopsSeen"))
            }
        val loopErr =
            assertThrows(UpdateException::class.java) {
                loopUpdater.downloadApk("https://github.com/hop/0", destFile)
            }
        assertEquals(UpdateErrorCode.TOO_MANY_REDIRECTS, loopErr.code)
        assertEquals(1 + AppUpdater.MAX_REDIRECTS, hopsSeen)

        // Exactly 5 hops is still accepted.
        var hops = 0
        val fiveHopUpdater =
            updater { url ->
                if (hops < AppUpdater.MAX_REDIRECTS) {
                    hops++
                    FakeHttpURLConnection(url = url, code = 302, headers = mapOf("Location" to "/hop/$hops"))
                } else {
                    FakeHttpURLConnection(url = url, code = 200, body = validApkBytes)
                }
            }
        val ok = fiveHopUpdater.downloadApk("https://github.com/hop/0", destFile)
        assertArrayEquals(validApkBytes, ok.readBytes())
    }

    @Test
    fun nonHttpsSchemesAndMissingLocationAreRefused() {
        val destFile = File(tempFolder.root, "updates/guard.apk")

        val fileSchemeUpdater =
            updater { url -> FakeHttpURLConnection(url = url, code = 302, headers = mapOf("Location" to "file:///etc/passwd")) }
        assertEquals(
            UpdateErrorCode.INSECURE_REDIRECT,
            assertThrows(UpdateException::class.java) { fileSchemeUpdater.downloadApk("https://github.com/dl", destFile) }.code,
        )

        val noLocUpdater = updater { url -> FakeHttpURLConnection(url = url, code = 302) }
        assertEquals(
            UpdateErrorCode.HTTP,
            assertThrows(UpdateException::class.java) { noLocUpdater.downloadApk("https://github.com/dl", destFile) }.code,
        )

        // Production configuration (no loopback exemption) refuses cleartext URLs outright.
        var opened = false
        val strict =
            AppUpdater(minApkBytes = 1L) { url ->
                opened = true
                FakeHttpURLConnection(url = url, code = 200, body = validApkBytes)
            }
        val strictErr =
            assertThrows(UpdateException::class.java) {
                strict.downloadApk("http://127.0.0.1:${rawServer.localPort}/valid.apk", destFile)
            }
        assertEquals(UpdateErrorCode.NETWORK, strictErr.code)
        assertFalse(opened)
        assertThrows(UpdateException::class.java) { strict.downloadApk("http://github.com/x.apk", destFile) }
    }

    @Test
    fun sha256SumsMismatchIsCorruptAndMatchSucceeds() {
        val apkUrl = "https://github.com/codescv/Delta-Android/releases/download/v2.0.6/Delta-Android-v2.0.6.apk"
        val sumsUrl = "https://github.com/codescv/Delta-Android/releases/download/v2.0.6/SHA256SUMS.txt"
        fun release(withSums: Boolean) =
            ReleaseInfo(
                tagName = "v2.0.6",
                version = "2.0.6",
                title = "v2.0.6",
                body = "",
                htmlUrl = ReleaseJson.DEFAULT_RELEASE_HTML_URL,
                apkAsset = ReleaseAsset("Delta-Android-v2.0.6.apk", apkUrl, validApkBytes.size.toLong()),
                checksumsAsset = if (withSums) ReleaseAsset("SHA256SUMS.txt", sumsUrl, 100L) else null,
            )

        fun updaterServing(sumsText: String) =
            updater { url ->
                when {
                    url.toString() == sumsUrl -> FakeHttpURLConnection(url = url, code = 200, body = sumsText.toByteArray())
                    url.toString() == apkUrl -> FakeHttpURLConnection(url = url, code = 200, body = validApkBytes)
                    else -> error("Unexpected URL $url")
                }
            }

        val goodHash = AppUpdater.sha256Hex(validApkBytes)
        val otherHash = "0".repeat(64)
        val sumsOk = "$otherHash  Delta-Android-v2.0.6-arm64-v8a.apk\n$goodHash  Delta-Android-v2.0.6.apk\n"
        val okDest = File(tempFolder.root, "updates/ok.apk")
        val ok = updaterServing(sumsOk).downloadRelease(release(withSums = true), okDest)
        assertArrayEquals(validApkBytes, ok.readBytes())
        assertEquals(goodHash, AppUpdater.sha256Hex(ok))

        // Mismatch -> CORRUPT, no file left behind.
        val badDest = File(tempFolder.root, "updates/bad.apk")
        val mismatch =
            assertThrows(UpdateException::class.java) {
                updaterServing("$otherHash  Delta-Android-v2.0.6.apk\n").downloadRelease(release(withSums = true), badDest)
            }
        assertEquals(UpdateErrorCode.CORRUPT, mismatch.code)
        assertTrue(mismatch.detail.contains("SHA-256 mismatch"))
        assertFalse(badDest.exists())
        assertFalse(File(badDest.path + ".tmp").exists())

        // Checksums file present but without an entry for the APK -> CORRUPT before downloading.
        val missing =
            assertThrows(UpdateException::class.java) {
                updaterServing("$goodHash  something-else.apk\n").downloadRelease(release(withSums = true), badDest)
            }
        assertEquals(UpdateErrorCode.CORRUPT, missing.code)

        // Direct API: malformed expected digest is rejected.
        assertEquals(
            UpdateErrorCode.CORRUPT,
            assertThrows(UpdateException::class.java) {
                updaterServing(sumsOk).downloadApk(apkUrl, badDest, expectedSha256 = "xyz")
            }.code,
        )

        // No SHA256SUMS.txt asset -> structure + validator checks only.
        val noSumsDest = File(tempFolder.root, "updates/nosums.apk")
        val noSums = updaterServing("unused").downloadRelease(release(withSums = false), noSumsDest)
        assertArrayEquals(validApkBytes, noSums.readBytes())

        // Release without APK -> NO_APK.
        assertEquals(
            UpdateErrorCode.NO_APK,
            assertThrows(UpdateException::class.java) {
                updaterServing(sumsOk).downloadRelease(release(withSums = true).copy(apkAsset = null), noSumsDest)
            }.code,
        )
    }

    @Test
    fun sizeSanityWindowIsEnforced() {
        val destFile = File(tempFolder.root, "updates/size.apk")
        val defaultUpdater =
            AppUpdater(allowInsecureLoopback = true) { url -> FakeHttpURLConnection(url = url, code = 200, body = validApkBytes) }
        // 600 KB APK is below the 20 MB floor.
        val small =
            assertThrows(UpdateException::class.java) { defaultUpdater.downloadApk("https://github.com/a.apk", destFile) }
        assertEquals(UpdateErrorCode.CORRUPT, small.code)
        assertFalse(destFile.exists())
        assertFalse(File(destFile.path + ".tmp").exists())

        // Declared asset size above the 200 MB ceiling is rejected before any connection.
        var opened = false
        val big =
            AppUpdater { url ->
                opened = true
                FakeHttpURLConnection(url = url, code = 200, body = validApkBytes)
            }
        assertEquals(
            UpdateErrorCode.CORRUPT,
            assertThrows(UpdateException::class.java) {
                big.downloadApk("https://github.com/a.apk", destFile, expectedSize = 300L * 1024 * 1024)
            }.code,
        )
        assertFalse(opened)

        // Content-Length above the ceiling is rejected.
        val bigHeader =
            AppUpdater { url ->
                FakeHttpURLConnection(url = url, code = 200, headers = mapOf("Content-Length" to (250L * 1024 * 1024).toString()))
            }
        assertEquals(
            UpdateErrorCode.CORRUPT,
            assertThrows(UpdateException::class.java) { bigHeader.downloadApk("https://github.com/a.apk", destFile) }.code,
        )

        // Streams that exceed a (test-sized) ceiling are aborted.
        val capped =
            AppUpdater(minApkBytes = 1L, maxApkBytes = 100_000L) { url ->
                FakeHttpURLConnection(url = url, code = 200, body = validApkBytes)
            }
        assertEquals(
            UpdateErrorCode.CORRUPT,
            assertThrows(UpdateException::class.java) { capped.downloadApk("https://github.com/a.apk", destFile) }.code,
        )
        assertFalse(File(destFile.path + ".tmp").exists())
    }

    @Test
    fun rejectsCorruptDownloadsAndSupportsCancellation() {
        val destFile = File(tempFolder.root, "updates/corrupt.apk")

        val badUpdaters =
            listOf(
                updater { url ->
                    FakeHttpURLConnection(
                        url = url,
                        code = 200,
                        headers = mapOf("Content-Type" to "text/html; charset=utf-8"),
                        body = "<html>portal</html>".toByteArray(),
                    )
                },
                updater { url -> FakeHttpURLConnection(url = url, code = 200, body = zipWithoutManifestBytes) },
                updater { url -> FakeHttpURLConnection(url = url, code = 200, body = validApkBytes.copyOf(validApkBytes.size / 2)) },
            )

        for (badUpdater in badUpdaters) {
            val err = assertThrows(UpdateException::class.java) { badUpdater.downloadApk("https://github.com/bad.apk", destFile) }
            assertEquals(UpdateErrorCode.CORRUPT, err.code)
            assertFalse(destFile.exists())
            assertFalse(File(destFile.path + ".tmp").exists())
        }

        // Fixed Content-Length truncated TCP stream.
        val truncErr =
            assertThrows(UpdateException::class.java) {
                socketUpdater().downloadApk(
                    "http://127.0.0.1:${rawServer.localPort}/truncated",
                    destFile,
                    readTimeoutMs = 5_000,
                )
            }
        assertEquals(UpdateErrorCode.CORRUPT, truncErr.code)
        assertFalse(destFile.exists())
        assertFalse(File(destFile.path + ".tmp").exists())

        // Mid-download CancelToken cancellation cleans up .tmp.
        val token = CancelToken()
        val firstChunk = CountDownLatch(1)
        val slowUpdater =
            updater { url ->
                val slowStream =
                    object : FilterInputStream(ByteArrayInputStream(validApkBytes)) {
                        override fun read(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ): Int {
                            if (token.isCancelled) throw IOException("disconnected")
                            Thread.sleep(25)
                            return super.read(b, off, minOf(len, 16 * 1024))
                        }
                    }
                FakeHttpURLConnection(
                    url = url,
                    code = 200,
                    headers = mapOf("Content-Length" to validApkBytes.size.toString()),
                    stream = slowStream,
                )
            }
        val canceller =
            Thread {
                firstChunk.await(5, TimeUnit.SECONDS)
                token.cancel()
            }.apply { start() }
        val cancelErr =
            assertThrows(UpdateException::class.java) {
                slowUpdater.downloadApk("https://github.com/slow.apk", destFile, cancelToken = token) { _, _ ->
                    firstChunk.countDown()
                }
            }
        canceller.join(5_000)
        assertEquals(UpdateErrorCode.CANCELLED, cancelErr.code)
        assertFalse(destFile.exists())
        assertFalse(File(destFile.path + ".tmp").exists())
    }

    private fun githubPayload(
        tag: String,
        apkUrl: String,
        size: Long,
    ): String =
        """
        {
          "html_url": "https://github.com/codescv/Delta-Android/releases/tag/$tag",
          "tag_name": "$tag",
          "name": "Delta $tag",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-11-12T12:00:00Z",
          "assets": [
            {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.invalid/SHA256SUMS.txt", "size": 64},
            {"name": "Delta-Android-$tag-arm64-v8a.apk", "browser_download_url": "https://example.invalid/arm.apk", "size": 10},
            {"name": "Delta-Android-$tag.apk", "browser_download_url": "$apkUrl",
             "size": $size, "content_type": "application/vnd.android.package-archive"}
          ],
          "body": "### Changes\n- In-app auto update"
        }
        """.trimIndent()

    private class FakeHttpURLConnection(
        url: URL,
        private val code: Int,
        private val headers: Map<String, String> = emptyMap(),
        private val body: ByteArray = ByteArray(0),
        private val errorBody: ByteArray = ByteArray(0),
        private val stream: InputStream? = null,
        private val onConnect: ((Map<String, String>) -> Unit)? = null,
    ) : HttpURLConnection(url) {
        private val requestProps = linkedMapOf<String, String>()
        private var notified = false

        override fun setRequestProperty(
            key: String,
            value: String,
        ) {
            requestProps[key.lowercase()] = value
        }

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() {
            if (!notified) {
                notified = true
                onConnect?.invoke(requestProps.toMap())
            }
        }

        override fun getResponseCode(): Int {
            connect()
            return code
        }

        override fun getContentType(): String? = getHeaderField("Content-Type")

        override fun getContentLengthLong(): Long = getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

        override fun getHeaderField(name: String?): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        override fun getInputStream(): InputStream {
            connect()
            return stream ?: ByteArrayInputStream(body)
        }

        override fun getErrorStream(): InputStream = ByteArrayInputStream(errorBody)
    }
}
