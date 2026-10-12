package cn.liuheo.twofauth

import android.app.*
import android.content.*
import android.content.ClipboardManager
import android.graphics.*
import android.hardware.biometrics.BiometricPrompt
import android.os.*
import android.text.*
import android.view.*
import android.widget.*
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

class MainActivity : Activity() {
    private enum class Page { CONNECT, HOME, SETTINGS, FORM, SCAN, QR, LOCK }
    private lateinit var vault: TokenVault
    private lateinit var preferences: AppPreferences
    private lateinit var ui: PocketUi
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var credentials: Credentials? = null
    private var page = Page.CONNECT
    private var accounts = emptyList<Account>()
    private var groups = emptyList<Group>()
    private var draft: AccountDraft? = null
    private var filter = ""
    private var selectedGroup = -1L
    private var revealed = false
    private var active = false
    private var unlocked = false
    private var backgroundAt = 0L
    private var epoch = 0
    private var busy = false
    private var needsSync = true
    private var nextRetry = 0L
    private var failures = 0
    private var authInFlight = false
    private var credentialInFlight = false
    private var authAction: (() -> Unit)? = null
    private var entryAuth = false
    private var externalFlow = false
    private var skipResumeLock = false
    private var afterResume: (() -> Unit)? = null
    private var autoUnlock = true
    private var swipe: SwipeRefreshLayout? = null
    private var cards: LinearLayout? = null
    private var chipRow: LinearLayout? = null
    private var photoKind = "qr"
    private var form: FormFields? = null
    private var sheet: Dialog? = null
    private var detailBinding: Binding? = null
    private var sensitiveBitmap: Bitmap? = null
    private var copied: String? = null
    private var copiedUntil = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val iconExecutor = Executors.newFixedThreadPool(2)
    private val pendingIcons = mutableMapOf<String, MutableList<ServiceBadge>>()
    private val failedIcons = android.util.LruCache<String, Long>(64)
    private val bindings = mutableListOf<Binding>()
    private val iconCache = android.util.LruCache<String, Bitmap>(32)
    private val clearClipboardTask = Runnable { clearClipboard() }
    private data class Binding(val account: Account, val code: TextView, val ring: CountdownRing)
    private data class Snapshot(val accounts: List<Account>, val groups: List<Group>, val warning: String?)
    private data class FormFields(val service: EditText, val account: EditText, val secret: EditText,
        val type: Spinner, val digits: Spinner, val algorithm: Spinner, val period: EditText,
        val counter: EditText, val group: Spinner, val types: List<String>, val digitValues: List<Int>, val algorithms: List<String>)

