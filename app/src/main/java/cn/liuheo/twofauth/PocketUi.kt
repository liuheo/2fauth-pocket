package cn.liuheo.twofauth

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class PocketUi(val activity: Activity, val dark: Boolean) {
    val blue = Color.rgb(38, 105, 255)
    val code = if (dark) Color.rgb(117, 174, 255) else Color.rgb(27, 96, 244)
    val ink = if (dark) Color.rgb(240, 245, 255) else Color.rgb(19, 28, 47)
    val muted = if (dark) Color.rgb(157, 174, 201) else Color.rgb(102, 120, 151)
    val surface = if (dark) Color.rgb(24, 34, 48) else Color.WHITE
    val field = if (dark) Color.rgb(31, 44, 63) else Color.rgb(232, 238, 251)
    val background = if (dark) Color.rgb(10, 17, 26) else Color.rgb(242, 245, 253)
    val line = if (dark) Color.rgb(48, 64, 88) else Color.rgb(219, 228, 250)
    val danger = if (dark) Color.rgb(255, 154, 145) else Color.rgb(179, 46, 43)
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }
    fun ripple(color: Int, radius: Int = 16): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(if (dark) 0x304C8EFF else 0x182165FC), shape(color, radius), shape(Color.WHITE, radius))
    fun column(padding: Int = 0) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    fun row() = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    fun add(parent: LinearLayout, child: View, top: Int = 0, height: Int = -2, width: Int = -1) {
        parent.addView(child, LinearLayout.LayoutParams(if (width < 0) width else dp(width), if (height < 0) height else dp(height))
            .apply { topMargin = dp(top) })
    }
    fun text(value: String, size: Float = 15f, color: Int = ink, medium: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun button(label: String, primary: Boolean = false, action: () -> Unit = {}) = Button(activity).apply {
        text = label; textSize = 15f; isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(48); minimumHeight = dp(48)
        setTextColor(if (primary) Color.WHITE else code)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        background = ripple(if (primary) blue else field, 16)
        setOnClickListener { action() }
    }
    fun icon(name: String, description: String, action: () -> Unit): FrameLayout = FrameLayout(activity).apply {
        background = ripple(field, 14)
        contentDescription = description; isFocusable = true
        addView(GlyphView(activity, name, muted), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        setOnClickListener { action() }
        minimumWidth = dp(48); minimumHeight = dp(48)
    }
    fun input(value: String = "", hint: String = "", password: Boolean = false, numeric: Boolean = false) = EditText(activity).apply {
        setText(value); this.hint = hint; textSize = 15f; setTextColor(ink); setHintTextColor(muted)
        isSingleLine = true; isSaveEnabled = false
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            numeric -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT
        }
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        setPadding(dp(16), dp(14), dp(16), dp(14)); background = shape(field, 14)
    }
    fun field(parent: LinearLayout, label: String, input: View, top: Int = 20) {
        add(parent, text(label, 13f, muted, true), top)
        add(parent, input, 8)
    }
    fun section(parent: LinearLayout, label: String) = add(parent, text(label, 13f, muted, true), 26)
    fun settingsRow(title: String, value: String, action: () -> Unit): LinearLayout = row().apply {
        setPadding(dp(16), dp(16), dp(12), dp(16)); background = ripple(surface, 14)
        addView(text(title, 15f), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(value, 12f, muted), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8); rightMargin = dp(8) })
        addView(GlyphView(activity, "chevron", muted), LinearLayout.LayoutParams(dp(16), dp(16)))
        minimumHeight = dp(54); setOnClickListener { action() }
    }
    fun toggleRow(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit): LinearLayout = row().apply {
        setPadding(dp(16), dp(14), dp(12), dp(14)); background = shape(surface, 14)
        val labels = column()
        add(labels, text(title, 15f)); add(labels, text(subtitle, 11f, muted), 6)
        addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Switch(activity).apply {
            isChecked = checked; contentDescription = title
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(blue, line))
            setOnCheckedChangeListener { _, value -> change(value) }
        })
    }
}

