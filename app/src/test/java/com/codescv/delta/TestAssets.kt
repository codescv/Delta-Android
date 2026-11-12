package com.codescv.delta

import java.io.File

/** Shared helpers for JVM unit tests. */
object TestAssets {
    val assetsDir: File by lazy {
        val prop = System.getProperty("delta.assets")
        val candidates = listOfNotNull(prop, "src/main/assets", "app/src/main/assets")
        candidates.map(::File).first { it.isDirectory }
    }

    fun text(path: String): String = File(assetsDir, path).readText()

    fun bytes(path: String): ByteArray = File(assetsDir, path).readBytes()

    val hostLib: File? by lazy {
        System.getProperty("delta.hostlib")?.let(::File)?.takeIf { it.isFile }
    }
}
