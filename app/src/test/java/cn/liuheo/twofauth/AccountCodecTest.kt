package cn.liuheo.twofauth

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountCodecTest {
    private val sample = """{"id":12,"service":"GitHub","account":"test@example.invalid","otp_type":"totp","period":30,"digits":6,"algorithm":"sha1","group_id":2,"secret":"JBSWY3DPEHPK3PXP"}"""

    @Test fun parsesMetadataWithoutRetainingSecret() {
        val account = AccountCodec.account(JSONObject(sample))
        assertEquals(12L, account.id); assertEquals(2L, account.groupId)
        assertEquals("GitHub", account.service)
        assertFalse(account.toString().contains("JBSWY3DPEHPK3PXP"))
    }
    @Test fun sensitiveDraftHasSecretOnlyWhenRequested() {
        val draft = AccountCodec.draft(JSONObject(sample), 12)
        assertEquals("JBSWY3DPEHPK3PXP", draft.secret)
        assertEquals(12L, draft.id)
    }
    @Test fun groupsExcludeVirtualAllGroup() {
        val groups = AccountCodec.groups("""[{"id":0,"name":"All"},{"id":4,"name":"工作"}]""")
        assertEquals(listOf(Group(4, "工作")), groups)
    }
    @Test fun payloadPreservesRequiredFieldsAndNullGroup() {
        val draft = AccountDraft(service = "GitHub", account = "tester", secret = "jbsw y3dp ehpk3pxp")
        val body = AccountCodec.payload(draft)
        assertEquals("JBSWY3DPEHPK3PXP", body.getString("secret"))
        assertTrue(body.isNull("group_id")); assertTrue(body.isNull("icon"))
        assertEquals(30, body.getInt("period")); assertFalse(body.has("counter"))
    }
    @Test fun hotpPayloadDoesNotSendPeriod() {
        val draft = AccountDraft(account = "tester", secret = "JBSWY3DPEHPK3PXP", type = "hotp", counter = 42)
        val body = AccountCodec.payload(draft)
        assertEquals(42L, body.getLong("counter")); assertFalse(body.has("period"))
    }
    @Test fun rejectsInvalidDrafts() {
        listOf(AccountDraft(secret = "ABC1"), AccountDraft(account = "a:b", secret = "ABCDEF"),
            AccountDraft(account = "test", secret = "ABCDEF", period = 0),
            AccountDraft(account = "test", secret = "ABCDEF", counter = -1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
    }
    @Test fun scannedUriRemovesRemoteImageDirective() {
        assertEquals("otpauth://totp/Test:alice?secret=ABCDEF&issuer=Test", AccountCodec.safeOtpauth(
            "otpauth://totp/Test:alice?secret=ABCDEF&image=https%3A%2F%2Fexample.invalid%2Fx&issuer=Test"))
    }
    @Test fun rejectsNonOtpOrAmbiguousUris() {
        listOf("https://example.invalid/qr", "otpauth://totp/a", "otpauth://totp/a?secret=ABC#fragment",
            "otpauth://user@totp/a?secret=ABC", "otpauth://totp:443/a?secret=ABC").forEach {
            assertThrows(IllegalArgumentException::class.java) { AccountCodec.safeOtpauth(it) }
        }
    }
}

