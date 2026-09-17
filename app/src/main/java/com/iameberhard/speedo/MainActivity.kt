package com.iameberhard.speedo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private val Accent = Color(0xFF3DDC97)
private val Track = Color(0xFF1C232B)
private val Card = Color(0xFF12171D)
private val Muted = Color(0xFF8A96A3)
private val Warning = Color(0xFFFFB454)

private const val MPS_TO_KMH = 3.6f
private const val MPS_TO_MPH = 2.236936f
private const val METERS_PER_MILE = 1609.344

/** A fix older than this means the signal is gone (tunnel, deep inside a carriage). */
private const val FIX_TIMEOUT_MS = 5000L

class MainActivity : ComponentActivity() {

    private val viewModel: SpeedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A speedometer that dims after 30 seconds is useless; the flag only applies while on screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color.Black)) {
                App(viewModel)
            }
        }
    }
}

private fun hasFineLocation(context: Context) = ContextCompat.checkSelfPermission(
    context, Manifest.permission.ACCESS_FINE_LOCATION,
) == PackageManager.PERMISSION_GRANTED

@Composable
private fun App(viewModel: SpeedViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasFineLocation(context)) }
    var denied by remember { mutableStateOf(false) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        denied = !granted
    }
    val ask = {
        launcher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    // The permission may have been granted in system settings while we were away.
    LifecycleResumeEffect(Unit) {
        granted = hasFineLocation(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        if (!granted && !askedOnce) {
            askedOnce = true
            ask()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        Box(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            if (granted) {
                LifecycleStartEffect(Unit) {
                    viewModel.start()
                    onStopOrDispose { viewModel.stop() }
                }
                Speedometer(viewModel)
            } else {
                PermissionScreen(
                    denied = denied,
                    onAsk = ask,
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionScreen(denied: Boolean, onAsk: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Speedo needs your precise location", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Speed is read from the GPS chip. Nothing is stored or sent anywhere, and GPS is only " +
                "used while the app is on screen.",
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAsk) { Text("Allow location") }
        if (denied) {
            Spacer(Modifier.height(8.dp))
            Text(
                "If no prompt appears, open settings and set Location to " +
                    "\"Allow only while using the app\" with \"Use precise location\" on.",
                color = Muted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onOpenSettings) { Text("Open app settings") }
        }
    }
}

@Composable
private fun Speedometer(viewModel: SpeedViewModel) {
    val context = LocalContext.current
    // Ticks once a second so a lost signal is noticed even though no new fix arrives to tell us.
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1000)
            value = SystemClock.elapsedRealtime()
        }
    }

    val trip = viewModel.trip
    val mph = viewModel.useMph
    val factor = if (mph) MPS_TO_MPH else MPS_TO_KMH
    val unit = if (mph) "mph" else "km/h"
    val live = trip.lastFixMs != 0L && now - trip.lastFixMs < FIX_TIMEOUT_MS

    val speed = if (live) trip.speedMps * factor else null
    val steps = if (mph) listOf(40, 80, 120, 200, 320) else listOf(60, 120, 200, 320, 500)
    val reference = maxOf(speed ?: 0f, trip.maxMps * factor)
    val gaugeMax = steps.firstOrNull { it > reference } ?: steps.last()

    val distance = if (mph) trip.distanceM / METERS_PER_MILE else trip.distanceM / 1000.0
    val stats = listOf(
        "MAX" to "${(trip.maxMps * factor).roundToInt()} $unit",
        "AVERAGE" to "${(trip.averageMps * factor).roundToInt()} $unit",
        "DISTANCE" to String.format(Locale.getDefault(), if (distance < 100) "%.1f %s" else "%.0f %s", distance, if (mph) "mi" else "km"),
        "MOVING TIME" to formatDuration(trip.movingMs),
    )

    // GPS status sits inside the dial, under the unit: a headline plus a smaller detail line.
    val satellites = viewModel.satellites
    val inView = viewModel.satellitesInView
    val accuracy = trip.accuracyM
    val status = when {
        !viewModel.gpsEnabled -> GpsStatus("Location is off", null, Warning)
        live -> GpsStatus(
            if (accuracy != null) "GPS ±${accuracy.roundToInt()} m" else "GPS locked",
            "$satellites of $inView satellites",
            Accent,
        )
        trip.lastFixMs != 0L -> GpsStatus("GPS signal lost", "$satellites of $inView satellites", Warning)
        // The in-view count climbing is the sign that a fix is on its way.
        inView > 0 -> GpsStatus("Searching for GPS", "$inView satellites in view", Muted)
        else -> GpsStatus("Searching for GPS", "works best near a window", Muted)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val short = maxHeight < 480.dp
        Column(Modifier.fillMaxSize()) {
            if (!viewModel.gpsEnabled) {
                LocationOffRow(onEnableGps = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
            }
            if (wide) {
                // Unfolded / landscape: gauge on the left, trip figures on the right.
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Gauge(speed, unit, gaugeMax, status, viewModel::toggleUnit, Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(16.dp))
                    if (short) {
                        StatGrid(stats, Modifier.width(320.dp))
                    } else {
                        Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            stats.forEach { (label, value) -> StatCard(label, value, Modifier.fillMaxWidth()) }
                        }
                    }
                }
            } else {
                Gauge(speed, unit, gaugeMax, status, viewModel::toggleUnit, Modifier.weight(1f).fillMaxWidth())
                StatGrid(stats, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                UnitToggle(mph = mph, onToggle = viewModel::toggleUnit)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::resetTrip) { Text("Reset trip") }
            }
        }
    }
}

private class GpsStatus(val headline: String, val detail: String?, val color: Color)

@Composable
private fun LocationOffRow(onEnableGps: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Warning))
        Spacer(Modifier.width(8.dp))
        Text("Location is turned off", color = Warning, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onEnableGps) { Text("Turn on") }
    }
}

