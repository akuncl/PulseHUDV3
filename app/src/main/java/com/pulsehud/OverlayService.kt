package com.pulsehud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.roundToInt

class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.pulsehud.START"
        const val ACTION_STOP = "com.pulsehud.STOP"
        const val ACTION_TOGGLE_EDIT = "com.pulsehud.TOGGLE_EDIT"
        const val ACTION_EDIT = "com.pulsehud.EDIT"
        const val ACTION_LIVE = "com.pulsehud.LIVE"
        private const val CHANNEL = "pulsehud_overlay"
    }

    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var editMode = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            // Rebuilding children during a drag can cancel the active pointer stream.
            if (!editMode) render()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        MonitorState.restore(this)
        createChannel()
        startForeground(41, notification())
        HudPrefs.put(this, "hud_running", true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_EDIT -> setEditMode(!editMode)
            ACTION_EDIT -> setEditMode(true)
            ACTION_LIVE -> setEditMode(false)
        }
        if (root == null) {
            createOverlay()
        } else if (intent?.action == ACTION_START) {
            val saved = HudPrefs.prefs(this)
            params?.let { lp ->
                lp.x = saved.getInt("x", lp.x)
                lp.y = saved.getInt("y", lp.y)
                try { root?.let { wm.updateViewLayout(it, lp) } } catch (_: Exception) { }
            }
        }
        render()
        return START_STICKY
    }

    private fun createOverlay() {
        val p = HudPrefs.prefs(this)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = p.getInt("x", 24)
            y = p.getInt("y", 120)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(7), dp(9), dp(7))
        }
        try {
            wm.addView(root, params)
        } catch (_: Exception) {
            root = null
            stopSelf()
            return
        }
        setEditMode(false)
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        HudPrefs.put(this, "edit_mode", enabled)
        val v = root ?: return
        val lp = params ?: return
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (enabled) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) { }
        v.setOnTouchListener(if (enabled) dragListener else null)
        v.alpha = if (enabled) .86f else 1f
        render()
    }

    private val dragListener = View.OnTouchListener { view, event ->
        val lp = params ?: return@OnTouchListener false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                view.tag = floatArrayOf(event.rawX - lp.x, event.rawY - lp.y)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val d = view.tag as? FloatArray ?: return@OnTouchListener true
                lp.x = (event.rawX - d[0]).toInt()
                lp.y = (event.rawY - d[1]).toInt()
                try { wm.updateViewLayout(view, lp) } catch (_: Exception) { }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                HudPrefs.put(this, "x", lp.x)
                HudPrefs.put(this, "y", lp.y)
                true
            }
            else -> true
        }
    }

    private fun batteryValues(): Pair<String, String> {
        val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra("level", -1) ?: -1
        val scale = b?.getIntExtra("scale", 100) ?: 100
        val pct = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "Unavailable"
        val temperature = b?.getIntExtra("temperature", -1) ?: -1
        val temp = if (temperature >= 0) "%.1f°C".format(temperature / 10f) else "Unavailable"
        return pct to temp
    }

    private fun metricValue(metric: String, battery: Pair<String, String>, compact: Boolean): String = when (metric) {
        "Current CPS" -> "CPS  ${MonitorState.currentCps(HudPrefs.int(this, "interval_ms", 1000).toLong(), this)}"
        "Peak CPS" -> "PEAK  ${MonitorState.peakCps}"
        "Total clicks" -> "TOTAL  ${MonitorState.totalClicks}"
        // Deliberately unavailable until a verified SurfaceFlinger/Shizuku backend is connected.
        "FPS" -> "FPS  N/A"
        "Battery %" -> "${if (compact) "BAT" else "BATTERY"}  ${battery.first}"
        "Battery temperature" -> "TEMP  ${battery.second}"
        else -> ""
    }

    private fun makeMetric(metric: String, battery: Pair<String, String>, compact: Boolean = false): TextView = TextView(this).apply {
        text = metricValue(metric, battery, compact)
        textSize = HudPrefs.int(this@OverlayService, "font_size", 14).toFloat().let { if (compact) it.coerceAtMost(10f) else it }
        setTextColor(try {
            Color.parseColor(HudPrefs.str(this@OverlayService, "text_color", "#F4F4F5"))
        } catch (_: Exception) { Color.WHITE })
        setPadding(dp(7), dp(4), dp(7), dp(4))
        gravity = when (HudPrefs.str(this@OverlayService, "alignment", "left")) {
            "center" -> Gravity.CENTER
            "right" -> Gravity.RIGHT
            else -> Gravity.LEFT
        }
        maxLines = 1
    }

    private fun render() {
        val box = root ?: return
        val p = HudPrefs.prefs(this)
        box.removeAllViews()
        val layout = HudPrefs.str(this, "layout", "vertical")
        val assigned = HudMetrics.cells(this)
        val battery = batteryValues()
        box.orientation = if (layout == "vertical" || layout == "custom") LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        if (layout == "custom") {
            val grid = GridLayout(this).apply {
                columnCount = 6
                rowCount = 4
            }
            val width = (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(240))
            assigned.forEachIndexed { index, metric ->
                val cell = if (metric.isBlank()) View(this).apply { visibility = View.INVISIBLE } else makeMetric(metric, battery, compact = true)
                val row = index / 6
                val column = index % 6
                val lp = GridLayout.LayoutParams(
                    GridLayout.spec(row, 1f),
                    GridLayout.spec(column, 1f)
                ).apply {
                    this.width = 0
                    height = dp(32)
                    setMargins(dp(1), dp(1), dp(1), dp(1))
                }
                grid.addView(cell, lp)
            }
            box.addView(grid, LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            assigned.filter { it.isNotBlank() }.forEach { metric ->
                box.addView(makeMetric(metric, battery), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
        }

        box.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            val opacity = p.getInt("opacity", 82).coerceIn(0, 100)
            setColor(Color.argb((opacity * 2.55f).roundToInt().coerceIn(0, 255), 20, 20, 23))
            setStroke(dp(1), Color.parseColor("#3B3B43"))
        }
        if (editMode) {
            val hint = TextView(this).apply {
                text = "MOVE HUD · drag to reposition"
                textSize = 9f
                setTextColor(Color.YELLOW)
                setPadding(dp(5), dp(4), dp(5), dp(4))
            }
            box.addView(hint, 0)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "PulseHUD overlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle("PulseHUD is running")
        .setContentText("Floating HUD active")
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        try { root?.let { wm.removeView(it) } } catch (_: Exception) { }
        root = null
        HudPrefs.put(this, "hud_running", false)
        HudPrefs.put(this, "edit_mode", false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
