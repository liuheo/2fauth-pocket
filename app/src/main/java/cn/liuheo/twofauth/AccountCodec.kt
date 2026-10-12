package cn.liuheo.twofauth

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object AccountCodec {
    private fun JSONObject.nullableString(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    fun account(json: JSONObject): Account {
        val type = json.getString("otp_type")
        require(type in listOf("totp", "hotp", "steamtotp")) { "不支持的验证码类型" }
        return Account(json.getLong("id"), json.nullableString("service").orEmpty(),
            json.nullableString("account").orEmpty(), type, if (type == "hotp") 30 else json.optInt("period", 30),
            groupId = if (json.isNull("group_id")) null else json.optLong("group_id").takeIf { it > 0 },
            icon = json.nullableString("icon"), digits = json.optInt("digits", 6),
            algorithm = json.optString("algorithm", "sha1"), counter = json.optLong("counter", 0))
    }

    fun draft(json: JSONObject, id: Long? = null): AccountDraft = AccountDraft(id,
        json.nullableString("service").orEmpty(), json.nullableString("account").orEmpty(),
        json.getString("secret"), json.optString("otp_type", "totp"), json.optInt("digits", 6),
        json.optString("algorithm", "sha1"), json.optInt("period", 30), json.optLong("counter", 0),
        if (json.isNull("group_id")) null else json.optLong("group_id").takeIf { it > 0 }, json.nullableString("icon"))

    fun payload(draft: AccountDraft): JSONObject {
        draft.validate()
        val json = JSONObject().put("service", draft.service).put("account", draft.account)
            .put("secret", draft.secret).put("otp_type", draft.type).put("digits", draft.digits)
            .put("algorithm", draft.algorithm).put("icon", draft.icon ?: JSONObject.NULL)
            .put("group_id", draft.groupId ?: JSONObject.NULL)
        if (draft.type == "hotp") json.put("counter", draft.counter) else json.put("period", draft.period)
        return json
    }

    fun groups(body: String): List<Group> {
        val array = JSONArray(body)
        return (0 until array.length()).map { array.getJSONObject(it) }
            .filter { it.getLong("id") > 0 }.map { Group(it.getLong("id"), it.getString("name")) }
    }

    fun safeOtpauth(raw: String): String {
        require(raw.length <= 16384) { "二维码内容过长" }
        val uri = try { URI(raw.trim()) } catch (_: Exception) { throw IllegalArgumentException("二维码格式不正确") }
        require(uri.scheme == "otpauth" && uri.host in listOf("totp", "hotp") && !uri.rawQuery.isNullOrBlank() &&
            uri.userInfo == null && uri.port == -1 && uri.fragment == null && !uri.rawPath.isNullOrBlank()) {
            "只支持标准 otpauth:// TOTP / HOTP 二维码"
        }
        // Do not let a scanned image URL instruct the server to fetch a remote resource.
        val query = uri.rawQuery.split('&').filterNot {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8").equals("image", true)
        }.joinToString("&")
        return "otpauth://${uri.host}${uri.rawPath}?$query"
    }
}

