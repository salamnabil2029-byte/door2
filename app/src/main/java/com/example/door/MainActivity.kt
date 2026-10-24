package com.example.door

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    // ألوان Enviropack
    private val PURPLE = Color.parseColor("#6315A8")
    private val BLUE = Color.parseColor("#004AAD")
    private val ORANGE = Color.parseColor("#E89629")
    private val INK = Color.parseColor("#1B1B3A")
    private val MUTED = Color.parseColor("#6B7280")
    private val BG = Color.parseColor("#F4F6FA")
    private val LINE = Color.parseColor("#DCE1EA")

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("door", Context.MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private var msg: TextView? = null
    private var chip: TextView? = null
    private var holdRunnable: Runnable? = null
    private var pollRunnable: Runnable? = null
    private val closeButtons = mutableListOf<View>()

    private var ip: String
        get() = prefs.getString("ip", "") ?: ""
        set(v) = prefs.edit().putString("ip", v).apply()
    private var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(root)
        setContentView(scroll)
        render()
    }

    override fun onPause() {
        super.onPause()
        stopHold(); stopPolling()
        if (token.isNotEmpty()) call("/api/stop", null) { _, _ -> }   // خروج من التطبيق = وقف الباب
    }

    override fun onResume() {
        super.onResume()
        if (chip != null && token.isNotEmpty()) startPolling()
    }

    // ---------------- الشاشات ----------------
    private fun render() {
        stopPolling(); closeButtons.clear(); chip = null
        root.removeAllViews()
        header()
        if (ip.isEmpty() || token.isEmpty()) pairScreen() else mainScreen()
        msg = text("", 16f, INK).also { root.addView(it) }
        root.addView(text("Enviropack © 2026", 12f, MUTED).apply { setPadding(0, dp(20), 0, 0) })
        if (chip != null) startPolling()
    }

    private fun header() {
        val logo = ImageView(this).apply {
            val bytes = Base64.decode(Brand.LOGO, Base64.DEFAULT)
            setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(4), 0, dp(12))
            }
        }
        root.addView(logo)
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(PURPLE, BLUE))
                .apply { cornerRadius = dp(2).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(4)).apply { setMargins(dp(50), 0, dp(50), dp(14)) }
        })
        root.addView(text("التحكم فباب الشركة", 15f, MUTED))
    }

    private fun pairScreen() {
        root.addView(section("ربط هذا الهاتف بالباب"))
        val ipBox = field("IP الحاسوب (مثلا 192.168.1.20)", ip)
        val codeBox = field("كود الاقتران (6 أرقام)", "").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val nameBox = field("سمية الهاتف", "هاتف")
        root.addView(ipBox); root.addView(codeBox); root.addView(nameBox)
        root.addView(bigButton("ربط الهاتف", PURPLE) {
            val body = JSONObject()
                .put("code", codeBox.text.toString().trim())
                .put("name", nameBox.text.toString().trim()).toString()
            ip = ipBox.text.toString().trim()
            say("كنتسناو الموافقة من الحاسوب... ⏳")
            call("/pair", body, 90000) { code, json ->
                if (code == 200 && json?.has("token") == true) {
                    token = json.getString("token"); render(); say("تم الاقتران ✅")
                } else say(json?.optString("error") ?: "ما قدرتش نتصل بالحاسوب")
            }
        })
        root.addView(text("الكود كيبان فبرنامج الحاسوب (تبويب الهواتف)", 13f, MUTED))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun mainScreen() {
        chip = TextView(this).apply {
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(12), 0, dp(4))
            }
        }
        setChip(3)
        root.addView(chip)

        root.addView(section("فتح الباب"))
        root.addView(bigButton("🚶   فتح للراجلين", PURPLE) { timed("ped") })
        root.addView(bigButton("🚗   فتح للسيارات", BLUE) { timed("car") })
        root.addView(bigButton("🔒   إغلاق الباب", ORANGE) { timed("close") }.also { closeButtons.add(it) })

        root.addView(section("تحكم يدوي (الباب كيتحرك ما دام صباعك على الزر)"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(holdButton("▲  فتح يدوي", PURPLE, "hold_open"), weighted())
        row.addView(holdButton("▼  إغلاق يدوي", ORANGE, "hold_close").also { closeButtons.add(it) }, weighted())
        root.addView(row)

        root.addView(text("⚙️  إعادة الاقتران / تغيير IP", 14f, MUTED).apply {
            setPadding(0, dp(22), 0, dp(6))
            setOnClickListener { token = ""; render() }
        })
    }

    // ---------------- الأوامر ----------------
    private fun timed(action: String) {
        call("/api/$action", null) { code, json ->
            when {
                code == 401 -> { token = ""; render(); say("خاصك تقترن من جديد") }
                code == 200 -> {
                    val running = json?.optString("status") == "RUNNING"
                    setChip(if (running) 1 else 0); say(if (running) "الباب كيتحرك..." else "تم الوقف")
                }
                else -> say(json?.optString("error") ?: "ما قدرتش نتصل بالحاسوب")
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun holdButton(label: String, color: Int, action: String): Button {
        val normal = ripple(rounded(Color.WHITE, 18, color), color)
        val active = rounded(color, 18)
        return styled(Button(this), label, color, 18f).apply {
            background = normal
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        v.background = active; (v as Button).setTextColor(Color.WHITE)
                        startHold(action)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.background = normal; (v as Button).setTextColor(color)
                        stopHold()
                        call("/api/stop", null) { _, _ -> }
                        say("تم الوقف")
                    }
                }
                true
            }
        }
    }

    // الطلب كيتعاود كل 0.3 ثانية، والسيرفر كيطفي وحدو إلا وقفو الطلبات
    private fun startHold(action: String) {
        stopHold()
        val r = object : Runnable {
            override fun run() {
                call("/api/$action", null) { code, json ->
                    if (code == 200) {
                        say(if (json?.optString("status") == "BUSY") "الباب كيتحرك، وقفو الأول" else "الباب كيتحرك...")
                    } else {
                        stopHold(); say(json?.optString("error") ?: "الاتصال تقطع")
                    }
                }
                ui.postDelayed(this, 300)
            }
        }
        holdRunnable = r
        r.run()
    }

    private fun stopHold() {
        holdRunnable?.let { ui.removeCallbacks(it) }
        holdRunnable = null
    }

    // حالة الباب والاتصال كل 2 ثواني
    private fun startPolling() {
        stopPolling()
        val r = object : Runnable {
            override fun run() {
                call("/api/status", null, 1500) { code, json ->
                    when {
                        code == 200 && json != null -> {
                            val closeOn = json.optBoolean("close", false)
                            closeButtons.forEach { it.alpha = if (closeOn) 1f else 0.4f }
                            setChip(if (json.optBoolean("moving")) 1 else 0)
                        }
                        code == 401 -> { token = ""; render(); say("خاصك تقترن من جديد") }
                        else -> setChip(2)
                    }
                }
                ui.postDelayed(this, 2000)
            }
        }
        pollRunnable = r
        r.run()
    }

    private fun stopPolling() {
        pollRunnable?.let { ui.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun setChip(kind: Int) {   // 0 واقف، 1 كيتحرك، 2 غير متصل، 3 كنتصل
        val (t, bg, fg) = when (kind) {
            0 -> Triple("🟢  متصل · الباب واقف", "#E6F4EA", "#1E7B3A")
            1 -> Triple("🟡  الباب كيتحرك...", "#FFF4E0", "#A15C00")
            2 -> Triple("🔴  غير متصل بالحاسوب", "#FDE8E8", "#B42318")
            else -> Triple("⏳  كنتصل...", "#EEF1F6", "#6B7280")
        }
        chip?.apply {
            text = t; setTextColor(Color.parseColor(fg))
            background = rounded(Color.parseColor(bg), 22)
        }
    }

    // ---------------- الشبكة ----------------
    private fun call(path: String, body: String?, timeoutMs: Int = 2000, cb: (Int, JSONObject?) -> Unit) {
        val currentIp = ip; val tok = token
        thread {
            var code = -1; var json: JSONObject? = null
            try {
                val c = URL("http://$currentIp:5000$path").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 2000; c.readTimeout = timeoutMs
                c.setRequestProperty("X-Token", tok)
                if (body != null) {
                    c.setRequestProperty("Content-Type", "application/json")
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                code = c.responseCode
                val stream = if (code < 400) c.inputStream else c.errorStream
                json = JSONObject(stream.bufferedReader().readText())
            } catch (e: Exception) { /* code = -1 */ }
            ui.post { cb(code, json) }
        }
    }

    // ---------------- أدوات الشكل ----------------
    private fun say(s: String) { msg?.text = s }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    private fun ripple(bg: GradientDrawable, color: Int) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))),
        bg, rounded(Color.WHITE, 18))

    private fun styled(b: Button, label: String, fg: Int, size: Float) = b.apply {
        text = label; textSize = size; setTextColor(fg); isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD; stateListAnimator = null; elevation = dp(2).toFloat()
    }

    private fun bigButton(label: String, color: Int, onClick: () -> Unit): Button =
        styled(Button(this), label, Color.WHITE, 19f).apply {
            background = ripple(rounded(color, 18), Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, dp(68)).apply { setMargins(0, dp(6), 0, dp(6)) }
            setOnClickListener { onClick() }
        }

    private fun text(t: String, size: Float, color: Int) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(MUTED); typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(4), dp(18), dp(4), dp(4))
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint; setText(value); setTextColor(INK); setHintTextColor(Color.parseColor("#9AA3B2"))
        textSize = 17f; gravity = Gravity.CENTER; setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(Color.WHITE, 14, LINE)
        layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(6), 0, dp(6)) }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, dp(84), 1f).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) }
}
