package cn.liuheo.twofauth

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.assertEquals
import android.view.WindowManager
import java.io.File

// Synthetic data lives only in the test APK. No real token or API calls are used.
@Suppress("DEPRECATION")
class UiSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test
    fun testScreensAndNavigation() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        fun field(name: String, value: Any) {
            MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
        }
        fun call(name: String, vararg arguments: Any) {
            val method = MainActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == arguments.size }
            method.isAccessible = true
            method.invoke(activity, *arguments)
        }
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun serverInput(): android.widget.EditText {
            fun inputs(view: android.view.View): List<android.widget.EditText> = when (view) {
                is android.widget.EditText -> listOf(view)
                is android.view.ViewGroup -> (0 until view.childCount).flatMap { inputs(view.getChildAt(it)) }
                else -> emptyList()
            }
            return inputs(activity.window.decorView).first()
        }
        main {
            assertEquals("Fresh installation must not select a server", "", serverInput().text.toString())
            assertEquals("例如 https://2fa.example.com", serverInput().hint.toString())
        }
        fun screenshot(name: String) {
            main {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                val sheet = MainActivity::class.java.getDeclaredField("sheet").apply { isAccessible = true }.get(activity) as? android.app.Dialog
                sheet?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            val file = File(activity.getExternalFilesDir(null), "$name.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        screenshot("connection")
        val now = SystemClock.elapsedRealtime()
        val accounts = listOf("GitHub", "Aliyun", "OpenAI", "Cloudflare", "Docker").mapIndexed { index, service ->
            Account(index + 1L, service, "developer@example.com", "totp", 30,
                Otp(listOf("382941", "619284", "853026", "194552", "701327")[index], now + 20000, 30),
                if (index % 2 == 0) 1 else 2)
        }
        main {
            field("credentials", Credentials("https://2fa.example.com", "TEST-NOT-A-REAL-TOKEN"))
            call("showConnection", "")
            assertEquals("Existing connection must be retained", "https://2fa.example.com", serverInput().text.toString())
            field("unlocked", true)
            field("accounts", accounts)
            field("groups", listOf(Group(1, "工作"), Group(2, "个人")))
            field("needsSync", false)
            field("nextRetry", Long.MAX_VALUE)
            call("showHome", false)
        }
        screenshot("home-light")
        main { field("filter", "GitHub"); call("renderCards") }
        main {
            val cards = MainActivity::class.java.getDeclaredField("cards").apply { isAccessible = true }.get(activity) as android.widget.LinearLayout
            assertEquals("Search should filter accounts", 1, cards.childCount)
            field("filter", ""); field("selectedGroup", 1L); call("renderCards")
            assertEquals("Group should filter accounts", 3, cards.childCount)
            field("selectedGroup", -1L); call("renderCards")
            call("showAccountSheet", accounts.first())
        }
        screenshot("detail-light")
        main { call("showSettings") }
        screenshot("settings-light")
        main {
            field("draft", AccountDraft(service = "GitHub", account = "developer@example.com", secret = "JBSWY3DPEHPK3PX", groupId = 1))
            call("showEditor")
        }
        screenshot("editor-light")
        main { AppPreferences(activity).theme = "dark"; call("showHome", false) }
        screenshot("home-dark")
        main { call("showSettings") }
        screenshot("settings-dark")
        main { call("showLock") }
        screenshot("unlock-dark")
        main { AppPreferences(activity).theme = "system"; activity.finish() }
    }

    @Test
    fun testLocalQrRecognition() {
        val context = instrumentation.targetContext
        val uri = "otpauth://totp/GitHub:developer%40example.com?secret=JBSWY3DPEHPK3PX&issuer=GitHub"
        val matrix = com.google.zxing.MultiFormatWriter().encode(uri, com.google.zxing.BarcodeFormat.QR_CODE, 384, 384)
        val pixels = IntArray(384 * 384) { index -> if (matrix[index % 384, index / 384]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        val bitmap = Bitmap.createBitmap(pixels, 384, 384, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "qr-test.png")
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(uri, QrImages.decode(context.contentResolver, android.net.Uri.fromFile(file)))
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test
    fun testServerSvgFixtures() {
        for (name in listOf("aliyun", "github", "openai")) {
            val bytes = instrumentation.context.assets.open("$name.svg").use { it.readBytes() }
            val bitmap = IconDecoder.decode(bytes)!!
            try {
                assertEquals(128, bitmap.width)
                val pixels = IntArray(128 * 128)
                bitmap.getPixels(pixels, 0, 128, 0, 0, 128, 128)
                org.junit.Assert.assertTrue("$name SVG must render visible pixels", pixels.any { android.graphics.Color.alpha(it) > 0 })
            } finally { bitmap.recycle() }
        }
    }

    @Test
    fun testSvgEntitiesRejectedAndRasterStillSupported() {
        val unsafe = "<!DOCTYPE svg [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><svg xmlns='http://www.w3.org/2000/svg'/>"
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { IconDecoder.decode(unsafe.toByteArray()) }
        val png = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        png.eraseColor(android.graphics.Color.RED)
        val bytes = java.io.ByteArrayOutputStream().also { png.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val result = IconDecoder.decode(bytes)!!
        try { assertEquals(android.graphics.Color.RED, result.getPixel(8, 8)) }
        finally { png.recycle(); result.recycle() }
    }
}

