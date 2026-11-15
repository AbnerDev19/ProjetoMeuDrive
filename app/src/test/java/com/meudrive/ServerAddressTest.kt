package com.meudrive

import org.junit.Assert.*
import org.junit.Test

class ServerAddressTest {
    @Test fun preservesHttpsDefaultAndExplicitPort() {
        assertEquals("https://example.com", ServerAddress.parse("https://example.com/").origin)
        assertEquals("https://example.com:8443", ServerAddress.parse("https://example.com:8443").origin)
        assertEquals("http://192.168.1.2:8080", ServerAddress.parse("192.168.1.2").origin)
        assertEquals("http://100.64.0.10:8080", ServerAddress.parse("100.64.0.10:8080").origin)
    }
    @Test fun navigationUsesHttpStandardPortsWithoutChangingConfiguredServerPort() {
        assertTrue(ServerAddress.parse("https://EXAMPLE.com:443").accepts("https://example.com/api/download?path=x"))
        assertTrue(ServerAddress.parse("http://host:80").accepts("http://host/api/list"))
        assertFalse(ServerAddress.parse("http://host").accepts("http://host/api/list"))
        assertTrue(ServerAddress.parse("http://host").accepts("http://host:8080/api/list"))
        assertFalse(ServerAddress.parse("https://host").accepts("https://elsewhere/api/list"))
        assertFalse(ServerAddress.parse("https://host").accepts("http://host/api/list"))
    }
    @Test fun onlyRecognizesTailscaleCarrierGradeRange() {
        assertTrue(ServerAddress.parse("100.64.0.1").privateVpn)
        assertTrue(ServerAddress.parse("100.127.255.254").privateVpn)
        assertFalse(ServerAddress.parse("http://100.evil.example").privateVpn)
        assertFalse(ServerAddress.parse("100.128.0.1").privateVpn)
    }
    @Test fun refusesCredentialsPathsAndInvalidSchemes() {
        for (value in listOf("javascript:alert(1)", "ftp://example.com", "https://user:pw@example.com", "https://example.com/secret", "https://example.com?x=1", "https://example.com#frag", "http://host:0", "")) {
            try { ServerAddress.parse(value); fail(value) } catch (_: IllegalArgumentException) {}
        }
    }
}
