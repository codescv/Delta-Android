package com.codescv.delta.update

/**
 * Semantic version (`major.minor.patch[-preRelease]`) as used by release tags (`v1.17.2`,
 * `1.17.2-beta.1`). Build metadata (`+...`) is ignored per SemVer §10.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemanticVersion> {
    /** Release (no pre-release) sorts above any pre-release of the same core version (SemVer §11). */
    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    val isPreRelease: Boolean get() = preRelease != null

    companion object {
        private val PATTERN = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.\-]+))?$""")

        /** Strips the debug `versionNameSuffix` (`-DEBUG`) so debug builds compare by base version. */
        fun stripDebugSuffix(version: String?): String? = version?.trim()?.removeSuffix("-DEBUG")

        /**
         * Parses `1.17.2`, `v1.17.2`, `V1.17`, `1.17.2-beta1`, `1.17.2+build.5`; returns null when
         * unparsable (including numeric overflow or empty pre-release identifiers like `1.2.0-beta..1`).
         */
        fun parse(text: String?): SemanticVersion? {
            var s = text?.trim() ?: return null
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            s = s.substringBefore('+')
            val match = PATTERN.matchEntire(s) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
            val pre = match.groupValues[4].ifEmpty { null }
            if (pre != null && pre.split('.').any { it.isEmpty() }) return null
            return SemanticVersion(major, minor, patch, pre)
        }

        /** True only when both parse and [latestTag] is strictly newer (fail-safe false on garbage). */
        fun isNewerVersion(
            currentVersion: String?,
            latestTag: String?,
        ): Boolean {
            val current = parse(currentVersion) ?: return false
            val latest = parse(latestTag) ?: return false
            return latest > current
        }

        private fun comparePreRelease(
            a: String?,
            b: String?,
        ): Int {
            if (a == b) return 0
            if (a == null) return 1
            if (b == null) return -1
            val pa = a.split('.')
            val pb = b.split('.')
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val nx = x.toBigIntegerOrNull()?.takeIf { x.all(Char::isDigit) }
                val ny = y.toBigIntegerOrNull()?.takeIf { y.all(Char::isDigit) }
                val cmp =
                    when {
                        nx != null && ny != null -> nx.compareTo(ny)
                        nx != null -> -1
                        ny != null -> 1
                        else -> x.compareTo(y)
                    }
                if (cmp != 0) return cmp
            }
            return pa.size.compareTo(pb.size)
        }
    }
}
