package com.codescv.delta.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM `ServerSocket` mock Google Drive REST API v3 + OAuth token server (zero `com.sun.*` imports).
 * Exercises the real `HttpGoogleDriveClient` (URL encoding, multipart/resumable uploads,
 * PATCH / X-HTTP-Method-Override, 401 refresh retry, JSON parsing, MD5 checksums).
 */
class MockDriveHttpServer(
    var validAccessToken: String = "valid-token",
    var userEmail: String = "player@example.com",
    var userDisplayName: String = "Delta Player",
) : Closeable {

    data class StoredNode(
        val id: String,
        var name: String,
        var mimeType: String,
        var parentId: String,
        var bytes: ByteArray = ByteArray(0),
        var modifiedTimeMs: Long = System.currentTimeMillis(),
        var createdTimeMs: Long = System.currentTimeMillis(),
        var trashed: Boolean = false,
    ) {
        val md5Checksum: String?
            get() = if (mimeType == DRIVE_FOLDER_MIME) null else md5Hex(bytes)
    }

    private data class HttpReq(
        val method: String,
        val path: String,
        val rawQuery: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private data class HttpResp(
        val status: Int,
        val contentType: String = "application/json; charset=UTF-8",
        val body: ByteArray = ByteArray(0),
        val extraHeaders: Map<String, String> = emptyMap(),
    )

    private data class PendingResumable(
        val existingId: String?,
        val name: String,
        val parentId: String,
        val mimeType: String,
        val modifiedTimeMs: Long?,
    )

    private val idSeq = AtomicInteger(1)
    private val nodes = ConcurrentHashMap<String, StoredNode>()
    private val resumableSessions = ConcurrentHashMap<String, PendingResumable>()
    private val running = AtomicBoolean(true)
    private val executor = Executors.newCachedThreadPool()
    private val serverSocket = ServerSocket(0, 32, InetAddress.getLoopbackAddress())

    val downloadCount = AtomicInteger(0)
    val uploadCount = AtomicInteger(0)
    val refreshCount = AtomicInteger(0)
    val authHeaderHistory = mutableListOf<String>()

    @Volatile
    var onTokenRequest: ((Map<String, String>) -> Pair<Int, String>)? = null

    val port: Int get() = serverSocket.localPort
    val apiBase: String get() = "http://127.0.0.1:$port/drive/v3"
    val uploadBase: String get() = "http://127.0.0.1:$port/upload/drive/v3"
    val tokenEndpoint: String get() = "http://127.0.0.1:$port/oauth2/token"
    val revokeEndpoint: String get() = "http://127.0.0.1:$port/oauth2/revoke"

    init {
        executor.execute {
            while (running.get()) {
                val client = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                executor.execute { handleConnection(client) }
            }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }

    fun seedFolder(name: String, parentId: String = "root", createdAtMs: Long = 1000L): StoredNode {
        val existing = nodes.values.firstOrNull {
            !it.trashed && it.mimeType == DRIVE_FOLDER_MIME && it.name == name && it.parentId == parentId
        }
        if (existing != null) return existing
        val id = "folder_${idSeq.getAndIncrement()}"
        val node = StoredNode(
            id = id,
            name = name,
            mimeType = DRIVE_FOLDER_MIME,
            parentId = parentId,
            modifiedTimeMs = createdAtMs,
            createdTimeMs = createdAtMs,
        )
        nodes[id] = node
        return node
    }

    fun seedFile(
        parentId: String,
        name: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream",
        modifiedTimeMs: Long = System.currentTimeMillis(),
    ): StoredNode {
        val existing = nodes.values.firstOrNull {
            !it.trashed && it.mimeType != DRIVE_FOLDER_MIME && it.name == name && it.parentId == parentId
        }
        if (existing != null) {
            existing.bytes = bytes.copyOf()
            existing.modifiedTimeMs = modifiedTimeMs
            existing.mimeType = mimeType
            return existing
        }
        val id = "file_${idSeq.getAndIncrement()}"
        val node = StoredNode(
            id = id,
            name = name,
            mimeType = mimeType,
            parentId = parentId,
            bytes = bytes.copyOf(),
            modifiedTimeMs = modifiedTimeMs,
            createdTimeMs = modifiedTimeMs,
        )
        nodes[id] = node
        return node
    }

    fun findByPath(vararg segments: String): StoredNode? {
        var parentId = "root"
        var current: StoredNode? = null
        for (seg in segments) {
            current = nodes.values.firstOrNull { !it.trashed && it.parentId == parentId && it.name == seg }
                ?: return null
            parentId = current.id
        }
        return current
    }

    private fun handleConnection(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10_000
            val req = readHttpRequest(BufferedInputStream(client.getInputStream())) ?: return
            val resp = try {
                route(req)
            } catch (e: Exception) {
                jsonResp(500, """{"error":{"message":"${e.message}"}}""")
            }
            writeHttpResponse(client, resp)
        }
    }

    private fun readHttpRequest(input: InputStream): HttpReq? {
        val headerBuf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            headerBuf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        val headerText = headerBuf.toByteArray().toString(Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val reqLineParts = lines[0].split(' ')
        if (reqLineParts.size < 2) return null
        val method = reqLineParts[0].uppercase()
        val rawTarget = reqLineParts[1]
        val qIdx = rawTarget.indexOf('?')
        val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val rawQuery = if (qIdx >= 0) rawTarget.substring(qIdx + 1) else ""

        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                val k = lines[i].substring(0, colon).trim().lowercase()
                val v = lines[i].substring(colon + 1).trim()
                headers[k] = v
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val bytes = ByteArray(contentLength)
            var off = 0
            while (off < contentLength) {
                val n = input.read(bytes, off, contentLength - off)
                if (n <= 0) break
                off += n
            }
            bytes
        } else {
            ByteArray(0)
        }
        return HttpReq(method, path, rawQuery, headers, body)
    }

    private fun writeHttpResponse(socket: Socket, resp: HttpResp) {
        val reason = when (resp.status) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(resp.status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(resp.contentType).append("\r\n")
            append("Content-Length: ").append(resp.body.size).append("\r\n")
            for ((k, v) in resp.extraHeaders) {
                append(k).append(": ").append(v).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        val out = socket.getOutputStream()
        out.write(header)
        if (resp.body.isNotEmpty()) out.write(resp.body)
        out.flush()
    }

    private fun route(req: HttpReq): HttpResp = when {
        req.path == "/oauth2/token" -> handleToken(req)
        req.path == "/oauth2/revoke" -> jsonResp(200, "{}")
        req.path == "/drive/v3/about" -> handleAbout(req)
        req.path == "/drive/v3/files" || req.path.startsWith("/drive/v3/files/") -> handleFiles(req)
        req.path == "/upload/drive/v3/files" || req.path.startsWith("/upload/drive/v3/files/") -> handleUpload(req)
        req.path.startsWith("/resumable/") -> handleResumablePut(req)
        else -> jsonResp(404, """{"error":{"message":"Unknown path ${req.path}"}}""")
    }

    private fun checkAuth(req: HttpReq): HttpResp? {
        val auth = req.header("authorization") ?: ""
        synchronized(authHeaderHistory) { authHeaderHistory.add(auth) }
        if (auth != "Bearer $validAccessToken") {
            return jsonResp(401, """{"error":{"code":401,"message":"Invalid Credentials"}}""")
        }
        return null
    }

    private fun handleToken(req: HttpReq): HttpResp {
        val form = parseQuery(req.body.toString(Charsets.UTF_8))
        val custom = onTokenRequest
        if (custom != null) {
            val (code, resp) = custom(form)
            return jsonResp(code, resp)
        }
        if (form["grant_type"] == "refresh_token") {
            refreshCount.incrementAndGet()
            val newTok = "refreshed-token-${refreshCount.get()}"
            validAccessToken = newTok
            return jsonResp(
                200,
                """{"access_token":"$newTok","expires_in":3600,"token_type":"Bearer","scope":"$DRIVE_SCOPE"}""",
            )
        }
        return jsonResp(
            200,
            """{"access_token":"$validAccessToken","refresh_token":"mock-refresh","expires_in":3600,"scope":"$DRIVE_SCOPE"}""",
        )
    }

    private fun handleAbout(req: HttpReq): HttpResp {
        checkAuth(req)?.let { return it }
        val json = JsonObject(
            mapOf(
                "user" to JsonObject(
                    mapOf(
                        "displayName" to JsonPrimitive(userDisplayName),
                        "emailAddress" to JsonPrimitive(userEmail),
                    ),
                ),
            ),
        ).toString()
        return jsonResp(200, json)
    }

    private fun handleFiles(req: HttpReq): HttpResp {
        checkAuth(req)?.let { return it }
        val query = parseQuery(req.rawQuery)
        val method = effectiveMethod(req)

        if (method == "GET" && req.path.startsWith("/drive/v3/files/") && req.path.length > "/drive/v3/files/".length) {
            val fileId = URLDecoder.decode(req.path.removePrefix("/drive/v3/files/"), "UTF-8")
            val node = nodes[fileId]
            if (node == null || node.trashed) {
                return jsonResp(404, """{"error":{"code":404,"message":"File not found"}}""")
            }
            return if (query["alt"] == "media") {
                downloadCount.incrementAndGet()
                HttpResp(200, node.mimeType, node.bytes)
            } else {
                jsonResp(200, nodeToJson(node).toString())
            }
        }

        if (method == "GET") {
            val q = query["q"] ?: ""
            val nameFilter = Regex("""name\s*=\s*'([^']*)'""").find(q)?.groupValues?.get(1)?.replace("\\'", "'")
            val parentFilter = Regex("""'([^']*)'\s+in\s+parents""").find(q)?.groupValues?.get(1)?.replace("\\'", "'")
            val mimeFilter = Regex("""mimeType\s*=\s*'([^']*)'""").find(q)?.groupValues?.get(1)
            val matched = nodes.values.filter { n ->
                !n.trashed &&
                    (nameFilter == null || n.name == nameFilter) &&
                    (parentFilter == null || n.parentId == parentFilter) &&
                    (mimeFilter == null || n.mimeType == mimeFilter)
            }.sortedBy { it.createdTimeMs }

            val resp = JsonObject(mapOf("files" to JsonArray(matched.map(::nodeToJson)))).toString()
            return jsonResp(200, resp)
        }

        if (method == "POST") {
            val obj = syncJson.parseToJsonElement(req.body.toString(Charsets.UTF_8)).jsonObject
            val name = obj.str("name") ?: "untitled"
            val mimeType = obj.str("mimeType") ?: "application/octet-stream"
            val parentId = obj["parents"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content ?: "root"
            val id = if (mimeType == DRIVE_FOLDER_MIME) "folder_${idSeq.getAndIncrement()}" else "file_${idSeq.getAndIncrement()}"
            val now = System.currentTimeMillis()
            val node = StoredNode(
                id = id,
                name = name,
                mimeType = mimeType,
                parentId = parentId,
                modifiedTimeMs = now,
                createdTimeMs = now,
            )
            nodes[id] = node
            return jsonResp(200, nodeToJson(node).toString())
        }

        return jsonResp(405, """{"error":{"message":"Method not allowed"}}""")
    }

    private fun handleUpload(req: HttpReq): HttpResp {
        checkAuth(req)?.let { return it }
        val method = effectiveMethod(req)
        val query = parseQuery(req.rawQuery)
        val uploadType = query["uploadType"] ?: ""
        val existingId = if (req.path.startsWith("/upload/drive/v3/files/") && req.path.length > "/upload/drive/v3/files/".length) {
            URLDecoder.decode(req.path.removePrefix("/upload/drive/v3/files/"), "UTF-8")
        } else {
            null
        }

        if (uploadType == "resumable") {
            val rawMeta = req.body.toString(Charsets.UTF_8)
            val meta = if (rawMeta.isBlank()) JsonObject(emptyMap()) else syncJson.parseToJsonElement(rawMeta).jsonObject
            val sessionId = "sess_${idSeq.getAndIncrement()}"
            val name = meta.str("name") ?: nodes[existingId]?.name ?: "file"
            val parentId = meta["parents"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                ?: nodes[existingId]?.parentId ?: "root"
            val mimeType = req.header("x-upload-content-type") ?: "application/octet-stream"
            val modTime = parseRfc3339Ms(meta.str("modifiedTime")).takeIf { it > 0L }
            resumableSessions[sessionId] = PendingResumable(existingId, name, parentId, mimeType, modTime)
            return HttpResp(
                status = 200,
                extraHeaders = mapOf("Location" to "http://127.0.0.1:$port/resumable/$sessionId"),
            )
        }

        if (uploadType == "multipart" && (method == "POST" || method == "PATCH")) {
            val contentType = req.header("content-type") ?: ""
            val boundary = Regex("""boundary=([^;\s]+)""").find(contentType)?.groupValues?.get(1)
                ?: return jsonResp(400, """{"error":{"message":"Missing multipart boundary"}}""")
            val parts = parseMultipart(req.body, boundary)
            if (parts.size < 2) {
                return jsonResp(400, """{"error":{"message":"Expected 2 multipart parts"}}""")
            }
            val metaObj = syncJson.parseToJsonElement(parts[0].body.toString(Charsets.UTF_8)).jsonObject
            val mediaBytes = parts[1].body
            val mediaMime = parts[1].contentType ?: "application/octet-stream"
            val modTime = parseRfc3339Ms(metaObj.str("modifiedTime")).takeIf { it > 0L } ?: System.currentTimeMillis()
            val stored = upsertUploadedNode(
                existingId = existingId,
                name = metaObj.str("name"),
                parentId = metaObj["parents"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content,
                mimeType = mediaMime,
                bytes = mediaBytes,
                modifiedTimeMs = modTime,
            )
            uploadCount.incrementAndGet()
            return jsonResp(200, nodeToJson(stored).toString())
        }

        return jsonResp(400, """{"error":{"message":"Unsupported uploadType=$uploadType"}}""")
    }

    private fun handleResumablePut(req: HttpReq): HttpResp {
        val sessionId = req.path.removePrefix("/resumable/")
        val pending = resumableSessions.remove(sessionId)
            ?: return jsonResp(404, """{"error":{"message":"Unknown resumable session"}}""")
        val stored = upsertUploadedNode(
            existingId = pending.existingId,
            name = pending.name,
            parentId = pending.parentId,
            mimeType = pending.mimeType,
            bytes = req.body,
            modifiedTimeMs = pending.modifiedTimeMs ?: System.currentTimeMillis(),
        )
        uploadCount.incrementAndGet()
        return jsonResp(200, nodeToJson(stored).toString())
    }

    private fun upsertUploadedNode(
        existingId: String?,
        name: String?,
        parentId: String?,
        mimeType: String,
        bytes: ByteArray,
        modifiedTimeMs: Long,
    ): StoredNode {
        if (existingId != null) {
            val existing = nodes[existingId] ?: throw IllegalStateException("Missing node $existingId")
            if (!name.isNullOrBlank()) existing.name = name
            existing.mimeType = mimeType
            existing.bytes = bytes.copyOf()
            existing.modifiedTimeMs = modifiedTimeMs
            return existing
        }
        val id = "file_${idSeq.getAndIncrement()}"
        val node = StoredNode(
            id = id,
            name = name ?: "file",
            mimeType = mimeType,
            parentId = parentId ?: "root",
            bytes = bytes.copyOf(),
            modifiedTimeMs = modifiedTimeMs,
            createdTimeMs = modifiedTimeMs,
        )
        nodes[id] = node
        return node
    }

    private data class MultipartPart(val contentType: String?, val body: ByteArray)

    private fun parseMultipart(body: ByteArray, boundary: String): List<MultipartPart> {
        val delim = "--$boundary".toByteArray(Charsets.US_ASCII)
        val positions = ArrayList<Int>()
        var idx = 0
        while (idx <= body.size - delim.size) {
            var match = true
            for (j in delim.indices) {
                if (body[idx + j] != delim[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                positions.add(idx)
                idx += delim.size
            } else {
                idx++
            }
        }
        val result = ArrayList<MultipartPart>()
        for (p in 0 until positions.size - 1) {
            var start = positions[p] + delim.size
            if (start + 1 < body.size && body[start] == '\r'.code.toByte() && body[start + 1] == '\n'.code.toByte()) {
                start += 2
            }
            var end = positions[p + 1]
            if (end >= 2 && body[end - 2] == '\r'.code.toByte() && body[end - 1] == '\n'.code.toByte()) {
                end -= 2
            }
            if (start >= end) continue
            var sep = -1
            for (i in start until end - 3) {
                if (body[i] == '\r'.code.toByte() &&
                    body[i + 1] == '\n'.code.toByte() &&
                    body[i + 2] == '\r'.code.toByte() &&
                    body[i + 3] == '\n'.code.toByte()
                ) {
                    sep = i
                    break
                }
            }
            if (sep < 0) continue
            val headersText = String(body, start, sep - start, Charsets.US_ASCII)
            val ct = headersText.lineSequence()
                .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            val partBody = body.copyOfRange(sep + 4, end)
            result.add(MultipartPart(ct, partBody))
        }
        return result
    }

    private fun nodeToJson(n: StoredNode): JsonObject {
        val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        map["id"] = JsonPrimitive(n.id)
        map["name"] = JsonPrimitive(n.name)
        map["mimeType"] = JsonPrimitive(n.mimeType)
        n.md5Checksum?.let { map["md5Checksum"] = JsonPrimitive(it) }
        map["modifiedTime"] = JsonPrimitive(formatRfc3339(n.modifiedTimeMs))
        map["size"] = JsonPrimitive(n.bytes.size.toString())
        map["parents"] = JsonArray(listOf(JsonPrimitive(n.parentId)))
        map["trashed"] = JsonPrimitive(n.trashed)
        return JsonObject(map)
    }

    private fun effectiveMethod(req: HttpReq): String {
        val override = req.header("x-http-method-override")
        return if (!override.isNullOrBlank()) override.uppercase() else req.method
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = URLDecoder.decode(if (eq >= 0) pair.substring(0, eq) else pair, "UTF-8")
            val v = URLDecoder.decode(if (eq >= 0) pair.substring(eq + 1) else "", "UTF-8")
            out[k] = v
        }
        return out
    }

    private fun jsonResp(status: Int, json: String): HttpResp =
        HttpResp(status, "application/json; charset=UTF-8", json.toByteArray(Charsets.UTF_8))
}
