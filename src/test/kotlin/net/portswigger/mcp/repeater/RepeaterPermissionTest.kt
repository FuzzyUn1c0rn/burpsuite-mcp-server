package net.portswigger.mcp.repeater

import io.mockk.*
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessApprovalHandler
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RepeaterPermissionTest {
    private val original = DataAccessSecurity.approvalHandler

    @AfterEach
    fun cleanup() {
        DataAccessSecurity.approvalHandler = original
        unmockkAll()
    }

    @Test
    fun `profile read rechecks the original allow-once decision without prompting again`() = runBlocking {
        val config = mockk<McpConfig>()
        var always = false
        every { config.requireDataAccessApproval } returns true
        every { config.alwaysAllowRepeater } answers { always }
        val approval = mockk<DataAccessApprovalHandler>()
        coEvery { approval.requestDataAccess(DataAccessType.REPEATER, config) } returns true
        DataAccessSecurity.approvalHandler = approval
        val permitted = approveM0ProfileMenuRead(config)
        assertTrue(permitted())
        assertTrue(permitted())
        always = true
        assertFalse(permitted())
        coVerify(exactly = 1) { approval.requestDataAccess(DataAccessType.REPEATER, config) }
    }

    @Test
    fun `profile read stops honoring revoked persistent approval`() = runBlocking {
        val config = mockk<McpConfig>()
        var always = true
        every { config.requireDataAccessApproval } returns true
        every { config.alwaysAllowRepeater } answers { always }
        val approval = mockk<DataAccessApprovalHandler>()
        DataAccessSecurity.approvalHandler = approval
        val permitted = approveM0ProfileMenuRead(config)
        assertTrue(permitted())
        always = false
        assertFalse(permitted())
        coVerify(exactly = 0) { approval.requestDataAccess(any(), any()) }
    }

    @Test
    fun `Repeater needs its own data approval`() = runBlocking {
        val config = mockk<McpConfig>(relaxed = true)
        every { config.requireDataAccessApproval } returns true
        every { config.alwaysAllowHttpHistory } returns true
        val approval = mockk<DataAccessApprovalHandler>()
        coEvery { approval.requestDataAccess(DataAccessType.REPEATER, config) } returns false
        DataAccessSecurity.approvalHandler = approval
        assertFalse(DataAccessSecurity.checkDataAccessPermission(DataAccessType.REPEATER, config))
        coVerify(exactly = 1) { approval.requestDataAccess(DataAccessType.REPEATER, config) }
    }

    @Test
    fun `explicit Repeater allowance avoids repeated prompts`() = runBlocking {
        val config = mockk<McpConfig>(relaxed = true)
        every { config.requireDataAccessApproval } returns true
        every { config.alwaysAllowRepeater } returns true
        val approval = mockk<DataAccessApprovalHandler>()
        DataAccessSecurity.approvalHandler = approval
        assertTrue(DataAccessSecurity.checkDataAccessPermission(DataAccessType.REPEATER, config))
        coVerify(exactly = 0) { approval.requestDataAccess(any(), any()) }
    }

    @Test
    fun `fingerprints preserve body bytes and include the service`() {
        val request = "POST / HTTP/1.1\r\nHost: example.test\r\n\r\na\r\nb"
        val digest = requestFingerprint("example.test", 443, true, request)
        assertNotEquals(digest, requestFingerprint("example.test", 443, true, request.replace("a\r\nb", "a\nb")))
        assertNotEquals(digest, requestFingerprint("example.test", 8443, true, request))
        assertNotEquals(digest, requestFingerprint("example.test", 443, false, request))
    }

    @Test
    fun `fingerprints normalize only header framing and retain ordered repeated values`() {
        val head = "POST / HTTP/1.1\r\nHost: example.test\r\nX-Value: one\r\nX-Value: two"
        val body = " café\r\n  \n"
        val request = "$head\r\n\r\n$body"
        fun fingerprint(text: String) = requestFingerprint("example.test", 8443, true, text)
        val original = fingerprint(request)

        assertEquals(
            original, fingerprint(head.replace("\r\nX-Value", "\nX-Value") + "\r\n\n" + body))
        assertNotEquals(original, fingerprint(request.replace("one\r\nX-Value: two", "two\r\nX-Value: one")))
        assertNotEquals(original, fingerprint(request.replace("café", "cafe")))
        assertNotEquals(original, fingerprint(request.trimEnd()))
    }
}
