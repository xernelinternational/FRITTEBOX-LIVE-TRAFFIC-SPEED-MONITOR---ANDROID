package com.fritte.speedmonitor

import android.content.Context

/** Small settings store. The password is never stored. */
object Prefs {
    const val MIN_REFRESH = 0.1
    const val MAX_REFRESH = 5.0

    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun host(c: Context): String = sp(c).getString("host", "fritz.box") ?: "fritz.box"
    fun setHost(c: Context, v: String) = sp(c).edit().putString("host", v).apply()

    fun refresh(c: Context): Double {
        val v = sp(c).getFloat("refresh", 1f).toDouble()
        return if (v in MIN_REFRESH..MAX_REFRESH) v else 1.0
    }
    fun setRefresh(c: Context, v: Double) = sp(c).edit().putFloat("refresh", v.toFloat()).apply()

    fun username(c: Context): String = sp(c).getString("username", "") ?: ""
    fun setUsername(c: Context, v: String) = sp(c).edit().putString("username", v).apply()

    fun notif(c: Context): Boolean = sp(c).getBoolean("notif", true)
    fun setNotif(c: Context, v: Boolean) = sp(c).edit().putBoolean("notif", v).apply()

    fun boot(c: Context): Boolean = sp(c).getBoolean("boot", false)
    fun setBoot(c: Context, v: Boolean) = sp(c).edit().putBoolean("boot", v).apply()
}
