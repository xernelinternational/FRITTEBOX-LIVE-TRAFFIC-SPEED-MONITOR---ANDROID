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

    fun savePw(c: Context): Boolean = sp(c).getBoolean("save_pw", false)
    fun setSavePw(c: Context, v: Boolean) = sp(c).edit().putBoolean("save_pw", v).apply()

    fun showPw(c: Context): Boolean = sp(c).getBoolean("show_pw", false)
    fun setShowPw(c: Context, v: Boolean) = sp(c).edit().putBoolean("show_pw", v).apply()

    /** The password is stored encrypted (Android Keystore) and only if "Save Password" is ticked. */
    fun password(c: Context): String = sp(c).getString("pw_enc", null)?.let { Vault.decrypt(it) } ?: ""
    fun setPassword(c: Context, v: String?) {
        val e = sp(c).edit()
        val enc = if (v.isNullOrEmpty()) null else Vault.encrypt(v)
        if (enc == null) e.remove("pw_enc") else e.putString("pw_enc", enc)
        e.apply()
    }

    fun notif(c: Context): Boolean = sp(c).getBoolean("notif", true)
    fun setNotif(c: Context, v: Boolean) = sp(c).edit().putBoolean("notif", v).apply()

    fun boot(c: Context): Boolean = sp(c).getBoolean("boot", false)
    fun setBoot(c: Context, v: Boolean) = sp(c).edit().putBoolean("boot", v).apply()
}
