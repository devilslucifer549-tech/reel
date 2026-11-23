package com.example.handscroll

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("hand_scroll", Context.MODE_PRIVATE)

    /** 1 (hard to trigger) .. 10 (very sensitive) */
    var sensitivity: Int
        get() = sp.getInt("sensitivity", 6)
        set(v) = sp.edit().putInt("sensitivity", v).apply()

    /** Hand travel (fraction of frame height) needed for a swipe. */
    val threshold: Float get() = 0.40f - 0.035f * sensitivity

    /** Delay between actions, in units of 100 ms (5..20). */
    var delayUnits: Int
        get() = sp.getInt("delay", 10)
        set(v) = sp.edit().putInt("delay", v).apply()

    val cooldownMs: Long get() = delayUnits * 100L

    var requireOpenPalm: Boolean
        get() = sp.getBoolean("palm", true)
        set(v) = sp.edit().putBoolean("palm", v).apply()

    var fistPlayPause: Boolean
        get() = sp.getBoolean("fist", true)
        set(v) = sp.edit().putBoolean("fist", v).apply()

    var thumbLike: Boolean
        get() = sp.getBoolean("thumb", true)
        set(v) = sp.edit().putBoolean("thumb", v).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    var allApps: Boolean
        get() = sp.getBoolean("allApps", false)
        set(v) = sp.edit().putBoolean("allApps", v).apply()

    /** Auto-stop timer in units of 5 minutes (0 = off). */
    var sleepUnits: Int
        get() = sp.getInt("sleep", 0)
        set(v) = sp.edit().putInt("sleep", v).apply()

    val sleepMs: Long get() = sleepUnits * 5L * 60_000L

    private fun today(): String = java.time.LocalDate.now().toString()

    fun countToday(): Int = if (sp.getString("day", "") == today()) sp.getInt("count", 0) else 0

    fun bumpCount() {
        val c = countToday()
        sp.edit().putString("day", today()).putInt("count", c + 1).apply()
    }
}
