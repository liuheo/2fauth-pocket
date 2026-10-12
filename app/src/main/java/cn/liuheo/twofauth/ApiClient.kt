package cn.liuheo.twofauth

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.io.ByteArrayOutputStream
import java.util.UUID

class ApiException(val status: Int, message: String) : Exception(message)

class ApiClient(private val credentials: Credentials) {
    private data class Response(val body: String, val dateSeconds: Long, val roundTrip: Long, val receivedAt: Long)

    private fun request(path: String, method: String = "GET", json: JSONObject? = null,
                        multipart: ByteArray? = null, contentType: String? = null): Response {
        val start = SystemClock.elapsedRealtime()
        val connection = URL("${credentials.server}/api/v1/$path").openConnection() as HttpsURLConnection
        try {
            // Never forward a bearer token to a redirected host (even HTTPS).
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.setRequestProperty("Authorization", "Bearer ${credentials.token}")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (json != null || multipart != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType ?: "application/json; charset=utf-8")
                val bytes = multipart ?: json!!.toString().toByteArray(Charsets.UTF_8)
                try { connection.outputStream.use { it.write(bytes) } }
                finally { if (multipart == null) bytes.fill(0) }
            }
            val status = connection.responseCode
            if (status !in listOf(200, 201, 204)) throw ApiException(status, when (status) {
                401 -> "Token 无效或已被撤销，请重新连接"
                403 -> "服务器拒绝访问，请检查 Token 权限"
                404 -> "API 不存在，请检查服务器地址和 2FAuth 版本"
                429 -> "请求过于频繁，请稍后刷新"
                422 -> "字段验证未通过，请检查密钥、名称及高级选项"
                in 300..399 -> "服务器返回重定向，请填写最终 HTTPS 地址"
                else -> "服务器暂时不可用（HTTP $status）"
            })
            val body = if (status == 204) "" else connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                val output = StringBuilder()
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    require(output.length + n <= 8_000_000) { "服务器响应过大" }
                    output.append(buffer, 0, n)
                }
                output.toString()
            }
            val receivedAt = SystemClock.elapsedRealtime()
            return Response(body, connection.getHeaderFieldDate("Date", 0) / 1000, receivedAt - start, receivedAt)
        } finally { connection.disconnect() }
    }

    private fun otp(json: JSONObject, period: Int, response: Response, hotp: Boolean): Otp {
        val password = json.getString("password")
        require(password.matches(Regex("[0-9A-Za-z]{4,16}"))) { "服务器返回的验证码格式不正确" }
        val expiry = if (hotp) null else response.receivedAt + Protocol.remainingMillis(
            json.getLong("generated_at"), response.dateSeconds, period, response.roundTrip)
        return Otp(password, expiry, period)
    }

    fun accounts(): List<Account> {
        val response = request("twofaccounts?withOtp=true&withSecret=false")
        val array = JSONArray(response.body)
        return (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            val account = AccountCodec.account(json)
            require(account.id > 0) { "服务器账户 ID 不正确" }
            // withOtp intentionally excludes HOTP. Ignore it even if a server sends one.
            if (!account.isHotp) json.optJSONObject("otp")?.let { account.otp = otp(it, account.period, response, false) }
            account
        }
    }

    fun generate(account: Account): Otp {
        val response = request("twofaccounts/${account.id}/otp")
        return otp(JSONObject(response.body), account.period, response, account.isHotp)
    }

    fun groups(): List<Group> = AccountCodec.groups(request("groups").body)

    fun preview(uri: String): AccountDraft = AccountCodec.draft(JSONObject(request("twofaccounts/preview", "POST",
        JSONObject().put("uri", AccountCodec.safeOtpauth(uri))).body))

    // Only call after a fresh device authentication, never from list synchronization.
    fun sensitiveAccount(id: Long): AccountDraft = AccountCodec.draft(JSONObject(
        request("twofaccounts/$id?withSecret=true").body), id)

    fun save(draft: AccountDraft) {
        draft.validate()
        draft.iconBytes?.let { draft.icon = uploadIcon(it); draft.iconBytes = null }
        request(if (draft.id == null) "twofaccounts" else "twofaccounts/${draft.id}",
            if (draft.id == null) "POST" else "PUT", AccountCodec.payload(draft))
    }

    fun deleteAccount(id: Long) { request("twofaccounts/$id", "DELETE") }
    fun createGroup(name: String) { request("groups", "POST", JSONObject().put("name", name)) }
    fun renameGroup(id: Long, name: String) { request("groups/$id", "PUT", JSONObject().put("name", name)) }
    fun deleteGroup(id: Long) { request("groups/$id", "DELETE") }

    fun qrCode(id: Long): ByteArray {
        val data = JSONObject(request("twofaccounts/$id/qrcode").body).getString("qrcode")
        require(data.startsWith("data:image/png;base64,") && data.length < 4_000_000) { "二维码格式不正确" }
        return android.util.Base64.decode(data.substringAfter(','), android.util.Base64.DEFAULT)
    }

    private fun uploadIcon(png: ByteArray): String {
        require(png.size <= 1_000_000) { "图标过大" }
        val boundary = "Pocket-${UUID.randomUUID()}"
        val body = ByteArrayOutputStream().apply {
            write(("--$boundary\r\nContent-Disposition: form-data; name=\"icon\"; filename=\"icon.png\"\r\n" +
                "Content-Type: image/png\r\n\r\n").toByteArray(Charsets.UTF_8))
            write(png)
            write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        }.toByteArray()
        return try { JSONObject(request("icons", "POST", multipart = body,
            contentType = "multipart/form-data; boundary=$boundary").body).getString("filename") }
        finally { body.fill(0) }
    }
}

