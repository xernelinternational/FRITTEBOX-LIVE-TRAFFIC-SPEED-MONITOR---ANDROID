package com.fritte.speedmonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.util.Locale

object Theme {
    val BG = Color.parseColor("#14161c")
    val PANEL = Color.parseColor("#1d2029")
    val FG = Color.parseColor("#e8eaf0")
    val MUTED = Color.parseColor("#8a90a2")
    val DOWN = Color.parseColor("#2ecc71")
    val UP = Color.parseColor("#4da3ff")
    val GRID = Color.parseColor("#2a2f3d")
    val RED = Color.parseColor("#e74c3c")
    val GRAY = Color.parseColor("#9aa0a6")
}

const val APP_NAME = "FritteBox Live Traffic Speed Monitor"
const val VERSION = "1.0"
const val SUPPORT_EMAIL = "fritte_support@online.de"
const val NOTICE_TITLE = "Copyright Notice"
const val NOTICE_TEXT =
    "FRITZ! and all related product images, logos, and trademarks are the property of " +
        "FRITZ! GmbH and/or their respective rights holders. These materials are used for " +
        "identification and informational purposes only. All rights remain with their respective " +
        "owners. This software is an independent project and is not affiliated with, endorsed by, " +
        "or sponsored by FRITZ! GmbH unless explicitly stated otherwise. " +
        "For support please contact via: " + SUPPORT_EMAIL

fun fmtRate(bytesPerSec: Double): Pair<String, String> {
    val bits = bytesPerSec * 8
    return if (bits >= 1e6) String.format(Locale.getDefault(), "%.2f", bits / 1e6) to "Mbit/s"
    else String.format(Locale.getDefault(), "%.0f", bits / 1e3) to "kbit/s"
}

fun fmtRateText(bytesPerSec: Double): String {
    val (v, u) = fmtRate(bytesPerSec)
    return "$v $u"
}

fun fmtBytes(value: Double): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var n = value
    var i = 0
    while (n >= 1024 && i < units.size - 1) { n /= 1024; i++ }
    return if (i == 0) "${n.toLong()} B" else String.format(Locale.getDefault(), "%.1f %s", n, units[i])
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.label(text: String, sizeSp: Float = 14f, color: Int = Theme.FG, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.flatButton(text: String, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Theme.FG)
        setBackgroundColor(Theme.PANEL)
        setOnClickListener { onClick() }
    }

/** Live graph of the last samples (download green, upload blue). */
class GraphView(ctx: Context) : View(ctx) {
    var down: List<Double> = emptyList()
    var up: List<Double> = emptyList()
    var interval = 1.0

    private val d = ctx.resources.displayMetrics.density
    private val grid = Paint().apply { color = Theme.GRID; strokeWidth = d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED; textSize = 10f * d }
    private val lineDown = linePaint(Theme.DOWN)
    private val lineUp = linePaint(Theme.UP)

    private fun linePaint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        strokeWidth = 2f * d
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Theme.PANEL)
        val padL = 46f * d
        val padR = 10f * d
        val padT = 22f * d
        val padB = 20f * d
        val gw = width - padL - padR
        val gh = height - padT - padB
        if (gw < 20f || gh < 20f) return

        val peak = maxOf(down.maxOrNull() ?: 0.0, up.maxOrNull() ?: 0.0, 125000.0)
        val bits = peak * 8
        val step = Math.pow(10.0, (bits.toLong().toString().length - 1).toDouble())
        var topBits = step * 10
        for (m in intArrayOf(1, 2, 5, 10)) {
            if (step * m >= bits) { topBits = step * m; break }
        }
        val top = topBits / 8

        txt.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val y = padT + gh * i / 4f
            canvas.drawLine(padL, y, width - padR, y, grid)
            canvas.drawText(fmtRate(top * (1 - i / 4.0)).first, padL - 6f * d, y + txt.textSize / 3f, txt)
        }
        txt.textAlign = Paint.Align.LEFT
        canvas.drawText(fmtRate(top).second, 4f * d, 14f * d, txt)
        canvas.drawText("-" + String.format(Locale.getDefault(), "%.0f", Monitor.HISTORY * interval) + " s", padL, height - 4f * d, txt)
        txt.textAlign = Paint.Align.RIGHT
        canvas.drawText("now", width - padR, height - 4f * d, txt)

        drawSeries(canvas, down, lineDown, padL, padT, gw, gh, top)
        drawSeries(canvas, up, lineUp, padL, padT, gw, gh, top)
    }

    private fun drawSeries(c: Canvas, data: List<Double>, paint: Paint,
                           padL: Float, padT: Float, gw: Float, gh: Float, top: Double) {
        if (data.size < 2) return
        val path = Path()
        for (i in data.indices) {
            val x = padL + gw * (Monitor.HISTORY - data.size + i) / (Monitor.HISTORY - 1f)
            val y = padT + gh * (1f - minOf(data[i] / top, 1.0).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, paint)
    }
}
