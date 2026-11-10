package com.devadam.piholeviewer

import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun sanitizeUrl_normalizesAddresses() {
        assertEquals("https://192.168.1.100", sanitizeUrl("192.168.1.100"))
        assertEquals("http://192.168.1.100", sanitizeUrl("http://192.168.1.100"))
        assertEquals("https://pi.hole", sanitizeUrl("https://pi.hole/admin/login"))
        assertEquals("https://192.168.1.2:8443", sanitizeUrl("https://192.168.1.2:8443/api/stats?foo=bar"))
    }

    @Test
    fun isUnencryptedUrl_onlyFlagsExplicitHttp() {
        assertTrue(isUnencryptedUrl("http://192.168.1.100"))
        assertTrue(isUnencryptedUrl("  HTTP://pi.hole"))
        assertFalse(isUnencryptedUrl("https://192.168.1.100"))
        assertFalse(isUnencryptedUrl(sanitizeUrl("192.168.1.100")))
    }

    @Test
    fun privacyPolicyUrl_isConfigured() {
        assertEquals("https://www.dev-adam.com/pihole-viewer/privacy/", PRIVACY_POLICY_URL)
    }

    @Test
    fun trademarkDisclaimer_isConfigured() {
        assertTrue(TRADEMARK_DISCLAIMER.contains("not affiliated with", ignoreCase = true))
        assertTrue(TRADEMARK_DISCLAIMER.startsWith("Pause for Pi-hole"))
    }

    @Test
    fun untrustedCertException_distinguishesFirstUseFromPinMismatch() {
        val firstUse = UntrustedCertException(
            instanceId = "id-1",
            instanceName = "Primary",
            fingerprintSha256 = "AA:BB:CC",
            subjectDn = "CN=pi.hole",
            issuerDn = "CN=pi.hole",
            previousPinnedSha256 = null
        )
        assertFalse(firstUse.isPinMismatch)

        val changedPin = UntrustedCertException(
            instanceId = "id-1",
            instanceName = "Primary",
            fingerprintSha256 = "11:22:33",
            subjectDn = "CN=pi.hole",
            issuerDn = "CN=pi.hole",
            previousPinnedSha256 = "AA:BB:CC"
        )
        assertTrue(changedPin.isPinMismatch)
        assertEquals("AA:BB:CC", changedPin.previousPinnedSha256)
        assertEquals("11:22:33", changedPin.fingerprintSha256)
    }
}