    private val ticker = object : Runnable {
        override fun run() {
            if (!active || !unlocked || page != Page.HOME) return
            updateCodes()
            val now = SystemClock.elapsedRealtime()
            if (!busy && now >= nextRetry && (needsSync || accounts.any {
                    !it.isHotp && it.otp?.expiresAt?.let { expiry -> expiry <= now } == true
                })) loadAccounts()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = TokenVault(this)
        preferences = AppPreferences(this)
        val stored = runCatching { vault.load() }
        credentials = stored.getOrNull()
        if (credentials == null) showConnection(if (stored.isFailure) "本地连接信息无法解密，请重新连接" else "")
        else showLock()
    }

    override fun onResume() {
        super.onResume()
        active = true
        if (SystemClock.elapsedRealtime() >= copiedUntil) clearClipboard()
        if (authInFlight) return
        val secure = getSystemService(KeyguardManager::class.java).isDeviceSecure
        val expired = backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt >= preferences.lockDelay
        val lock = credentials != null && preferences.lockEnabled && secure &&
            (!unlocked || (expired && !skipResumeLock))
        skipResumeLock = false
        if (lock) {
            wipeDraft()
            afterResume = null
            showLock()
            if (autoUnlock) { autoUnlock = false; authenticate("验证身份以查看验证码", true) { showHome() } }
            return
        }
        unlocked = credentials != null
        root.visibility = View.VISIBLE
        val continuation = afterResume
        afterResume = null
        if (continuation != null) continuation()
        else if (page == Page.LOCK && credentials != null) showHome()
        else if (page == Page.HOME) startTicker()
    }

    override fun onPause() {
        super.onPause()
        active = false
        epoch++
        backgroundAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(ticker)
        accounts.forEach { it.otp = null }
        revealed = false
        updateCodes()
        sheet?.dismiss()
        root.visibility = View.INVISIBLE
        needsSync = true
        if (!authInFlight && !externalFlow) {
            autoUnlock = true
            if (preferences.lockDelay == 0L) unlocked = false
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        iconExecutor.shutdownNow()
        pendingIcons.clear()
        handler.removeCallbacksAndMessages(null)
        wipeDraft()
        sensitiveBitmap?.recycle()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Never serialize tokens, form secrets, or QR images into activity state.
        super.onSaveInstanceState(outState)
        outState.clear()
    }

    private fun navigate(destination: Page) {
        epoch++
        handler.removeCallbacks(ticker)
        sheet?.dismiss(); sheet = null
        bindings.clear(); detailBinding = null
        cards = null; chipRow = null; swipe = null
        if (page == Page.QR && destination != Page.QR) { sensitiveBitmap?.recycle(); sensitiveBitmap = null }
        page = destination
        ui = PocketUi(this, preferences.isDark())
        val sensitive = destination in listOf(Page.CONNECT, Page.FORM, Page.SCAN, Page.QR, Page.LOCK)
        if (sensitive || preferences.blockScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = ui.background
        window.navigationBarColor = if (Build.VERSION.SDK_INT < 27 && !ui.dark) Color.rgb(35, 45, 63) else ui.background
        window.decorView.systemUiVisibility = if (ui.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        root = ui.column().apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                if (ui.dark) intArrayOf(0xff101D30.toInt(), ui.background) else intArrayOf(0xffF8FAFF.toInt(), ui.background))
            setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(12))
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(ui.dp(20) + insets.systemWindowInsetLeft, ui.dp(16) + insets.systemWindowInsetTop,
                    ui.dp(20) + insets.systemWindowInsetRight, ui.dp(12) + insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(root); root.requestApplyInsets()
        form = null
    }

    private fun header(title: String, back: () -> Unit, right: View? = null) {
        val row = ui.row()
        row.addView(ui.icon("back", "返回", back), LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        row.addView(ui.text(title, 19f, medium = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(right ?: View(this), LinearLayout.LayoutParams(if (right == null) ui.dp(44) else -2, ui.dp(44)))
        ui.add(root, row)
    }

    private fun scrollBody(): LinearLayout {
        val content = ui.column()
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return content
    }

    private fun info(message: String, error: Boolean = false) {
        if (::status.isInitialized) { status.text = message; status.setTextColor(if (error) ui.danger else ui.muted) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun wipeDraft() { draft?.secret = ""; draft?.iconBytes?.fill(0); draft = null; form = null }
    private fun api() = ApiClient(credentials ?: error("未连接服务器"))

    private fun <T> job(message: String, mutation: Boolean = false, work: () -> T, success: (T) -> Unit) {
        if (busy) { toast("正在处理，请稍候"); return }
        busy = true
        val current = epoch
        info(message)
        swipe?.isRefreshing = true
        executor.execute {
            val result = runCatching(work)
            handler.post {
                busy = false
                swipe?.isRefreshing = false
                if (isDestroyed || current != epoch || !active) {
                    result.getOrNull().let { if (it is AccountDraft) it.secret = ""; if (it is ByteArray) it.fill(0) }
                    needsSync = true
                    if (active && mutation) toast("请求已结束，服务端可能已有变化，请同步确认，不要重复提交")
                    if (active && page == Page.HOME) startTicker()
                    return@post
                }
                result.onSuccess(success).onFailure { failure ->
                    if (failure is ApiException && failure.status in listOf(401, 403)) {
                        accounts.forEach { it.otp = null }; nextRetry = Long.MAX_VALUE; needsSync = false
                    }
                    info(friendlyError(failure) + if (mutation) "；操作可能已提交，请同步确认后再重试" else "", true)
                    toast(friendlyError(failure) + if (mutation) "；请先同步确认，不要重复提交" else "")
                    updateCodes()
                }
            }
        }
    }

    private fun showConnection(message: String = "") {
        navigate(Page.CONNECT)
        val body = scrollBody()
        if (credentials != null) ui.add(body, ui.button("返回", action = { showSettings() }))
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_launcher); background = ui.shape(ui.blue, 22); clipToOutline = true }
        body.addView(logo, LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(42) })
        ui.add(body, ui.text("连接到 2FAuth", 26f, medium = true).apply { gravity = Gravity.CENTER }, 24)
        ui.add(body, ui.text("账号由你的服务器统一管理\n手机只负责安全、快捷地使用验证码", 14f, ui.muted).apply { gravity = Gravity.CENTER }, 12)
        val server = ui.input(credentials?.server.orEmpty(), "例如 https://2fa.example.com")
        server.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        val token = ui.input(hint = "粘贴 Personal Access Token", password = true)
        ui.field(body, "服务器地址", server, 34)
        ui.field(body, "API Token", token)
        val tokenRow = ui.row()
        tokenRow.addView(ui.text("Token 不会离开你的连接目标", 11f, ui.muted), LinearLayout.LayoutParams(0, -2, 1f))
        tokenRow.addView(ui.icon("eye", "显示或隐藏 Token") {
            token.transformationMethod = if (token.transformationMethod == null) android.text.method.PasswordTransformationMethod.getInstance() else null
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        ui.add(body, tokenRow, 8)
        status = ui.text(message, 13f, ui.danger).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        ui.add(body, status, 8)
        fun candidate() = Credentials(Protocol.normalizeServer(server.text.toString()), Protocol.validToken(token.text.toString()))
        var tested: Credentials? = null
        ui.add(body, ui.button("测试连接") {
            runCatching { candidate() }.onSuccess { value ->
                job("正在验证 API…", work = { ApiClient(value).accounts(); value }) { tested = it; info("连接成功，可以保存并进入首页") }
            }.onFailure { info(friendlyError(it), true) }
        }, 18)
        ui.add(body, ui.button("连接并继续  →", true) {
            runCatching { candidate() }.onSuccess { value ->
                fun persist() {
                    runCatching { vault.save(value) }.onSuccess {
                        token.text.clear(); credentials = value; accounts = emptyList(); groups = emptyList()
                        filter = ""; selectedGroup = -1; unlocked = false; needsSync = true; failures = 0; nextRetry = 0
                        if (preferences.lockEnabled && getSystemService(KeyguardManager::class.java).isDeviceSecure) {
                            showLock(); authenticate("验证身份以进入 2FAuth", true) { showHome() }
                        } else { unlocked = true; showHome() }
                    }.onFailure { info("无法加密保存连接，请重试", true) }
                }
                if (tested == value) persist() else job("正在验证连接…", work = { ApiClient(value).accounts() }) { persist() }
            }.onFailure { info(friendlyError(it), true) }
        }, 16)
        ui.add(body, ui.button("如何获取 API Token？") {
            alertBuilder().setTitle("获取 Personal Access Token")
                .setMessage("打开你的 2FAuth 网页，在 Settings → OAUTH（或账户设置的 Personal Access Tokens）创建客户端专用 Token。不要填写账户密码。")
                .setPositiveButton("打开网页") { _, _ ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Protocol.normalizeServer(server.text.toString())))) }
                        .onFailure { toast("无法打开浏览器，请检查地址") }
                }.setNegativeButton("关闭", null).show()
        }, 12)
        ui.add(body, ui.text("HTTPS · Android Keystore 加密 · 无广告与统计", 11f, ui.muted).apply { gravity = Gravity.CENTER }, 24)
    }

    private fun showHome(sync: Boolean = true) {
        if (credentials == null) { showConnection(); return }
        wipeDraft()
        navigate(Page.HOME)
        val top = ui.row()
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_launcher); background = ui.shape(ui.blue, 13); clipToOutline = true }
        top.addView(logo, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        val labels = ui.column()
        ui.add(labels, ui.text("2FAuth", 23f, medium = true))
        ui.add(labels, ui.text("●  已连接 · ${android.net.Uri.parse(credentials!!.server).host}", 10f, ui.muted), 5)
        top.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(11) })
        top.addView(ui.icon("settings", "设置") { showSettings() }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        ui.add(root, top, 4)
        val searchRow = ui.row().apply { background = ui.shape(ui.field, 14); setPadding(ui.dp(12), 0, ui.dp(2), 0) }
        searchRow.addView(GlyphView(this, "search", ui.muted), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
        val search = ui.input(filter, "搜索账号或服务").apply {
            background = null
            setPadding(ui.dp(10), ui.dp(13), ui.dp(8), ui.dp(13))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun afterTextChanged(s: Editable?) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter = s?.toString().orEmpty(); renderCards() }
            })
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        searchRow.addView(ui.icon("qr", "扫码添加") { showScanMenu() }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        ui.add(root, searchRow, 16)
        chipRow = ui.row()
        ui.add(root, HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }, 12)
        val toolbar = ui.row()
        status = ui.text("点击复制 · 长按管理 · 下拉同步", 11f, ui.muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { needsSync = true; nextRetry = 0; loadAccounts() }
        }
        toolbar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        ui.add(root, toolbar, 12)
        val frame = FrameLayout(this)
        cards = ui.column().apply { setPadding(0, ui.dp(4), 0, ui.dp(86)) }
        val scrolling = ScrollView(this).apply { addView(cards); clipToPadding = false }
        swipe = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(ui.blue); setProgressBackgroundColorSchemeColor(ui.surface)
            addView(scrolling); setOnRefreshListener { needsSync = true; nextRetry = 0; loadAccounts() }
            setOnChildScrollUpCallback { _, _ -> scrolling.canScrollVertically(-1) }
        }
        frame.addView(swipe, FrameLayout.LayoutParams(-1, -1))
        val add = ui.icon("plus", "添加账号") { showScanMenu() }.apply {
            background = ui.ripple(ui.blue, 18); elevation = ui.dp(7).toFloat()
            removeAllViews(); addView(GlyphView(this@MainActivity, "plus", Color.WHITE), FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER))
        }
        frame.addView(add, FrameLayout.LayoutParams(ui.dp(58), ui.dp(58), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = ui.dp(14); rightMargin = ui.dp(3) })
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        renderChips(); renderCards()
        if (sync) { needsSync = true; startTicker() }
    }

    private fun renderChips() {
        val row = chipRow ?: return
        row.removeAllViews()
        val choices = listOf(Group(-1, "全部")) + groups + listOf(Group(0, "未分组"))
        if (choices.none { it.id == selectedGroup }) selectedGroup = -1
        choices.forEach { group ->
            val count = accounts.count { group.id == -1L || (it.groupId ?: 0) == group.id }
            val chip = ui.text("${group.name}  $count", 12f, if (selectedGroup == group.id) Color.WHITE else ui.muted, true).apply {
                gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
                background = ui.ripple(if (selectedGroup == group.id) ui.blue else ui.field, 10)
                minHeight = ui.dp(44); isFocusable = true
                contentDescription = "${group.name}，$count 个账号"
                setOnClickListener { selectedGroup = group.id; renderChips(); renderCards() }
            }
            row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.dp(8) })
        }
    }

    private fun renderCards() {
        val list = cards ?: return
        list.removeAllViews(); bindings.clear()
        val visible = accounts.filter { (selectedGroup == -1L || (it.groupId ?: 0) == selectedGroup) &&
            (filter.isBlank() || it.service.contains(filter, true) || it.account.contains(filter, true)) }
        if (visible.isEmpty()) {
            val empty = ui.column(24).apply { background = ui.shape(ui.surface, 20) }
            ui.add(empty, ui.text(if (accounts.isEmpty()) "添加你的第一个账号" else "没有匹配的账号", 20f, medium = true), 22)
            ui.add(empty, ui.text(if (accounts.isEmpty()) "扫描二维码、从相册识别，\n或手动填写账户信息。" else "试试其他名称，或切换分组。", 14f, ui.muted), 14)
            if (accounts.isEmpty()) ui.add(empty, ui.button("添加账号", true) { showScanMenu() }, 24)
            ui.add(list, empty, 14)
            return
        }
        visible.forEach { account ->
            val card = ui.row().apply {
                background = ui.ripple(ui.surface, 18); setPadding(ui.dp(14), ui.dp(12), ui.dp(12), ui.dp(12))
                minimumHeight = ui.dp(88); isFocusable = true
                setOnClickListener { useAccount(account) }
                setOnLongClickListener { showAccountSheet(account); true }
            }
            val badge = ServiceBadge(this, account.service, ui)
            card.addView(badge, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
            account.icon?.let { loadIcon(it, badge) }
            val labels = ui.column()
            ui.add(labels, ui.text(account.service.ifBlank { "未命名服务" }, 15f, medium = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            ui.add(labels, ui.text(account.account, 11f, ui.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, 4)
            val code = ui.text("— — —", 23f, ui.code, true).apply {
                letterSpacing = .045f; fontFeatureSettings = "tnum"; maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(14, 23, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                setOnClickListener { useAccount(account) }; setOnLongClickListener { showAccountSheet(account); true }
            }
            ui.add(labels, code, 4)
            card.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(12); rightMargin = ui.dp(5) })
            val ring = CountdownRing(this, ui)
            card.addView(ring, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
            ring.setOnClickListener { showAccountSheet(account) }; ring.contentDescription = "账号详情"
            bindings.add(Binding(account, code, ring))
            ui.add(list, card, 8)
        }
        updateCodes()
    }

    private fun valid(otp: Otp?) = otp != null && (otp.expiresAt == null || otp.expiresAt > SystemClock.elapsedRealtime())
    private fun format(code: String) = if (preferences.groupedCodes) Protocol.displayCode(code) else code
    private fun updateCodes() {
        val now = SystemClock.elapsedRealtime()
        (bindings + listOfNotNull(detailBinding)).forEach { binding ->
            val otp = binding.account.otp
            val usable = valid(otp) && unlocked && active
            val hidden = preferences.hideCodes && !revealed
            binding.code.text = when { !usable -> "— — —"; hidden -> "••• •••"; else -> format(otp!!.password) }
            binding.code.contentDescription = if (usable && !hidden) "验证码 ${otp!!.password}，点击复制" else "验证码已隐藏或过期"
            val remaining = ((otp?.expiresAt ?: now) - now).coerceAtLeast(0)
            binding.ring.hotp = binding.account.isHotp
            binding.ring.seconds = if (usable) ((remaining + 999) / 1000).toInt() else null
            binding.ring.fraction = if (usable && !binding.account.isHotp) remaining.toFloat() / (otp!!.period * 1000) else 0f
            binding.ring.invalidate()
        }
    }

    private fun startTicker() { handler.removeCallbacks(ticker); if (active && unlocked && page == Page.HOME) handler.post(ticker) }
    private fun loadAccounts() {
        if (busy || !active || !unlocked || page != Page.HOME) return
        val client = api()
        val oldHotp = accounts.filter { it.isHotp }.associate { it.id to it.otp }
        job("正在同步…", work = {
            val list = client.accounts()
            val grouping = runCatching { client.groups() }
            if (grouping.exceptionOrNull() is ApiException && (grouping.exceptionOrNull() as ApiException).status == 401) throw grouping.exceptionOrNull()!!
            Snapshot(list, grouping.getOrDefault(groups), if (grouping.isFailure) "账号已同步，分组加载失败" else null)
        }) { snapshot ->
            snapshot.accounts.filter { it.isHotp }.forEach { it.otp = oldHotp[it.id] }
            accounts = snapshot.accounts; groups = snapshot.groups
            needsSync = false; failures = 0; nextRetry = SystemClock.elapsedRealtime() + 2000
            sheet?.dismiss()
            info(snapshot.warning ?: "已同步 · 点击复制，长按管理", snapshot.warning != null)
            renderChips(); renderCards()
        }
        // Failed GETs retry with bounded backoff; mutations and HOTP never auto-retry.
        nextRetry = SystemClock.elapsedRealtime() + minOf(60000L, 5000L * (1L shl minOf(failures, 4)))
        failures++
    }

    private fun useAccount(account: Account) {
        if (!unlocked || !active) return
        if (preferences.hideCodes && !revealed) { revealed = true; updateCodes(); return }
        if (valid(account.otp)) copy(account.otp!!.password, account.otp?.expiresAt) else generate(account)
    }

    private fun generate(account: Account) {
        fun request() {
            val client = api()
            job("正在获取验证码…", mutation = account.isHotp, work = { client.generate(account) }) {
                account.otp = it; info("验证码已更新"); updateCodes()
            }
        }
        if (account.isHotp) alertBuilder().setTitle("生成新的 HOTP？")
            .setMessage("生成会推进服务端计数器。网络中断后也可能已消耗，不会自动重试。")
            .setPositiveButton("生成") { _, _ -> request() }.setNegativeButton("取消", null).show()
        else request()
    }

    private fun bottomSheet(title: String): LinearLayout {
        sheet?.dismiss()
        val dialog = Dialog(this)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body = ui.column(22).apply { background = ui.shape(ui.surface, 25) }
        ui.add(body, ui.text("━━", 13f, ui.muted).apply { gravity = Gravity.CENTER })
        ui.add(body, ui.text(title, 22f, medium = true), 18)
        dialog.setContentView(ScrollView(this).apply { addView(body) })
        dialog.setOnDismissListener { detailBinding = null }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(-1, minOf((resources.displayMetrics.heightPixels * .82).toInt(), ui.dp(660)))
        }
        sheet = dialog
        return body
    }

    private fun showAccountSheet(account: Account) {
        val body = bottomSheet(account.service.ifBlank { "账号详情" })
        ui.add(body, ui.text(account.account, 13f, ui.muted), 8)
        val codeRow = ui.row()
        val code = ui.text("", 36f, ui.code, true).apply {
            letterSpacing = .04f; fontFeatureSettings = "tnum"; maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(20, 36, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            setOnClickListener { useAccount(account) }
        }
        codeRow.addView(code, LinearLayout.LayoutParams(0, -2, 1f))
        val ring = CountdownRing(this, ui)
        codeRow.addView(ring, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
        detailBinding = Binding(account, code, ring)
        ui.add(body, codeRow, 24); updateCodes()
        ui.add(body, ui.text("${account.type.uppercase()} · ${account.algorithm.uppercase()} · ${account.digits} 位" +
            if (account.isHotp) " · 手动生成" else " · ${account.period} 秒", 12f, ui.muted), 16)
        ui.add(body, ui.text("分组：${groups.firstOrNull { it.id == account.groupId }?.name ?: "未分组"}", 13f, ui.muted), 10)
        ui.add(body, ui.button("复制验证码", true) { useAccount(account) }, 24)
        if (account.isHotp) ui.add(body, ui.button("生成下一个 HOTP") { generate(account) }, 10)
        ui.add(body, ui.button("查看账号二维码") { sensitiveQr(account) }, 10)
        ui.add(body, ui.button("编辑账号 / 调整分组") { editAccount(account) }, 10)
        ui.add(body, ui.button("查看密钥") { revealSecret(account) }, 10)
        ui.add(body, ui.button("删除账号") { deleteAccount(account) }, 10)
    }

    private fun editAccount(account: Account) {
        authenticate("验证身份以编辑账号和密钥") {
            val client = api(); sheet?.dismiss()
            job("正在安全获取编辑信息…", work = { client.sensitiveAccount(account.id) }) { draft = it; showEditor() }
        }
    }

    private fun revealSecret(account: Account) {
        authenticate("验证身份以查看密钥") {
            sheet?.dismiss(); val client = api()
            job("正在获取密钥…", work = { client.sensitiveAccount(account.id) }) { secretAccount ->
                val body = bottomSheet("账号密钥")
                ui.add(body, ui.text("密钥等同于账号凭据，请勿截图、转发或粘贴到不可信应用。", 13f, ui.danger), 16)
                ui.add(body, ui.text(secretAccount.secret.chunked(4).joinToString(" "), 22f, ui.code, true), 24)
                ui.add(body, ui.button("复制密钥") { copy(secretAccount.secret, sensitive = true) }, 24)
                ui.add(body, ui.button("关闭") { sheet?.dismiss() }, 12)
                sheet?.setOnDismissListener { secretAccount.secret = ""; detailBinding = null }
            }
        }
    }

    private fun sensitiveQr(account: Account) {
        authenticate("验证身份以展示含密钥的二维码") {
            sheet?.dismiss(); val client = api()
            job("正在生成二维码…", work = { client.qrCode(account.id) }) { bytes ->
                val bitmap = try { QrImages.qrBitmap(bytes) } finally { bytes.fill(0) }
                if (bitmap == null) { info("二维码图片无法解码", true); return@job }
                showQr(account, bitmap)
            }
        }
    }

    private fun showQr(account: Account, bitmap: Bitmap) {
        navigate(Page.QR); sensitiveBitmap = bitmap
        header("账号二维码", { showHome() })
        val body = scrollBody()
        ui.add(body, ui.text(account.service, 22f, medium = true).apply { gravity = Gravity.CENTER }, 28)
        ui.add(body, ui.text(account.account, 13f, ui.muted).apply { gravity = Gravity.CENTER }, 8)
        ui.add(body, ImageView(this).apply {
            setImageBitmap(bitmap); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24)); background = ui.shape(Color.WHITE, 20)
            contentDescription = "含账号密钥的二维码"
        }, 28)
        ui.add(body, ui.text("此二维码包含账户密钥，等同于账户凭据。\n仅用于迁移到你信任的验证器，敏感页面始终禁止截图。", 14f, ui.danger), 24)
        ui.add(body, ui.button("完成", true) { showHome() }, 28)
    }

    private fun deleteAccount(account: Account) {
        alertBuilder().setTitle("从服务器删除 ${account.service}？")
            .setMessage("将永久删除 ${account.account}，其他客户端也会同步移除。请确认已保存恢复码。")
            .setNegativeButton("取消", null).setPositiveButton("验证并删除") { _, _ ->
                authenticate("验证身份以删除账号") {
                    sheet?.dismiss(); val client = api()
                    job("正在删除…", true, { client.deleteAccount(account.id) }) { toast("账号已删除"); showHome() }
                }
            }.show()
    }

    private fun showScanMenu() {
        navigate(Page.SCAN)
        header("添加账号", { showHome() })
        val body = scrollBody()
        body.addView(GlyphView(this, "qr", ui.code), LinearLayout.LayoutParams(ui.dp(108), ui.dp(108)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(48) })
        ui.add(body, ui.text("扫描你的验证二维码", 24f, medium = true).apply { gravity = Gravity.CENTER }, 30)
        ui.add(body, ui.text("支持标准 otpauth:// TOTP / HOTP\n先预览信息，再确认保存到服务器", 14f, ui.muted).apply { gravity = Gravity.CENTER }, 14)
        status = ui.text("", 13f, ui.danger); ui.add(body, status, 18)
        ui.add(body, ui.button("打开相机扫描", true) {
            if (busy) { toast("正在处理，请稍候"); return@button }
            externalFlow = true
            startActivityForResult(Intent(this, ScanActivity::class.java), 201)
        }, 28)
        ui.add(body, ui.button("从相册识别二维码") { chooseImage("qr") }, 14)
        ui.add(body, ui.button("手动输入账号") { draft = AccountDraft(); showEditor() }, 14)
        ui.add(body, ui.text("图片二维码在手机本地识别，不上传整张相册图片。\n识别到的 otpauth 信息仅发给你的 2FAuth 做预览。", 12f, ui.muted), 26)
    }

    private fun previewUri(uri: String) {
        val clean = runCatching { AccountCodec.safeOtpauth(uri) }
        if (clean.isFailure) { info(friendlyError(clean.exceptionOrNull()!!), true); return }
        val client = api()
        job("正在解析二维码，尚未保存…", work = { client.preview(clean.getOrThrow()) }) { draft = it; showEditor() }
    }

    private fun spinner(values: List<String>, selected: Int): Spinner = Spinner(this).apply {
        adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).apply {
                (this as TextView).setTextColor(ui.ink); textSize = 14f; setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = super.getDropDownView(position, convertView, parent).apply {
                setBackgroundColor(ui.surface); (this as TextView).setTextColor(ui.ink)
            }
        }
        background = ui.shape(ui.field, 14); setSelection(selected.coerceAtLeast(0)); minimumHeight = ui.dp(48)
    }

    private fun showEditor() {
        val value = draft ?: return
        navigate(Page.FORM)
        header(if (value.id == null) "添加账号" else "编辑账号", {
            alertBuilder().setTitle("放弃未保存的更改？").setPositiveButton("放弃") { _, _ -> wipeDraft(); showHome() }.setNegativeButton("继续编辑", null).show()
        }, ui.button("保存", true) { saveEditor() })
        val body = scrollBody()
        val badge = ServiceBadge(this, value.service, ui)
        value.iconBytes?.let { badge.bitmap = BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (value.iconBytes == null) value.icon?.let { loadIcon(it, badge) }
        body.addView(badge, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(16) })
        body.addView(ui.button("更换图标") {
            if (!captureDraft()) return@button
            alertBuilder().setItems(arrayOf("按服务名称自动匹配", "从相册选择图标")) { _, which ->
                if (which == 0) { value.icon = null; value.iconBytes = null; showEditor() } else chooseImage("icon")
            }.show()
        }, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(8) })
        val service = ui.input(value.service, "例如 GitHub")
        val account = ui.input(value.account, "邮箱或用户名")
        val secret = ui.input(value.secret, "Base32 密钥", password = true)
        ui.field(body, "服务名称", service)
        ui.field(body, "账号名称", account)
        ui.field(body, "密钥（Secret）", secret)
        ui.add(body, ui.text("密钥仅在本页内存中使用，不保存到本机。", 11f, ui.muted), 8)
        val groupOptions = listOf("未分组") + groups.map { it.name }
        val group = spinner(groupOptions, groups.indexOfFirst { it.id == value.groupId } + 1)
        ui.field(body, "分组", group)
        val advanced = ui.column().apply { visibility = View.GONE }
        ui.add(body, ui.button("高级选项  ⌄") { advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE }, 22)
        val types = if (value.type == "steamtotp") listOf("steamtotp") else listOf("totp", "hotp")
        val digits = if (value.type == "steamtotp") listOf(5) else listOf(6, 7, 8)
        val algorithms = if (value.algorithm == "md5") listOf("sha1", "sha256", "sha512", "md5") else listOf("sha1", "sha256", "sha512")
        val type = spinner(types.map { it.uppercase() }, types.indexOf(value.type))
        val digit = spinner(digits.map { "$it 位" }, digits.indexOf(value.digits))
        val algorithm = spinner(algorithms.map { it.uppercase() }, algorithms.indexOf(value.algorithm))
        val period = ui.input(value.period.toString(), numeric = true)
        val counter = ui.input(value.counter.toString(), numeric = true)
        ui.field(advanced, "类型", type, 8); ui.field(advanced, "验证码位数", digit)
        ui.field(advanced, "算法", algorithm); ui.field(advanced, "更新周期（秒，TOTP 使用）", period)
        ui.field(advanced, "计数器（HOTP 使用）", counter)
        ui.add(body, advanced)
        status = ui.text("保存会修改你的 2FAuth 服务端账号。", 12f, ui.muted)
        ui.add(body, status, 22)
        ui.add(body, ui.button(if (value.id == null) "确认添加到 2FAuth" else "保存更改", true) { saveEditor() }, 18)
        ui.add(body, ui.text("锁定应用时会放弃尚未保存的敏感表单。", 11f, ui.muted), 14)
        form = FormFields(service, account, secret, type, digit, algorithm, period, counter, group, types, digits, algorithms)
    }

    private fun captureDraft(): Boolean {
        val fields = form ?: return false
        val value = draft ?: return false
        return runCatching {
            value.service = fields.service.text.toString(); value.account = fields.account.text.toString(); value.secret = fields.secret.text.toString()
            value.type = fields.types[fields.type.selectedItemPosition]
            value.digits = fields.digitValues[fields.digits.selectedItemPosition]
            value.algorithm = fields.algorithms[fields.algorithm.selectedItemPosition]
            value.period = fields.period.text.toString().toIntOrNull() ?: throw IllegalArgumentException("请输入有效的更新周期")
            value.counter = fields.counter.text.toString().toLongOrNull() ?: throw IllegalArgumentException("请输入有效的计数器")
            value.groupId = groups.getOrNull(fields.group.selectedItemPosition - 1)?.id
        }.onFailure { info(friendlyError(it), true) }.isSuccess
    }

    private fun saveEditor() {
        if (!captureDraft()) return
        val value = draft ?: return
        runCatching { value.validate() }.onFailure { info(friendlyError(it), true) }.onSuccess {
            val client = api()
            job("正在保存，请勿重复提交…", true, { client.save(value) }) {
                toast(if (value.id == null) "账号已添加" else "账号已更新"); wipeDraft(); showHome()
            }
        }
    }

    private fun chooseImage(kind: String) {
        if (busy) { toast("正在处理，请稍候"); return }
        if (kind == "icon" && !captureDraft()) return
        photoKind = kind; externalFlow = true
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 202)
    }

    private fun showSettings() {
        navigate(Page.SETTINGS)
        header("设置", { showHome() })
        val body = scrollBody()
        ui.section(body, "连接设置")
        ui.add(body, ui.settingsRow("服务器", android.net.Uri.parse(credentials?.server).host ?: "未连接") { showConnection() }, 12)
        ui.add(body, ui.settingsRow("API Token", "已加密保存") { showConnection() }, 6)
        ui.add(body, ui.settingsRow("测试连接", "检查 API") { val client = api(); job("正在测试…", work = { client.accounts() }) { info("连接正常"); toast("API 连接正常") } }, 6)
        ui.add(body, ui.settingsRow("同步数据", "立即同步") { showHome() }, 6)
        ui.add(body, ui.settingsRow("分组管理", "${groups.size} 个分组") { showGroupManager() }, 6)
        ui.section(body, "安全设置")
        val deviceSecure = getSystemService(KeyguardManager::class.java).isDeviceSecure
        ui.add(body, ui.toggleRow("生物识别 / 设备锁", if (deviceSecure) "优先生物识别，可使用设备凭据" else "请先设置系统锁屏密码", preferences.lockEnabled && deviceSecure) { enabled ->
            authenticate(if (enabled) "验证身份以启用应用锁" else "验证身份以关闭应用锁") { preferences.lockEnabled = enabled; showSettings() }
            if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) showSettings()
        }, 12)
        ui.add(body, ui.settingsRow("自动锁定", delayLabel()) {
            val options = arrayOf("立即", "30 秒", "1 分钟", "5 分钟")
            val values = listOf(0L, 30000L, 60000L, 300000L)
            alertBuilder().setTitle("离开应用后自动锁定").setSingleChoiceItems(options, values.indexOf(preferences.lockDelay)) { dialog, which ->
                preferences.lockDelay = values[which]; dialog.dismiss(); showSettings()
            }.setNegativeButton("取消", null).show()
        }, 6)
        ui.add(body, ui.toggleRow("默认隐藏验证码", "首页点按显示，离开应用后重新隐藏", preferences.hideCodes) { preferences.hideCodes = it; revealed = false }, 6)
        ui.add(body, ui.toggleRow("阻止首页截图", "密钥、表单、二维码页面始终受保护", preferences.blockScreenshots) { preferences.blockScreenshots = it }, 6)
        ui.section(body, "界面设置")
        ui.add(body, ui.settingsRow("主题", when (preferences.theme) { "dark" -> "深色"; "light" -> "浅色"; else -> "跟随系统" }) {
            val values = listOf("system", "light", "dark")
            alertBuilder().setTitle("主题").setSingleChoiceItems(arrayOf("跟随系统", "浅色", "深色"), values.indexOf(preferences.theme)) { dialog, which ->
                preferences.theme = values[which]; dialog.dismiss(); showSettings()
            }.setNegativeButton("取消", null).show()
        }, 12)
        ui.add(body, ui.settingsRow("语言", "简体中文") { toast("当前版本使用简体中文") }, 6)
        ui.add(body, ui.toggleRow("验证码分段显示", "复制时始终使用无空格原始验证码", preferences.groupedCodes) { preferences.groupedCodes = it }, 6)
        ui.add(body, ui.settingsRow("关于应用", "2FAuth Pocket ${BuildConfig.VERSION_NAME}") {
            alertBuilder().setTitle("2FAuth Pocket ${BuildConfig.VERSION_NAME}").setMessage("原生 Android 轻量客户端\n账号统一由 2FAuth 服务管理\n无广告、统计或离线密钥库\n\n二维码与编辑按需下载密钥，普通列表不下载密钥。\n\n" +
                if (BuildConfig.DEBUG) "当前安装包为调试构建，长期使用建议正式签名。" else "Release 构建，请妥善保管发行签名密钥。")
                .setPositiveButton("完成", null).show()
        }, 6)
        status = ui.text("", 12f, ui.muted); ui.add(body, status, 16)
        ui.add(body, ui.button("断开连接") {
            alertBuilder().setTitle("清除本机连接？").setMessage("不会删除服务器账号或撤销 Token。下次使用需重新连接。")
                .setNegativeButton("取消", null).setPositiveButton("断开") { _, _ ->
                    if (busy) { toast("请等待当前请求结束"); return@setPositiveButton }
                    runCatching { vault.clear() }.onSuccess {
                        credentials = null; accounts = emptyList(); groups = emptyList(); unlocked = false; iconCache.evictAll()
                        wipeDraft(); clearClipboard(); showConnection()
                    }.onFailure { toast("无法清除连接，请重试") }
                }.show()
        }, 22)
    }

    private fun delayLabel() = when (preferences.lockDelay) { 30000L -> "30 秒"; 60000L -> "1 分钟"; 300000L -> "5 分钟"; else -> "立即" }
    private fun showGroupManager() {
        val body = bottomSheet("分组管理")
        ui.add(body, ui.text("删除分组不会删除其中的账号。", 12f, ui.muted), 10)
        groups.forEach { group ->
            ui.add(body, ui.settingsRow(group.name, "管理") {
                alertBuilder().setTitle(group.name).setItems(arrayOf("重命名", "删除分组")) { _, which ->
                    if (which == 0) groupNameDialog(group)
                    else alertBuilder().setTitle("删除 ${group.name}？").setMessage("账号不会被删除，会变为未分组。")
                        .setPositiveButton("删除") { _, _ -> mutateGroup { it.deleteGroup(group.id) } }.setNegativeButton("取消", null).show()
                }.show()
            }, 10)
        }
        ui.add(body, ui.button("＋ 新建分组", true) { groupNameDialog(null) }, 20)
        ui.add(body, ui.button("完成") { sheet?.dismiss() }, 12)
    }

    private fun groupNameDialog(group: Group?) {
        val input = ui.input(group?.name ?: "", "分组名称")
        val dialog = alertBuilder().setTitle(if (group == null) "新建分组" else "重命名分组")
            .setView(input).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank() || name.length > 32) { input.error = "请输入 1～32 个字符"; return@setOnClickListener }
                dialog.dismiss()
                mutateGroup { if (group == null) it.createGroup(name) else it.renameGroup(group.id, name) }
            }
        }
        dialog.show()
    }

    private fun mutateGroup(action: (ApiClient) -> Unit) {
        sheet?.dismiss(); val client = api()
        job("正在更新分组…", true, { action(client); client.groups() }) { groups = it; toast("分组已更新"); showGroupManager() }
    }

    private fun alertBuilder() = AlertDialog.Builder(this, if (ui.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)

    private fun showLock() {
        navigate(Page.LOCK)
        val body = scrollBody()
        body.addView(GlyphView(this, "fingerprint", ui.code), LinearLayout.LayoutParams(ui.dp(108), ui.dp(108)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(92) })
        ui.add(body, ui.text("验证身份", 27f, medium = true).apply { gravity = Gravity.CENTER }, 36)
        ui.add(body, ui.text("使用指纹、面容或设备凭据\n保护你的验证码与账户信息", 14f, ui.muted).apply { gravity = Gravity.CENTER }, 16)
        ui.add(body, ui.button("解锁 2FAuth", true) { authenticate("验证身份以查看验证码", true) { showHome() } }, 42)
        ui.add(body, ui.text("自托管 · 由你掌控", 12f, ui.muted).apply { gravity = Gravity.CENTER }, 24)
    }

    private fun authenticate(reason: String, entry: Boolean = false, action: () -> Unit) {
        if (authInFlight || busy) { toast("请等待当前操作结束"); return }
        val manager = getSystemService(KeyguardManager::class.java)
        if (!manager.isDeviceSecure) {
            if (entry) { unlocked = true; action() }
            else alertBuilder().setTitle("需要设备身份验证").setMessage("请先在系统设置中启用锁屏 PIN 或密码，再进行密钥查看、编辑、二维码或删除等敏感操作。")
                .setPositiveButton("打开安全设置") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) }.setNegativeButton("取消", null).show()
            return
        }
        authInFlight = true; authAction = action; entryAuth = entry
        if (entry) autoUnlock = false
        if (Build.VERSION.SDK_INT >= 28) {
            val prompt = BiometricPrompt.Builder(this).setTitle("验证 2FAuth 身份").setDescription(reason)
                .setNegativeButton("使用设备凭据", mainExecutor) { _, _ -> launchDeviceCredential(reason) }.build()
            prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { completeAuth(true) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (credentialInFlight || !authInFlight) return
                    when (errorCode) {
                        13 -> Unit
                        5, 10 -> completeAuth(false)
                        else -> launchDeviceCredential(reason)
                    }
                }
            })
        } else launchDeviceCredential(reason)
    }

    private fun launchDeviceCredential(reason: String) {
        if (credentialInFlight) return
        val intent = getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent("验证 2FAuth 身份", reason)
        if (intent == null) { completeAuth(false); return }
        credentialInFlight = true
        startActivityForResult(intent, 100)
    }

    private fun completeAuth(success: Boolean) {
        val action = authAction
        val entry = entryAuth
        authAction = null; authInFlight = false; credentialInFlight = false
        skipResumeLock = true
        if (success) {
            unlocked = true; backgroundAt = 0
            if (active) { root.visibility = View.VISIBLE; action?.invoke() } else afterResume = action
        } else {
            if (entry) { unlocked = false; showLock() }
            else if (active) { root.visibility = View.VISIBLE; if (page == Page.SETTINGS) showSettings() }
            else if (page == Page.SETTINGS) afterResume = { showSettings() }
        }
    }

    @Deprecated("Platform activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) { completeAuth(resultCode == RESULT_OK); return }
        if (requestCode in listOf(201, 202)) {
            externalFlow = false; skipResumeLock = true
            if (resultCode != RESULT_OK) return
            afterResume = {
                if (requestCode == 201) data?.getStringExtra("otpauth")?.let { previewUri(it) }
                else data?.data?.let { uri ->
                    if (photoKind == "icon") {
                        job("正在处理图标…", work = { QrImages.icon(contentResolver, uri) }) { bytes -> draft?.iconBytes = bytes; showEditor() }
                    } else job("正在本地识别二维码…", work = { QrImages.decode(contentResolver, uri) }) { previewUri(it) }
                }
            }
        }
    }

    @Deprecated("Platform back compatibility")
    override fun onBackPressed() {
        when (page) {
            Page.FORM -> alertBuilder().setTitle("放弃未保存的更改？").setPositiveButton("放弃") { _, _ -> wipeDraft(); showHome() }.setNegativeButton("取消", null).show()
            Page.SETTINGS, Page.SCAN, Page.QR -> showHome()
            Page.CONNECT -> if (credentials != null) showSettings() else super.onBackPressed()
            else -> super.onBackPressed()
        }
    }

    private fun copy(value: String, expiry: Long? = null, sensitive: Boolean = false) {
        if (!active || !unlocked || (expiry != null && expiry <= SystemClock.elapsedRealtime())) { toast("请先解锁或刷新验证码"); return }
        val clip = ClipData.newPlainText("2FAuth sensitive", value)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        copied = value
        copiedUntil = SystemClock.elapsedRealtime() + minOf(if (sensitive) 15000L else 30000L, expiry?.minus(SystemClock.elapsedRealtime()) ?: 30000L).coerceAtLeast(1000L)
        handler.removeCallbacks(clearClipboardTask); handler.postDelayed(clearClipboardTask, copiedUntil - SystemClock.elapsedRealtime())
        toast(if (sensitive) "已复制密钥，请谨慎粘贴" else "已复制验证码")
    }

    private fun clearClipboard() {
        val value = copied ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        val result = runCatching {
            val clip = clipboard.primaryClip ?: return
            if (clip.description.label == "2FAuth sensitive" && clip.itemCount == 1 && clip.getItemAt(0).text?.toString() == value) {
                if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
        if (result.isSuccess) copied = null
    }

    private fun loadIcon(filename: String, view: ServiceBadge) {
        if (!filename.matches(Regex("[A-Za-z0-9_.-]{1,160}")) || filename.contains("..")) return
        val server = credentials?.server ?: return
        val key = "$server/$filename"
        iconCache.get(key)?.let { view.bitmap = it; return }
        if ((failedIcons.get(key) ?: 0L) > SystemClock.elapsedRealtime()) return
        pendingIcons[key]?.let { it.add(view); return }
        if (pendingIcons.size >= 32) return
        pendingIcons[key] = mutableListOf(view)
        iconExecutor.execute {
            val bitmap = runCatching { QrImages.serverIcon(server, filename) }.getOrNull()
            handler.post {
                val views = pendingIcons.remove(key).orEmpty()
                if (isDestroyed || credentials?.server != server) { bitmap?.recycle(); return@post }
                if (bitmap == null) { failedIcons.put(key, SystemClock.elapsedRealtime() + 60000); return@post }
                iconCache.put(key, bitmap)
                views.forEach { it.bitmap = bitmap }
            }
        }
    }

    private fun friendlyError(error: Throwable): String = when (error) {
        is ApiException -> error.message ?: "API 请求失败"
        is SSLException -> "服务器证书验证失败，不会绕过 TLS 校验"
        is SocketTimeoutException -> "连接超时，请检查网络"
        is UnknownHostException -> "无法解析服务器地址"
        is IllegalArgumentException -> error.message ?: "输入不正确"
        is org.json.JSONException -> "API 返回格式不正确，请检查服务器与版本"
        else -> "操作失败，请检查连接或所选图片"
    }
}



