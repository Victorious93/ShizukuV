package ai.victorious.bridge.core

import org.junit.Assert.*
import org.junit.Test

class RedactorTest {
    @Test fun masksKeyValueSecrets() {
        val r = Redactor.redact("password=hunter2 token: abc123 api_key=\"s3cr3t value\" ok=1")
        assertFalse(r.contains("hunter2")); assertFalse(r.contains("abc123")); assertFalse(r.contains("s3cr3t"))
        assertTrue(r.contains("ok=1"))
    }

    @Test fun masksPairingCodes() {
        val r = Redactor.redact("pairing code 123456 entered")
        assertFalse(r.contains("123456"))
        assertFalse(Redactor.redact("pair: 654321").contains("654321"))
    }

    @Test fun masksBearerAndUrlCredentialsAndLongTokens() {
        assertFalse(Redactor.redact("Authorization: Bearer abcdefghijklmnop").contains("abcdefghijklmnop"))
        assertFalse(Redactor.redact("https://user:pw123@host/x").contains("pw123"))
        assertFalse(Redactor.redact("key ${"A".repeat(40)}").contains("A".repeat(40)))
    }

    @Test fun masksPemBlocks() {
        val r = Redactor.redact("x -----BEGIN PRIVATE KEY-----\nMIIabc\n-----END PRIVATE KEY----- y")
        assertFalse(r.contains("MIIabc"))
    }

    @Test fun commandsAreReducedToExecutableAndCount() {
        assertEquals("curl (+3 args)", Redactor.summarizeCommand(listOf("/system/bin/curl", "-H", "Authorization: x", "http://a")))
        assertEquals("ls", Redactor.summarizeCommand(listOf("ls")))
        assertEquals("tool (shell command, arguments omitted)", Redactor.summarizeShell("/bin/tool --password hunter2"))
    }

    @Test fun benignTextIsUntouched() {
        assertEquals("Read a setting: global/adb_enabled", Redactor.redact("Read a setting: global/adb_enabled"))
    }
}
