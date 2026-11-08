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
}
