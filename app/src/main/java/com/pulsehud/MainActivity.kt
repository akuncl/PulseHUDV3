package com.pulsehud

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val Bg = Color(0xFF101012)
private val CardBg = Color(0xFF1A1A1E)
private val Raised = Color(0xFF25252B)
private val Accent = Color(0xFFFF8A45)
private val Muted = Color(0xFF9A9AA3)
private val White = Color(0xFFF4F4F5)
private val presets = listOf("Minimal", "Gaming", "Neon", "Glass", "Terminal")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MonitorState.restore(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = CardBg, onSurface = White)) { Dashboard() } }
    }
}

@Composable
private fun Dashboard() {
    val context = LocalContext.current
    val prefs = remember { HudPrefs.prefs(context) }
    var layout by remember { mutableStateOf(HudPrefs.str(context, "layout", "vertical")) }
    var cells by remember { mutableStateOf(HudMetrics.cells(context)) }
    var selectedCell by remember { mutableIntStateOf(0) }
    var chooseMetric by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf(HudPrefs.str(context, "preset_base", "Gaming")) }
    var customPreviewJson by remember { mutableStateOf<String?>(null) }
    var movingHud by remember { mutableStateOf(HudPrefs.bool(context, "edit_mode", false)) }
    var hudRunning by remember { mutableStateOf(HudPrefs.bool(context, "hud_running", false)) }
    var customName by remember { mutableStateOf("") }
    var customPresets by remember { mutableStateOf(prefs.getString("custom_presets_json", "") ?: "") }
    var colorHex by remember { mutableStateOf(HudPrefs.str(context, "text_color", "#F4F4F5")) }
    var fontSize by remember { mutableFloatStateOf(HudPrefs.int(context, "font_size", 14).toFloat()) }
    var opacity by remember { mutableFloatStateOf(HudPrefs.int(context, "opacity", 82).toFloat()) }
    var alignment by remember { mutableStateOf(HudPrefs.str(context, "alignment", "left")) }
    var interval by remember { mutableFloatStateOf(HudPrefs.int(context, "interval_ms", 1000).toFloat()) }
    var cps by remember { mutableIntStateOf(0) }
    var total by remember { mutableLongStateOf(MonitorState.totalClicks) }
    var peak by remember { mutableIntStateOf(MonitorState.peakCps) }
    var batteryPct by remember { mutableStateOf("—") }
    var batteryTemp by remember { mutableStateOf("—") }
    var overlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var accessibilityEnabled by remember { mutableStateOf(false) }

    fun save(k: String, v: Any) = HudPrefs.put(context, k, v)
    fun applyPreset(name: String) {
        selectedPreset = name
        val base = when (name) {
            "Minimal" -> Triple("#F4F4F5", 12, 65)
            "Neon" -> Triple("#FF8A45", 16, 88)
            "Glass" -> Triple("#DCE7F5", 14, 42)
            "Terminal" -> Triple("#8DFF9A", 13, 94)
            else -> Triple("#F4F4F5", 15, 82)
        }
        colorHex = base.first; fontSize = base.second.toFloat(); opacity = base.third.toFloat()
        layout = if (name == "Neon" || name == "Terminal") "vertical" else "horizontal"
        val presetCells = when (name) {
            "Minimal" -> listOf("Current CPS", "FPS", "", "", "", "")
            "Neon" -> listOf("Current CPS", "Peak CPS", "Total clicks", "FPS", "Battery %", "Battery temperature")
            "Glass" -> listOf("Current CPS", "FPS", "Battery %", "Battery temperature", "", "")
            "Terminal" -> listOf("Current CPS", "Peak CPS", "Total clicks", "FPS", "", "")
            else -> listOf("Current CPS", "Peak CPS", "Total clicks", "FPS", "Battery %", "Battery temperature")
        }
        cells = (presetCells + List(18) { "" }).take(24)
        save("text_color", colorHex); save("font_size", fontSize.toInt()); save("opacity", opacity.toInt())
        save("layout", layout); save("preset_base", name); HudMetrics.saveCells(context, cells)
    }
    fun applyCustom(json: org.json.JSONObject) {
        layout = json.optString("layout", layout)
        colorHex = json.optString("text_color", colorHex)
        fontSize = json.optInt("font_size", fontSize.toInt()).toFloat()
        opacity = json.optInt("opacity", opacity.toInt()).toFloat()
        val raw = json.optString("grid_cells", "")
        if (raw.isNotBlank()) {
            val parsed = raw.split("|").take(24)
            cells = parsed + List((24 - parsed.size).coerceAtLeast(0)) { "" }
        }
        alignment = json.optString("alignment", alignment)
        interval = json.optInt("interval_ms", interval.toInt()).toFloat()
        val savedX = json.optInt("x", HudPrefs.int(context, "x", 24))
        val savedY = json.optInt("y", HudPrefs.int(context, "y", 120))
        save("x", savedX); save("y", savedY)
        save("layout", layout); save("text_color", colorHex); save("font_size", fontSize.toInt()); save("opacity", opacity.toInt())
        save("alignment", alignment); save("interval_ms", interval.toInt())
        HudMetrics.saveCells(context, cells); selectedPreset = "Custom"
    }

    LaunchedEffect(Unit) {
        while (true) {
            cps = MonitorState.currentCps(interval.toLong(), context); total = MonitorState.totalClicks; peak = MonitorState.peakCps
            val b = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.getIntExtra("level", -1) ?: -1; val scale = b?.getIntExtra("scale", 100) ?: 100
            batteryPct = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "Unavailable"
            val temp = b?.getIntExtra("temperature", -1) ?: -1
            batteryTemp = if (temp >= 0) "%.1f°C".format(temp / 10f) else "Unavailable"
            overlayPermission = Settings.canDrawOverlays(context)
            accessibilityEnabled = try { (Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(context.packageName, true) } catch (_: Exception) { false }
            delay(500)
        }
    }
    fun startHud() {
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START))
            hudRunning = true
        }
    }
    fun refreshOverlay() {
        if (hudRunning && Settings.canDrawOverlays(context)) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START))
        }
    }

    if (chooseMetric) AlertDialog(onDismissRequest = { chooseMetric = false }, title = { Text("Cell ${selectedCell + 1}") },
        text = { Column { (listOf("(Empty)") + HudMetrics.all).forEach { option -> Text(option, Modifier.fillMaxWidth().clickable {
            cells = cells.toMutableList().also { it[selectedCell] = if (option == "(Empty)") "" else option }
            HudMetrics.saveCells(context, cells); selectedPreset = "Custom"; customPreviewJson = null; chooseMetric = false; refreshOverlay()
        }.padding(10.dp), color = if (cells[selectedCell] == option) Accent else White) } } }, confirmButton = { TextButton(onClick = { chooseMetric = false }) { Text("Close") } })

    Surface(Modifier.fillMaxSize(), color = Bg) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("PulseHUD", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("PERFORMANCE, AT A GLANCE", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp) }
                Text("V3", color = Accent, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0xFF2B211B), CircleShape).padding(horizontal = 15.dp, vertical = 9.dp))
            }
            Panel("LIVE MONITOR") {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Metric("CURRENT CPS", cps.toString(), Modifier.weight(1f)); Metric("PEAK CPS", peak.toString(), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) { Metric("TOTAL CLICKS", total.toString(), Modifier.weight(1f)); Metric("FPS", "—", Modifier.weight(1f), "Game FPS unavailable") }
                Text("FPS is unavailable: no privileged SurfaceFlinger frame source is connected. PulseHUD does not substitute its own refresh rate.", color = Color(0xFFFFC18E), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { startHud() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)) { Text("Start HUD") }
                    OutlinedButton(onClick = { context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)); hudRunning = false; movingHud = false }, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
                Button(onClick = { if (!hudRunning) startHud() else { movingHud = !movingHud; save("edit_mode", movingHud); context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_TOGGLE_EDIT)) } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Raised)) { Text(if (movingHud) "Done" else "Move HUD") }
            }
            Panel("CUSTOM HUD EDITOR · 4 × 6") {
                Text("Select a cell, then assign a metric. Empty cells stay empty. Changes save immediately.", color = Muted, fontSize = 11.sp)
                repeat(4) { r -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { repeat(6) { c ->
                    val i = r * 6 + c
                    Box(Modifier.weight(1f).height(61.dp).background(if (selectedCell == i) Color(0xFF493020) else Raised, RoundedCornerShape(9.dp)).border(if (selectedCell == i) 1.dp else 0.dp, Accent, RoundedCornerShape(9.dp)).clickable { selectedCell = i; chooseMetric = true }.padding(3.dp), contentAlignment = Alignment.Center) {
                        if (cells[i].isNotBlank()) Text(cells[i].replace("Battery temperature", "Batt temp").replace("Total clicks", "Total").replace("Current CPS", "CPS"), color = White, fontSize = 9.sp, maxLines = 2)
                    }
                } } }
                Text("Selected cell ${selectedCell + 1}: ${cells[selectedCell].ifBlank { "Empty" }}", color = Accent, fontSize = 11.sp)
                OutlinedButton(onClick = { cells = List(24) { "" }; selectedPreset = "Custom"; customPreviewJson = null; HudMetrics.saveCells(context, cells); refreshOverlay() }, modifier = Modifier.fillMaxWidth()) { Text("Clear all cells") }
            }
            Panel("LAYOUT MODE") {
                Choice(listOf("horizontal", "vertical", "custom"), layout) { layout = it; save("layout", it); refreshOverlay() }
                Text(when (layout) { "horizontal" -> "Widgets render in one horizontal row."; "vertical" -> "Widgets render in one vertical column."; else -> "Uses your 24 custom cell assignments." }, color = Muted, fontSize = 11.sp)
                Text("LIVE PREVIEW", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Preview(cells, layout, cps, peak, total, batteryPct, batteryTemp)
            }
            Panel("PRESET PREVIEW & LIBRARY") {
                Text("Tap a preset to preview its real layout. Apply it to update the HUD.", color = Muted, fontSize = 11.sp)
                presets.forEach { name ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, color = White, fontSize = 13.sp)
                        Row { TextButton(onClick = { selectedPreset = name; customPreviewJson = null }) { Text("Preview") }; TextButton(onClick = { applyPreset(name); customPreviewJson = null; refreshOverlay() }) { Text("Apply", color = Accent) } }
                    }
                }
                Text("Previewing: $selectedPreset", color = Accent, fontSize = 11.sp)
                val customPreview = remember(customPreviewJson) { try { customPreviewJson?.let { org.json.JSONObject(it) } } catch (_: Exception) { null } }
                val previewCells = if (customPreview != null) { val raw = customPreview.optString("grid_cells", ""); (raw.split("|").take(24) + List((24 - raw.split("|").size).coerceAtLeast(0)) { "" }).take(24) } else if (selectedPreset == "Custom") cells else presetCells(selectedPreset)
                val previewLayout = customPreview?.optString("layout", "custom") ?: if (selectedPreset == "Custom") layout else presetLayout(selectedPreset)
                Preview(previewCells, previewLayout, cps, peak, total, batteryPct, batteryTemp)
                OutlinedTextField(value = customName, onValueChange = { customName = it }, label = { Text("Custom preset name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = {
                    if (customName.isNotBlank()) {
                        val obj = try { org.json.JSONObject(customPresets) } catch (_: Exception) { org.json.JSONObject() }
                        obj.put(customName.trim(), org.json.JSONObject()
                            .put("layout", layout).put("text_color", colorHex)
                            .put("font_size", fontSize.toInt()).put("opacity", opacity.toInt())
                            .put("alignment", alignment).put("interval_ms", interval.toInt())
                            .put("x", HudPrefs.int(context, "x", 24)).put("y", HudPrefs.int(context, "y", 120))
                            .put("grid_cells", cells.joinToString("|")))
                        customPresets = obj.toString(); save("custom_presets_json", customPresets); customName = ""
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)) { Text("Save current custom preset") }
                val names = remember(customPresets) { try { org.json.JSONObject(customPresets).keys().asSequence().toList().sorted() } catch (_: Exception) { emptyList() } }
                names.forEach { name -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = White)
                    Row { TextButton(onClick = { val obj = org.json.JSONObject(customPresets).optJSONObject(name) ?: org.json.JSONObject(); selectedPreset = name; customPreviewJson = obj.toString() }) { Text("Preview") }; TextButton(onClick = { val obj = org.json.JSONObject(customPresets).optJSONObject(name) ?: org.json.JSONObject(); applyCustom(obj); customPreviewJson = null; refreshOverlay() }) { Text("Apply", color = Accent) } }
                } }
            }
            Panel("APPEARANCE") {
                OutlinedTextField(value = colorHex, onValueChange = { colorHex = it.uppercase(); save("text_color", colorHex); refreshOverlay() }, label = { Text("Text color · HEX") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Font size · ${fontSize.toInt()} sp", color = Muted); Slider(value = fontSize, onValueChange = { fontSize = it; save("font_size", it.toInt()); refreshOverlay() }, valueRange = 9f..30f)
                Text("Background opacity · ${opacity.toInt()}%", color = Muted); Slider(value = opacity, onValueChange = { opacity = it; save("opacity", it.toInt()); refreshOverlay() }, valueRange = 0f..100f)
                Choice(listOf("left", "center", "right"), alignment) { alignment = it; save("alignment", it); refreshOverlay() }
                Text("CPS rolling window · ${interval.toInt()} ms", color = Muted); Slider(value = interval, onValueChange = { interval = it; save("interval_ms", it.toInt()); refreshOverlay() }, valueRange = 250f..5000f)
            }
            Panel("DEVICE & PERMISSIONS") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Metric("BATTERY", batteryPct, Modifier.weight(1f)); Metric("TEMPERATURE", batteryTemp, Modifier.weight(1f)) }
                Status("Overlay permission", overlayPermission)
                Status("Accessibility click events", accessibilityEnabled)
                Text("Accessibility only counts click events exposed by an app. Many games use custom rendering and expose no click events. The service excludes PulseHUD's own package; game-wide raw touch capture is not guaranteed.", color = Color(0xFFFFC18E), fontSize = 11.sp)
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.fillMaxWidth()) { Text("Open Accessibility settings") }
                OutlinedButton(onClick = { MonitorState.resetSession(context) }, modifier = Modifier.fillMaxWidth()) { Text("Reset CPS session") }
            }
            Text("PulseHUD 3.0 · Android 10+ · metrics are local", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

private fun presetCells(name: String): List<String> {
    val first = when (name) { "Minimal" -> listOf("Current CPS", "FPS"); "Glass" -> listOf("Current CPS", "FPS", "Battery %", "Battery temperature"); "Terminal" -> listOf("Current CPS", "Peak CPS", "Total clicks", "FPS"); else -> HudMetrics.all }
    return (first + List(24 - first.size) { "" }).take(24)
}
private fun presetLayout(name: String) = if (name == "Neon" || name == "Terminal") "vertical" else "horizontal"

@Composable private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(22.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp); content() } }
}
@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier, hint: String = "") {
    Column(modifier.background(Raised, RoundedCornerShape(14.dp)).padding(11.dp)) { Text(label, color = Muted, fontSize = 9.sp); Text(value, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold); if (hint.isNotBlank()) Text(hint, color = Muted, fontSize = 9.sp) }
}
@Composable private fun Preview(
    cells: List<String>, layout: String, cps: Int, peak: Int, total: Long,
    batteryPct: String, batteryTemp: String
) {
    val safeCells = (cells + List((24 - cells.size).coerceAtLeast(0)) { "" }).take(24)
    val metrics = if (layout == "custom") safeCells.filter { it.isNotBlank() } else safeCells.filter { it.isNotBlank() }
    fun label(metric: String) = when (metric) {
        "Current CPS" -> "CPS  $cps"
        "Peak CPS" -> "PEAK  $peak"
        "Total clicks" -> "TOTAL  $total"
        "FPS" -> "FPS  N/A"
        "Battery %" -> "BATTERY  $batteryPct"
        "Battery temperature" -> "TEMP  $batteryTemp"
        else -> metric
    }
    Column(Modifier.fillMaxWidth().background(Raised, RoundedCornerShape(12.dp)).padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (layout == "custom") repeat(4) { r -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(6) { c ->
            val metric = safeCells[r * 6 + c]
            Box(Modifier.weight(1f).height(34.dp).background(if (metric.isBlank()) Color.Transparent else Color(0xFF37323A), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                if (metric.isNotBlank()) Text(label(metric), color = White, fontSize = 7.sp, maxLines = 2)
            }
        } } }
        else if (layout == "vertical") metrics.forEach { Text(label(it), color = White, fontSize = 11.sp) }
        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { metrics.forEach { Text(label(it), color = White, fontSize = 10.sp) } }
        if (metrics.isEmpty()) Text("(empty HUD)", color = Muted, fontSize = 10.sp)
    }
}
@Composable private fun Choice(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Raised, RoundedCornerShape(12.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { options.forEach { item ->
        Box(Modifier.weight(1f).background(if (item == selected) Accent else Color.Transparent, RoundedCornerShape(9.dp)).clickable { onSelect(item) }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Text(item.replaceFirstChar { it.uppercase() }, color = if (item == selected) Color.Black else White, fontSize = 10.sp) }
    } }
}
@Composable private fun Status(label: String, enabled: Boolean) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(if (enabled) Color(0xFF7DDB9A) else Accent, CircleShape)); Spacer(Modifier.width(8.dp)); Text(label + if (enabled) " · Enabled" else " · Not enabled", color = White, fontSize = 12.sp) } }
