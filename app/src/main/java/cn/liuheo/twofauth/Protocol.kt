package cn.liuheo.twofauth

import java.net.URI
import java.util.Locale

object Protocol {
    fun normalizeServer(raw: String): String {
        require(raw.isNotBlank()) { "请输入你自己的 2FAuth 服务器地址" }
        val uri = try { URI(raw.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("服务器地址格式不正确")
        }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) {
            "请输入完整的 HTTPS 地址"
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "地址不能包含用户名、查询参数或 # 片段"
        }
        val path = (uri.path ?: "").trimEnd('/')
        require(!path.lowercase(Locale.ROOT).endsWith("/api/v1")) {
            "请填写网站根地址，不要添加 /api/v1"
        }
        require(path.split('/').none { it == "." || it == ".." }) { "地址路径不正确" }
        return URI("https", null, uri.host.lowercase(Locale.ROOT), uri.port, path, null, null)
            .toASCIIString().trimEnd('/')
    }

    fun validToken(raw: String): String {
        val token = raw.trim()
        require(token.isNotEmpty() && token.all { it.code in 33..126 }) {
            "请输入有效 API Token，不要包含空格或换行"
        }
        return token
    }

    // Use server generation time, not the phone's wall clock. Deduct a safety second
    // and the round trip so a code is hidden before its server-side expiry.
    fun remainingMillis(generatedAt: Long, serverDate: Long, period: Int, roundTrip: Long): Long {
        require(generatedAt > 0 && period in 1..86400) { "验证码有效期不正确" }
        val reference = maxOf(generatedAt, serverDate)
        val expiry = (generatedAt / period + 1) * period
        return ((expiry - reference) * 1000 - roundTrip.coerceAtLeast(0) - 1000).coerceAtLeast(0)
    }

    fun displayCode(code: String): String = if (code.length == 6 || code.length == 8) {
        code.chunked(code.length / 2).joinToString(" ")
    } else code
}

data class Credentials(val server: String, val token: String)
data class Otp(val password: String, val expiresAt: Long?, val period: Int)
data class Account(val id: Long, val service: String, val account: String, val type: String,
                   val period: Int, var otp: Otp? = null, val groupId: Long? = null,
                   val icon: String? = null, val digits: Int = 6,
                   val algorithm: String = "sha1", val counter: Long = 0) {
    val isHotp: Boolean get() = type.equals("hotp", true)
}

data class Group(val id: Long, val name: String)

data class AccountDraft(var id: Long? = null, var service: String = "", var account: String = "",
    var secret: String = "", var type: String = "totp", var digits: Int = 6,
    var algorithm: String = "sha1", var period: Int = 30, var counter: Long = 0,
    var groupId: Long? = null, var icon: String? = null, var iconBytes: ByteArray? = null) {
    fun validate() {
        service = service.trim()
        account = account.trim()
        secret = secret.filterNot { it.isWhitespace() }.uppercase(Locale.ROOT)
        require(account.isNotBlank() && ':' !in account && ':' !in service) { "账号不能为空，名称不能包含冒号" }
        require(account.length <= 255 && service.length <= 255) { "名称最多 255 个字符" }
        require(secret.length in 1..4096 && secret.matches(Regex("[A-Z2-7]+=*"))) { "请输入有效的 Base32 密钥" }
        require(type in listOf("totp", "hotp", "steamtotp")) { "验证码类型不正确" }
        require(digits in 5..10 && (type == "steamtotp" || digits in 6..8)) { "验证码位数需为 6、7 或 8" }
        require(algorithm in listOf("sha1", "sha256", "sha512", "md5")) { "算法不正确" }
        require(period in 1..86400 && counter >= 0) { "周期或计数器不正确" }
    }
}

