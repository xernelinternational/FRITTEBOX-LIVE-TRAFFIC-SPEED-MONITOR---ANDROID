package com.fritte.speedmonitor

import android.content.Context
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

enum class ConnState { CONNECTING, RECONNECTING, OK, ERROR }

data class Snapshot(
    val state: ConnState,
    val model: String?,
    val accessType: String?,
    val down: List<Double>,
    val up: List<Double>,
    val sumDown: Double,
    val sumUp: Double,
    val count: Long,
    val peakDown: Double,
    val peakUp: Double,
    val totDown: Double,
    val totUp: Double,
    val linkDown: Double,
    val linkUp: Double,
    val interval: Double,
    val dropPending: Boolean
)

/** Polls the router in the background while the app or the notification service needs it. */
object Monitor {
    const val HISTORY = 120

    private val lock = Any()
    private val wakeQueue = LinkedBlockingQueue<Int>()
    private var refs = 0
    private var running = false
    private var thread: Thread? = null

    @Volatile var host = "fritz.box"
    @Volatile var interval = 1.0

    private var state = ConnState.CONNECTING
    private var model: String? = null
    private var accessType: String? = null
    private val down = ArrayDeque<Double>()
    private val up = ArrayDeque<Double>()
    private var sumDown = 0.0
    private var sumUp = 0.0
    private var count = 0L
    private var peakDown = 0.0
    private var peakUp = 0.0
    private var totDown = 0.0
    private var totUp = 0.0
    private var linkDown = 0.0
    private var linkUp = 0.0
    private var fails = 0
    private var wasConnected = false
    private var alerted = false
    private var dropPending = false

    fun acquire(ctx: Context) {
        synchronized(lock) {
            if (refs == 0) {
                host = Prefs.host(ctx)
                interval = Prefs.refresh(ctx)
            }
            refs++
            running = true
            if (thread == null) {
                thread = Thread({ loop() }, "router-monitor").also {
                    it.isDaemon = true
                    it.start()
                }
            }
        }
    }

    fun release() {
        synchronized(lock) {
            refs = maxOf(0, refs - 1)
            if (refs == 0) {
                running = false
                wakeQueue.offer(1)
            }
        }
    }

    fun wake() {
        wakeQueue.offer(1)
    }

    fun retry(fromDialog: Boolean) {
        synchronized(lock) {
            if (fromDialog) {
                fails = 0
                alerted = false
            }
            state = ConnState.RECONNECTING
        }
        wake()
    }

    fun reset() {
        synchronized(lock) {
            down.clear(); up.clear()
            sumDown = 0.0; sumUp = 0.0; count = 0
            peakDown = 0.0; peakUp = 0.0; totDown = 0.0; totUp = 0.0
        }
    }

    fun consumeDrop() {
        synchronized(lock) { dropPending = false }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            state, model, accessType, down.toList(), up.toList(), sumDown, sumUp, count,
            peakDown, peakUp, totDown, totUp, linkDown, linkUp, interval, dropPending
        )
    }

    private fun loop() {
        var lastT = 0L
        var metaHost: String? = null
        while (true) {
            synchronized(lock) {
                if (!running) {
                    thread = null
                    return
                }
            }
            val h = host.trim().ifEmpty { "fritz.box" }
            var delayMs = (interval * 1000).toLong()
            try {
                val info = Router.wanCall(h, "GetAddonInfos")
                val now = System.nanoTime()
                val dt = if (lastT == 0L) 1.0 else (now - lastT) / 1e9
                lastT = now
                if (metaHost != h) {
                    var ld = 0.0
                    var lu = 0.0
                    var at: String? = null
                    try {
                        val lp = Router.wanCall(h, "GetCommonLinkProperties")
                        ld = Router.num(lp, "NewLayer1DownstreamMaxBitRate")
                        lu = Router.num(lp, "NewLayer1UpstreamMaxBitRate")
                        at = lp["NewWANAccessType"]
                    } catch (e: Exception) {
                        // line properties are optional
                    }
                    val m = Router.fetchModel(h)
                    synchronized(lock) {
                        linkDown = ld; linkUp = lu; accessType = at; model = m
                    }
                    metaHost = h
                }
                val d = Router.num(info, "NewByteReceiveRate")
                val u = Router.num(info, "NewByteSendRate")
                synchronized(lock) {
                    down.addLast(d); up.addLast(u)
                    if (down.size > HISTORY) { down.removeFirst(); up.removeFirst() }
                    sumDown += d; sumUp += u; count++
                    peakDown = maxOf(peakDown, d); peakUp = maxOf(peakUp, u)
                    totDown += d * dt; totUp += u * dt
                    fails = 0; wasConnected = true; alerted = false
                    state = ConnState.OK
                }
            } catch (e: Exception) {
                lastT = 0L
                metaHost = null
                delayMs = maxOf(delayMs, 1000L) // don't hammer a router that is down
                synchronized(lock) {
                    state = ConnState.ERROR
                    fails++
                    if (wasConnected && fails >= 2 && !alerted) {
                        alerted = true
                        dropPending = true
                    }
                }
            }
            try {
                wakeQueue.poll(delayMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                // ignore
            }
            wakeQueue.clear()
        }
    }
}
