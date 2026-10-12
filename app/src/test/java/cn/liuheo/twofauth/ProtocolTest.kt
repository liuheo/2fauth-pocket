package cn.liuheo.twofauth

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun normalizesHttpsAddress() {
        assertEquals("https://2fa.example.com", Protocol.normalizeServer(" https://2fa.example.com/ "))
        assertEquals("https://example.com:8443/auth", Protocol.normalizeServer("https://EXAMPLE.com:8443/auth/"))
    }
    @Test fun rejectsUnsafeAddresses() {
        listOf("http://example.com", "https://user:pass@example.com", "https://example.com?token=x",
            "https://example.com/#x", "https://example.com/api/v1", "https://example.com/a/../b").forEach {
            assertThrows(IllegalArgumentException::class.java) { Protocol.normalizeServer(it) }
        }
    }
    @Test fun requiresExplicitServerAddress() {
        for (address in listOf("", " ", "\n\t")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { Protocol.normalizeServer(address) }
            assertEquals("请输入你自己的 2FAuth 服务器地址", failure.message)
        }
    }
    @Test fun tokenRejectsHeaderInjection() {
        assertEquals("abc.def", Protocol.validToken(" abc.def "))
        assertThrows(IllegalArgumentException::class.java) { Protocol.validToken("abc\r\nInjected: x") }
        assertThrows(IllegalArgumentException::class.java) { Protocol.validToken("") }
    }
    @Test fun countdownUsesServerGenerationAndLatency() {
        assertEquals(28000L, Protocol.remainingMillis(1701788671, 1701788671, 30, 0))
        assertEquals(25500L, Protocol.remainingMillis(1701788671, 1701788673, 30, 500))
        assertEquals(0L, Protocol.remainingMillis(1701788671, 1701788701, 30, 100))
        assertThrows(IllegalArgumentException::class.java) { Protocol.remainingMillis(1, 1, 0, 0) }
    }
    @Test fun preservesLeadingZerosAndSteamCodes() {
        assertEquals("045 698", Protocol.displayCode("045698"))
        assertEquals("1234 5678", Protocol.displayCode("12345678"))
        assertEquals("ABCDE", Protocol.displayCode("ABCDE"))
    }
    @Test fun missingDateAndCustomPeriodsAreSafe() {
        assertEquals(28000L, Protocol.remainingMillis(1701788671, 0, 30, 0))
        assertEquals(0L, Protocol.remainingMillis(1701788671, 0, 30, 30000))
        assertEquals(56000L, Protocol.remainingMillis(121, 122, 60, 1000))
        assertThrows(IllegalArgumentException::class.java) { Protocol.remainingMillis(0, 0, 30, 0) }
    }
}

