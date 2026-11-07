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
}
