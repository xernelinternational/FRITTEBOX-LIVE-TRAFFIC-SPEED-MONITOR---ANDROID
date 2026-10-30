package com.fritte.speedmonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 500)
        }
    }

    private lateinit var etHost: EditText
    private lateinit var etRefresh: EditText
    private lateinit var tvDot: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvConn: TextView
    private lateinit var tvFooter: TextView
    private lateinit var graph: GraphView
    private lateinit var btnAwake: android.widget.Button
    private lateinit var swNotif: Switch
    private lateinit var swBoot: Switch
    private lateinit var downCard: Card
    private lateinit var upCard: Card
    private var awake = false
    private var interval = 1.0

    private class Card(val box: LinearLayout, val value: TextView, val unit: TextView, val sub: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = APP_NAME
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        interval = Prefs.refresh(this)
        buildUi()
        if (Prefs.notif(this)) startMonitorService()
    }

    override fun onStart() {
        super.onStart()
        Monitor.acquire(this)
        // the notification's "Stop" button may have switched the setting off
        swNotif.setOnCheckedChangeListener(null)
        swNotif.isChecked = Prefs.notif(this)
        swNotif.setOnCheckedChangeListener { _, on -> onNotifToggle(on) }
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        Prefs.setHost(this, etHost.text.toString().trim().ifEmpty { "fritz.box" })
        Monitor.release()
        super.onStop()
    }

    // ---------------- UI ----------------
    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG); isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        // Router row
        val r1 = row()
        r1.addView(label("Router:", 14f, Theme.MUTED))
        etHost = field(Prefs.host(this))
        etHost.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        r1.addView(etHost, weighted(1f, dp(10)))
        col.addView(r1)

        val r2 = row()
        r2.addView(flatButton("Retry") { onRetry() }, weighted(1f))
        r2.addView(flatButton("Reset stats") { Monitor.reset() }, weighted(1f, dp(8)))
        col.addView(r2)

        // Status
        val r3 = row()
        tvDot = label("●", 16f, Theme.GRAY)
        tvStatus = label("Connecting …", 14f, Theme.MUTED)
        r3.addView(tvDot)
        r3.addView(tvStatus, weighted(1f, dp(6)))
        col.addView(r3)
        tvConn = label("", 13f, Theme.MUTED)
        col.addView(tvConn)

        // Refresh speed
        val r4 = row()
        r4.addView(label("Refresh speed:", 14f, Theme.MUTED))
        etRefresh = field(fmtInterval(interval))
        etRefresh.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        etRefresh.filters = arrayOf(InputFilter.LengthFilter(3)) // max 3 characters
        r4.addView(etRefresh, LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        r4.addView(flatButton("Apply") { applyInterval() }, weighted(0f, dp(8)))
        col.addView(r4)
        col.addView(label("seconds (0.1–5)", 12f, Theme.MUTED))

        // Cards
        val cards = row()
        downCard = card("▼ DOWNLOAD", Theme.DOWN)
        upCard = card("▲ UPLOAD", Theme.UP)
        cards.addView(downCard.box, weighted(1f))
        cards.addView(upCard.box, weighted(1f, dp(8)))
        col.addView(cards, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        graph = GraphView(this)
        col.addView(graph, LinearLayout.LayoutParams(-1, dp(200)).apply { topMargin = dp(10) })

        tvFooter = label("", 12f, Theme.MUTED)
        tvFooter.gravity = Gravity.CENTER
        col.addView(tvFooter, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // Options
        btnAwake = flatButton("Enable Keep-Awake") { toggleAwake() }
        col.addView(btnAwake, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        swNotif = Switch(this).apply {
            text = "Live speed in notification"
            setTextColor(Theme.FG)
        }
        col.addView(swNotif, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        swBoot = Switch(this).apply {
            text = "Start after phone restart"
            setTextColor(Theme.FG)
            isChecked = Prefs.boot(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setBoot(this@MainActivity, on) }
        }
        col.addView(swBoot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // Bottom: More Information + copyright / version
        val more = label("More Information", 15f, Theme.UP).apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(10))
            setOnClickListener { startActivity(Intent(this@MainActivity, ExtraInfoActivity::class.java)) }
        }
        col.addView(more, LinearLayout.LayoutParams(-1, -2))

        val ver = label("© Copyright Info - Version $VERSION", 12f, Theme.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            tooltipText = "For support click on Version" // shown on long press
            setOnClickListener { showNotice() }
        }
        col.addView(ver, LinearLayout.LayoutParams(-1, -2))
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun weighted(w: Float, leftMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(if (w > 0f) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, w)
            .apply { this.leftMargin = leftMargin }

    private fun field(text: String): EditText = EditText(this).apply {
        setText(text)
        setTextColor(Theme.FG)
        setBackgroundColor(Theme.PANEL)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        isSingleLine = true
    }

    private fun card(title: String, color: Int): Card {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.PANEL)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(label(title, 13f, color, true))
        val value = label("–", 28f, Theme.FG, true)
        val unit = label("Mbit/s", 13f, Theme.MUTED)
        val sub = label("", 11f, Theme.MUTED).apply { typeface = Typeface.MONOSPACE }
        box.addView(value); box.addView(unit)
        box.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        return Card(box, value, unit, sub)
    }

    private fun fmtInterval(v: Double): String =
        if (v == Math.floor(v)) v.toInt().toString() else v.toString()

    // ---------------- actions ----------------
    private fun onRetry() {
        val h = etHost.text.toString().trim().ifEmpty { "fritz.box" }
        Prefs.setHost(this, h)
        Monitor.host = h
        Monitor.retry(false)
    }

    private fun applyInterval() {
        val v = etRefresh.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (v == null || v < Prefs.MIN_REFRESH || v > Prefs.MAX_REFRESH) {
            AlertDialog.Builder(this)
                .setTitle("Invalid value")
                .setMessage("Value must not be smaller than 0.1 seconds or higher than 5 seconds.")
                .setPositiveButton("OK", null).show()
            etRefresh.setText(fmtInterval(interval))
            return
        }
        interval = v
        etRefresh.setText(fmtInterval(v))
        Prefs.setRefresh(this, v)   // remembered for the next start
        Monitor.interval = v
        Monitor.wake()
    }

    private fun toggleAwake() {
        awake = !awake
        if (awake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnAwake.text = if (awake) "Disable Keep-Awake" else "Enable Keep-Awake"
    }

    private fun onNotifToggle(on: Boolean) {
        Prefs.setNotif(this, on)
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            startMonitorService()
        } else {
            stopService(Intent(this, MonitorService::class.java))
        }
    }

    private fun startMonitorService() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        startForegroundService(Intent(this, MonitorService::class.java))
    }

    private fun showNotice() {
        AlertDialog.Builder(this)
            .setTitle(NOTICE_TITLE)
            .setMessage(NOTICE_TEXT)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy e-mail") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Support E-Mail", SUPPORT_EMAIL))
                Toast.makeText(this, "Support E-Mail copied to clipboard.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun showDrop() {
        val host = etHost.text.toString().trim().ifEmpty { "fritz.box" }
        AlertDialog.Builder(this)
            .setTitle("Connection lost")
            .setMessage("⚠ Connection to the router was lost\n\nThe app can no longer reach $host.\n" +
                "Check that the router is on and reachable, then try again.")
            .setPositiveButton("Try to reconnect") { _, _ -> Monitor.retry(true) }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    // ---------------- refresh ----------------
    private fun refreshUi() {
        val h = etHost.text.toString().trim().ifEmpty { "fritz.box" }
        Monitor.host = h
        val s = Monitor.snapshot()

        when (s.state) {
            ConnState.OK -> {
                tvDot.setTextColor(Theme.DOWN)
                tvStatus.text = if (s.model != null) "Connected to ${s.model}" else "Connected"
                tvConn.text = Router.connLabel(s.accessType)
            }
            ConnState.ERROR -> {
                tvDot.setTextColor(Theme.RED)
                tvStatus.text = "No connection"
                tvConn.text = ""
            }
            ConnState.RECONNECTING -> {
                tvDot.setTextColor(Theme.GRAY)
                tvStatus.text = "Reconnecting …"
                tvConn.text = ""
            }
            ConnState.CONNECTING -> {
                tvDot.setTextColor(Theme.GRAY)
                tvStatus.text = "Connecting …"
                tvConn.text = ""
            }
        }

        if (s.down.isNotEmpty()) {
            showCard(downCard, s.down, s.sumDown, s.count, s.peakDown, s.interval)
            showCard(upCard, s.up, s.sumUp, s.count, s.peakUp, s.interval)
        }
        var foot = "Transferred since start:  ▼ ${fmtBytes(s.totDown)}   ▲ ${fmtBytes(s.totUp)}"
        if (s.linkDown > 0) {
            foot += "\nLine speed: ▼ ${(s.linkDown / 1e6).toInt()} Mbit/s   ▲ ${(s.linkUp / 1e6).toInt()} Mbit/s"
        }
        tvFooter.text = foot

        graph.down = s.down
        graph.up = s.up
        graph.interval = s.interval
        graph.invalidate()

        if (s.dropPending) {
            Monitor.consumeDrop()
            showDrop()
        }
    }

    private fun showCard(c: Card, series: List<Double>, sum: Double, count: Long, peak: Double, interval: Double) {
        val (v, u) = fmtRate(series.last())
        c.value.text = v
        c.unit.text = u
        val avgAll = if (count > 0) sum / count else 0.0
        val n = maxOf(1, Math.round(60 / maxOf(interval, 0.1)).toInt())
        val avg60 = series.takeLast(n).average()
        c.sub.text = "Ø start: ${fmtRateText(avgAll)}\nØ 60 s:  ${fmtRateText(avg60)}\nPeak:    ${fmtRateText(peak)}"
    }
}
