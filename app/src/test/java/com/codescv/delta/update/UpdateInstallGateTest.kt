package com.codescv.delta.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Install gates: package name, strictly newer versionCode, identical signing-certificate digests. */
class UpdateInstallGateTest {
    private val certA = byteArrayOf(1, 2, 3, 4)
    private val certB = byteArrayOf(5, 6, 7, 8)
    private val certC = byteArrayOf(1, 2, 3, 9)
    private val installedDigests = UpdateManager.certDigests(listOf(certA))

    private fun verify(
        archivePackage: String? = "com.codescv.delta",
        archiveVersionCode: Long = 20006L,
        archiveCerts: List<ByteArray> = listOf(certA.copyOf()),
        installedPackage: String = "com.codescv.delta",
        installedVersionCode: Long = 20005L,
        installedCerts: Set<String> = installedDigests,
    ) = UpdateManager.verifyArchive(
        installedPackage = installedPackage,
        installedVersionCode = installedVersionCode,
        installedCertDigests = installedCerts,
        archivePackage = archivePackage,
        archiveVersionCode = archiveVersionCode,
        archiveCertDigests = UpdateManager.certDigests(archiveCerts),
    )

    private fun codeOf(block: () -> Unit): UpdateErrorCode = assertThrows(UpdateException::class.java) { block() }.code

    @Test
    fun acceptsSamePackageNewerVersionSameSigner() {
        verify()
        verify(archiveVersionCode = 30000L)
        assertEquals("com.codescv.delta", UpdateManager.EXPECTED_PACKAGE)
    }

    @Test
    fun rejectsWrongPackage() {
        assertEquals(UpdateErrorCode.WRONG_PACKAGE, codeOf { verify(archivePackage = "com.evil.delta") })
        assertEquals(UpdateErrorCode.WRONG_PACKAGE, codeOf { verify(archivePackage = null) })
        // Even if the running app were renamed, only com.codescv.delta archives are accepted.
        assertEquals(
            UpdateErrorCode.WRONG_PACKAGE,
            codeOf { verify(archivePackage = "com.other", installedPackage = "com.other") },
        )
    }

    @Test
    fun rejectsVersionCodeNotGreaterThanInstalled() {
        assertEquals(UpdateErrorCode.NOT_NEWER, codeOf { verify(archiveVersionCode = 20005L) })
        assertEquals(UpdateErrorCode.NOT_NEWER, codeOf { verify(archiveVersionCode = 20004L) })
        assertEquals(UpdateErrorCode.NOT_NEWER, codeOf { verify(archiveVersionCode = 1L) })
    }

    @Test
    fun rejectsSigningCertificateMismatchAndFailsClosed() {
        assertEquals(UpdateErrorCode.SIGNATURE_MISMATCH, codeOf { verify(archiveCerts = listOf(certC)) })
        assertEquals(UpdateErrorCode.SIGNATURE_MISMATCH, codeOf { verify(archiveCerts = listOf(certA, certB)) })
        assertEquals(UpdateErrorCode.SIGNATURE_MISMATCH, codeOf { verify(archiveCerts = emptyList()) })
        assertEquals(UpdateErrorCode.SIGNATURE_MISMATCH, codeOf { verify(installedCerts = emptySet()) })
    }

    @Test
    fun gatesAreCheckedInOrderPackageThenVersionThenSignature() {
        assertEquals(
            UpdateErrorCode.WRONG_PACKAGE,
            codeOf { verify(archivePackage = "x.y", archiveVersionCode = 1L, archiveCerts = listOf(certC)) },
        )
        assertEquals(
            UpdateErrorCode.NOT_NEWER,
            codeOf { verify(archiveVersionCode = 1L, archiveCerts = listOf(certC)) },
        )
    }

    @Test
    fun signatureComparisonIsOrderInsensitiveAndByteExact() {
        assertTrue(UpdateManager.signaturesMatch(listOf(certA), listOf(certA.copyOf())))
        assertTrue(UpdateManager.signaturesMatch(listOf(certA, certB), listOf(certB, certA)))
        assertFalse(UpdateManager.signaturesMatch(listOf(certA), listOf(certC)))
        assertFalse(UpdateManager.signaturesMatch(listOf(certA), emptyList()))
        assertFalse(UpdateManager.signaturesMatch(emptyList(), listOf(certA)))
        assertEquals(
            setOf("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"),
            UpdateManager.certDigests(listOf(certA)),
        )
    }

    @Test
    fun autoCheckIsThrottledTo24Hours() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(day, UpdateManager.AUTO_CHECK_INTERVAL_MS)
        assertEquals("delta_update", UpdateManager.PREFS_NAME)
        val now = 10L * day
        assertTrue(UpdateManager.shouldAutoCheck(now, 0L))
        assertFalse(UpdateManager.shouldAutoCheck(now, now - 1000L))
        assertFalse(UpdateManager.shouldAutoCheck(now, now - day + 1))
        assertTrue(UpdateManager.shouldAutoCheck(now, now - day))
        // Clock moved backwards: check again rather than never.
        assertTrue(UpdateManager.shouldAutoCheck(now, now + day))
    }
}