class CountdownRing(context: Context, private val ui: PocketUi) : View(context) {
    var seconds: Int? = null
    var fraction = 0f
    var hotp = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = ui.dp(3).toFloat()
        val inset = stroke + ui.dp(4)
        val bounds = RectF(inset, inset, width - inset, height - inset)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.color = ui.line
        canvas.drawOval(bounds, paint)
        paint.color = ui.blue; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(bounds, -90f, 360f * fraction.coerceIn(0f, 1f), false, paint)
        paint.style = Paint.Style.FILL; paint.color = ui.ink; paint.textAlign = Paint.Align.CENTER
        paint.textSize = ui.dp(13).toFloat(); paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val label = if (hotp) "H" else seconds?.toString() ?: "–"
        canvas.drawText(label, width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2, paint)
    }
}

class ServiceBadge(context: Context, var service: String, private val ui: PocketUi) : View(context) {
    private val fallback: Bitmap? by lazy {
        val resource = when (service.trim().lowercase().replace(" ", "")) {
            "aliyun", "alibabacloud", "阿里云" -> R.raw.brand_aliyun
            "openai", "chatgpt" -> R.raw.brand_openai
            else -> null
        }
        resource?.let { runCatching { context.resources.openRawResource(it).use { stream -> IconDecoder.decode(stream.readBytes()) } }.getOrNull() }
    }
    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = if (ui.dark) ui.field else Color.rgb(247, 249, 255)
        canvas.drawRoundRect(bounds, width * .3f, height * .3f, paint)
        (bitmap ?: fallback)?.let { canvas.drawBitmap(it, null, RectF(width * .12f, height * .12f, width * .88f, height * .88f), paint); return }
        val s = service.lowercase()
        val brand = when {
            s == "github" -> R.drawable.ic_brand_github
            s == "docker" -> R.drawable.ic_brand_docker
            s == "cloudflare" -> R.drawable.ic_brand_cloudflare
            else -> null
        }
        if (brand != null) {
            val drawable = context.getDrawable(brand)!!.mutate()
            if (s == "github" && ui.dark) drawable.setTint(ui.ink)
            drawable.setBounds((width * .14f).toInt(), (height * .14f).toInt(), (width * .86f).toInt(), (height * .86f).toInt())
            drawable.draw(canvas)
            return
        }
        if (s.contains("microsoft")) {
            val colors = intArrayOf(0xffF35325.toInt(), 0xff81BC06.toInt(), 0xff05A6F0.toInt(), 0xffFFBA08.toInt())
            for (i in 0..3) {
                paint.color = colors[i]
                val x = width * (.17f + (i % 2) * .35f); val y = height * (.17f + (i / 2) * .35f)
                canvas.drawRect(x, y, x + width * .31f, y + height * .31f, paint)
            }
            return
        }
        if (s == "google") {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = width * .12f
            val arc = RectF(width * .23f, height * .23f, width * .77f, height * .77f)
            intArrayOf(0xff4285F4.toInt(), 0xff34A853.toInt(), 0xffFBBC05.toInt(), 0xffEA4335.toInt()).forEachIndexed { i, c ->
                paint.color = c; canvas.drawArc(arc, i * 90f, 90f, false, paint)
            }
            paint.style = Paint.Style.FILL; paint.color = ui.surface
            canvas.drawRect(width * .53f, height * .21f, width * .92f, height * .48f, paint)
            paint.color = 0xff4285F4.toInt(); canvas.drawRect(width * .51f, height * .47f, width * .83f, height * .58f, paint)
            return
        }
        paint.color = when { s.contains("cloudflare") -> 0xffF38020.toInt(); s.contains("docker") -> 0xff129DDB.toInt(); else -> ui.code }
        paint.textAlign = Paint.Align.CENTER; paint.textSize = width * .36f
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val label = when { s.contains("github") -> "GH"; s.contains("docker") -> "DK"; s.contains("cloudflare") -> "CF"; else -> service.take(1).uppercase().ifBlank { "2F" } }
        canvas.drawText(label, width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2, paint)
    }
}

