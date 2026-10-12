package cn.liuheo.twofauth

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.*

class ScanActivity : Activity() {
    private lateinit var scanner: DecoratedBarcodeView
    private lateinit var feedback: TextView
    private var torch = false
    private var completed = false
    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult) {
            if (completed) return
            runCatching { AccountCodec.safeOtpauth(result.text) }.onSuccess {
                completed = true; scanner.pause()
                setResult(RESULT_OK, Intent().putExtra("otpauth", it)); finish()
            }.onFailure { feedback.text = "只支持 otpauth:// 账号二维码，请更换二维码" }
        }
        override fun possibleResultPoints(resultPoints: List<ResultPoint>) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(10, 17, 26); window.navigationBarColor = Color.rgb(10, 17, 26)
        window.decorView.systemUiVisibility = 0
        val ui = PocketUi(this, true)
        val frame = FrameLayout(this)
        scanner = DecoratedBarcodeView(this).apply {
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setStatusText(""); viewFinder.setLaserVisibility(false)
            decodeContinuous(callback)
        }
        frame.addView(scanner, FrameLayout.LayoutParams(-1, -1))
        val overlay = ui.column(22)
        val top = ui.row()
        top.addView(ui.icon("back", "返回") { finish() }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        top.addView(ui.text("扫描二维码", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(ui.icon("flash", "切换手电筒") { torch = !torch; if (torch) scanner.setTorchOn() else scanner.setTorchOff() }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        ui.add(overlay, top, 20)
        ui.add(overlay, ui.text("将 otpauth:// 二维码放入取景框", 14f, Color.WHITE).apply { gravity = Gravity.CENTER }, 24)
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val bottom = ui.column(20).apply { background = ui.shape(0xeF101A29.toInt(), 25) }
        feedback = ui.text("识别后将进入预览，不会自动保存账号。", 14f, Color.WHITE)
        ui.add(bottom, feedback)
        ui.add(bottom, ui.button("返回相册 / 手动输入") { finish() }, 18)
        frame.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = ui.dp(24); leftMargin = ui.dp(20); rightMargin = ui.dp(20) })
        setContentView(frame)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && !completed) scanner.resume()
    }
    override fun onPause() { scanner.pause(); super.onPause() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanner.resume()
            else feedback.text = "未授予相机权限，可返回使用相册或手动输入。"
        }
    }
}

