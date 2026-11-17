package com.pulsehud

import android.content.Context
import android.os.SystemClock
import java.util.ArrayDeque

/** Shared click statistics. Only events actually delivered to the accessibility service are counted. */
object MonitorState {
    private val lock = Any()
    private val eventTimes = ArrayDeque<Long>()

    @Volatile var totalClicks: Long = 0
        private set
    @Volatile var peakCps: Int = 0
        private set

    fun recordClick(context: Context) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            totalClicks++
            eventTimes.addLast(now)
            trim(now, 10_000L)
            HudPrefs.prefs(context).edit()
                .putLong("total_clicks", totalClicks)
                .putInt("peak_cps", peakCps)
                .apply()
        }
    }

    fun restore(context: Context) {
        val p = HudPrefs.prefs(context)
        synchronized(lock) {
            totalClicks = p.getLong("total_clicks", 0L)
            peakCps = p.getInt("peak_cps", 0)
        }
    }

    /** Counts observed click events in the trailing window; no events are synthesized. */
    fun currentCps(windowMs: Long, context: Context? = null): Int {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val window = windowMs.coerceIn(250L, 5_000L)
            trim(now, 10_000L)
            val cutoff = now - window
            val count = eventTimes.count { it >= cutoff }
            val cps = (count * 1000.0 / window).toInt()
            if (cps > peakCps) {
                peakCps = cps
                context?.let { HudPrefs.prefs(it).edit().putInt("peak_cps", peakCps).apply() }
            }
            return cps
        }
    }

    fun persistPeak(context: Context) {
        HudPrefs.prefs(context).edit().putInt("peak_cps", peakCps).apply()
    }

    fun resetSession(context: Context) {
        synchronized(lock) {
            totalClicks = 0
            peakCps = 0
            eventTimes.clear()
        }
        HudPrefs.prefs(context).edit().putLong("total_clicks", 0L).putInt("peak_cps", 0).apply()
    }

    private fun trim(now: Long, retentionMs: Long) {
        val cutoff = now - retentionMs
        while (eventTimes.isNotEmpty() && eventTimes.peekFirst() < cutoff) eventTimes.removeFirst()
    }
}

object HudPrefs {
    fun prefs(context: Context) = context.getSharedPreferences("pulsehud", Context.MODE_PRIVATE)
    fun bool(context: Context, key: String, default: Boolean = true) = prefs(context).getBoolean(key, default)
    fun int(context: Context, key: String, default: Int) = prefs(context).getInt(key, default)
    fun str(context: Context, key: String, default: String) = prefs(context).getString(key, default) ?: default
    fun put(context: Context, key: String, value: Any) {
        val e = prefs(context).edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            is Long -> e.putLong(key, value)
            is String -> e.putString(key, value)
        }
        e.apply()
    }
}

object HudMetrics {
    val all = listOf("Current CPS", "Peak CPS", "Total clicks", "FPS", "Battery %", "Battery temperature")
    fun defaultCells(): List<String> = listOf("Current CPS", "Peak CPS", "Total clicks", "FPS", "Battery %", "Battery temperature") + List(18) { "" }
    fun cells(context: Context): List<String> {
        val raw = HudPrefs.str(context, "grid_cells", "")
        if (raw.isBlank()) return defaultCells()
        val parsed = raw.split("|").take(24)
        return parsed + List((24 - parsed.size).coerceAtLeast(0)) { "" }
    }
    fun saveCells(context: Context, cells: List<String>) =
        HudPrefs.put(context, "grid_cells", (cells + List((24 - cells.size).coerceAtLeast(0)) { "" }).take(24).joinToString("|"))
}