class GlyphView(context: Context, private val glyph: String, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.7f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = color
        canvas.save(); canvas.scale(width / 24f, height / 24f)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x, y, x2, y2, paint)
        when (glyph) {
            "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            "back" -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
            "chevron" -> { line(9f, 6f, 15f, 12f); line(15f, 12f, 9f, 18f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "search" -> { canvas.drawCircle(10f, 10f, 6f, paint); line(15f, 15f, 21f, 21f) }
            "copy" -> { canvas.drawRoundRect(8f, 8f, 20f, 21f, 2f, 2f, paint); line(15f, 3f, 3f, 3f); line(3f, 3f, 3f, 16f) }
            "refresh" -> { canvas.drawArc(RectF(4f, 4f, 20f, 20f), 35f, 285f, false, paint); line(19f, 3f, 19f, 9f); line(19f, 9f, 13f, 9f) }
            "eye" -> { canvas.drawOval(RectF(3f, 7f, 21f, 17f), paint); canvas.drawCircle(12f, 12f, 2.5f, paint) }
            "image" -> { canvas.drawRoundRect(3f, 3f, 21f, 21f, 3f, 3f, paint); canvas.drawCircle(8f, 8f, 1.5f, paint); line(4f, 18f, 11f, 11f); line(11f, 11f, 20f, 19f) }
            "edit" -> { line(5f, 19f, 8f, 12f); line(8f, 12f, 17f, 3f); line(17f, 3f, 21f, 7f); line(21f, 7f, 12f, 16f); line(12f, 16f, 5f, 19f); line(4f, 22f, 20f, 22f) }
            "qr" -> {
                canvas.drawRect(3f, 3f, 10f, 10f, paint); canvas.drawRect(14f, 3f, 21f, 10f, paint); canvas.drawRect(3f, 14f, 10f, 21f, paint)
                line(14f, 15f, 21f, 15f); line(14f, 15f, 14f, 21f); line(18f, 18f, 21f, 21f)
            }
            "lock" -> { canvas.drawRoundRect(5f, 10f, 19f, 21f, 2f, 2f, paint); canvas.drawArc(RectF(8f, 2f, 16f, 16f), 180f, 180f, false, paint); line(12f, 14f, 12f, 17f) }
            "fingerprint" -> { for (i in 0..3) canvas.drawArc(RectF(3f+i*2, 2f+i*2, 21f-i*2, 23f-i*2), 160f, 240f, false, paint); line(12f, 14f, 10f, 22f) }
            "flash" -> { val p = Path(); p.moveTo(14f, 2f); p.lineTo(4f, 14f); p.lineTo(11f, 14f); p.lineTo(9f, 22f); p.lineTo(21f, 9f); p.lineTo(14f, 9f); p.close(); canvas.drawPath(p, paint) }
            "check" -> { line(4f, 12f, 10f, 18f); line(10f, 18f, 20f, 6f) }
            "more" -> { paint.style = Paint.Style.FILL; for (x in listOf(5f, 12f, 19f)) canvas.drawCircle(x, 12f, 1.5f, paint); paint.style = Paint.Style.STROKE }
            else -> {
                canvas.drawCircle(12f, 12f, 4f, paint); canvas.drawCircle(12f, 12f, 9f, paint)
                for (i in 0..7) { val angle = i * Math.PI / 4; line((12+8*kotlin.math.cos(angle)).toFloat(), (12+8*kotlin.math.sin(angle)).toFloat(), (12+11*kotlin.math.cos(angle)).toFloat(), (12+11*kotlin.math.sin(angle)).toFloat()) }
            }
        }
        canvas.restore()
    }
}