@Composable
private fun Gauge(
    speed: Float?,
    unit: String,
    gaugeMax: Int,
    status: GpsStatus,
    onToggleUnit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side: Dp = min(maxWidth, maxHeight)
        val stroke = side * 0.05f
        val fraction by animateFloatAsState(
            targetValue = ((speed ?: 0f) / gaugeMax).coerceIn(0f, 1f),
            label = "gauge",
        )
        val density = LocalDensity.current
        val speedSize = with(density) { (side * 0.32f).toSp() }
        val unitSize = with(density) { (side * 0.075f).toSp() }
        val scaleSize = with(density) { (side * 0.045f).toSp() }
        val statusSize = with(density) { (side * 0.06f).toSp() }
        val detailSize = with(density) { (side * 0.042f).toSp() }

        Box(
            Modifier
                .size(side)
                // Tapping the dial switches km/h <-> mph, same as the toggle below.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleUnit,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(stroke)) {
                val style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
                drawArc(Track, startAngle = 135f, sweepAngle = 270f, useCenter = false, style = style)
                if (fraction > 0f) {
                    drawArc(Accent, startAngle = 135f, sweepAngle = 270f * fraction, useCenter = false, style = style)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = speed?.roundToInt()?.toString() ?: "–",
                    color = if (speed != null) Color.White else Muted,
                    style = TextStyle(
                        fontSize = speedSize,
                        lineHeight = speedSize * 1.05f,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                )
                Text(unit, color = Accent, style = TextStyle(fontSize = unitSize, fontWeight = FontWeight.Medium))
                Spacer(Modifier.height(side * 0.025f))
                Text(
                    "● ${status.headline}",
                    color = status.color,
                    style = TextStyle(fontSize = statusSize, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                if (status.detail != null) {
                    Text(status.detail, color = Muted, style = TextStyle(fontSize = detailSize), maxLines = 1)
                }
            }
            // Scale ends, sitting in the open bottom of the arc.
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.62f).padding(bottom = side * 0.06f),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("0", color = Muted, style = TextStyle(fontSize = scaleSize))
                Text("$gaugeMax", color = Muted, style = TextStyle(fontSize = scaleSize))
            }
        }
    }
}

@Composable
private fun StatGrid(stats: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (label, value) -> StatCard(label, value, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun UnitToggle(mph: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Card)
            .padding(4.dp),
    ) {
        listOf("km/h" to false, "mph" to true).forEach { (label, isMph) ->
            val selected = mph == isMph
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Accent else Color.Transparent)
                    .clickable(enabled = !selected, onClick = onToggle)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(label, color = if (selected) Color.Black else Muted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(Locale.US, "%d:%02d:%02d", totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60)
}
