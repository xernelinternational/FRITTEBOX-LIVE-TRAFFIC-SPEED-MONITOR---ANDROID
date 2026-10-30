package com.fritte.speedmonitor

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** The "Extra information" screen. */
class ExtraInfoActivity : Activity() {
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var tvText: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnLoad: android.widget.Button
    private lateinit var btnPublic: android.widget.Button
    private var items: List<Item> = emptyList()
    private var showPublic = false   // the public IPv4 stays hidden until the user asks
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Extra information"
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        setContentView(col)

        col.addView(label("Extra information", 18f, Theme.FG, true))
        col.addView(label("Admin login (optional):", 13f, Theme.MUTED).apply { setPadding(0, dp(10), 0, 0) })

        etUser = field("User").apply { setText(Prefs.username(this@ExtraInfoActivity)) }
        etPass = field("Password").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(etUser, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        col.addView(etPass, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnLoad = flatButton("Load details") { start() }
        btnPublic = flatButton("Show Public Info") { togglePublic() }
        buttons.addView(btnLoad, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(btnPublic, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        col.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        col.addView(label(
            "Most details need your Admin login and the setting \"Zugriff für Anwendungen zulassen\". " +
                "The password is never saved.", 11f, Theme.MUTED
        ).apply { setPadding(0, dp(4), 0, dp(6)) })

        tvText = label("", 14f, Theme.FG).apply {
            setTextIsSelectable(true)
            setBackgroundColor(Theme.PANEL)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val scroll = ScrollView(this).apply { addView(tvText, ViewGroup.LayoutParams(-1, -2)) }
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        tvStatus = label("", 11f, Theme.MUTED).apply { setPadding(0, dp(6), 0, 0) }
        col.addView(tvStatus)

        start()
    }

    override fun onDestroy() {
        exec.shutdownNow()
        super.onDestroy()
    }

    private fun field(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Theme.MUTED)
        setTextColor(Theme.FG)
        setBackgroundColor(Theme.PANEL)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        isSingleLine = true
    }

    private fun start() {
        if (busy) return
        busy = true
        btnLoad.isEnabled = false
        tvStatus.text = "Loading …"
        val host = Prefs.host(this).trim().ifEmpty { "fritz.box" }
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()
        if (pass.isNotEmpty()) Prefs.setUsername(this, user) // only the user name is remembered
        exec.execute {
            val res = try { Router.gatherInfo(host, user, pass) } catch (e: Exception) { null to e.message }
            runOnUiThread { if (!isDestroyed) showResult(res) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun showResult(res: Pair<Any?, String?>) {
        busy = false
        btnLoad.isEnabled = true
        val list = res.first as? List<Item>
        if (list == null) {
            items = emptyList()
            render()
            tvStatus.text = "Could not load: ${res.second ?: "unknown error"}"
            return
        }
        items = list
        render()
        val missing = list.count { it.value == null }
        tvStatus.text = when (res.second) {
            "refused" -> "Items marked unavailable are not offered by this router/firmware or the login was refused."
            "unreachable" -> "The router's detail interface could not be reached - check the router address and " +
                "\"Zugriff für Anwendungen zulassen\"."
            "ok" -> if (missing > 0) "Done. Items marked unavailable are not offered by this router/firmware." else "Done."
            else -> if (missing > 0) "Some details need your Admin login - Enter it above and click on Load details. " +
                "Your password is never saved." else "Done."
        }
    }

    private fun togglePublic() {
        showPublic = !showPublic
        btnPublic.text = if (showPublic) "Hide Public Info" else "Show Public Info"
        render()
    }

    private fun render() {
        val sb = SpannableStringBuilder()
        fun add(text: String, color: Int, style: Int = Typeface.NORMAL) {
            val start = sb.length
            sb.append(text)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(style), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (item in items) {
            var value = item.value
            if (item.title == Router.PUBLIC_TITLE && value != null && !showPublic) {
                value = value.replace(Regex("^\\d{1,3}(\\.\\d{1,3}){3}"), "***.***.***.***")
            }
            when {
                value == null -> {
                    add(item.title + ": ", Theme.DOWN, Typeface.BOLD)
                    add(Router.NA + "\n\n", Theme.MUTED, Typeface.ITALIC)
                }
                value.contains("\n") -> {
                    add(item.title + "\n", Theme.DOWN, Typeface.BOLD)
                    add(value.split("\n").joinToString("\n") { if (it.isEmpty()) "" else "    $it" } + "\n\n", Theme.FG)
                }
                else -> {
                    add(item.title + ": ", Theme.DOWN, Typeface.BOLD)
                    add(value + "\n\n", Theme.FG)
                }
            }
        }
        tvText.text = sb
    }
}
