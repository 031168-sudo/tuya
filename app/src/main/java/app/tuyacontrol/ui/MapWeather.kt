package app.tuyacontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tuyacontrol.DeviceUi
import app.tuyacontrol.HeatingUiState
import app.tuyacontrol.data.WeatherNow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Погода в шапке «Карты»: пиктограмма, температура уличного датчика (T&H, тот же, что в «Отоплении»)
 * и прогноз на сегодня «макс / мин».
 */
@Composable
fun OutdoorWeather(devices: List<DeviceUi>, heating: HeatingUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s = heating.settings
    val now by produceState<WeatherNow.Now?>(null, s.latitude, s.longitude) {
        value = WeatherNow(context).get(s.latitude, s.longitude)
    }
    val outdoor = devices.firstOrNull { it.id == s.outdoorSensorId }?.let(::deviceTemperature)
    // Мин/макс на сегодня — из прогноза «Отопления» (там он уже поправлен по уличному датчику),
    // пока «Отопление» его не посчитало — прямо из Open-Meteo
    val today = heating.outdoor.take(24)
    val (max, min) = if (today.size == 24 && today.any { it != 0.0 }) today.max() to today.min() else now?.max to now?.min
    val kind = now?.let { WeatherNow.kind(it.code) } ?: WeatherNow.Kind.UNKNOWN

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        WeatherIcon(
            kind, day = now?.isDay ?: true,
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = kind.title },
        )
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                outdoor?.value?.let { String.format(Locale("ru"), "%.1f°", it) } ?: "—",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                // Как в карточке: датчик не в сети — серым
                color = if (outdoor?.value == null || outdoor?.stale != false) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.onSurface,
            )
            if (max != null && min != null) {
                Text(
                    String.format(Locale("ru"), "↑%.0f°  ↓%.0f°", max, min),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val SunColor = Color(0xFFF5B82E)
private val MoonColor = Color(0xFFE3C565)
private val CloudLight = Color(0xFFB9C1CC)
private val CloudDark = Color(0xFF8E98A6)
private val RainColor = Color(0xFF4A90D9)
private val SnowColor = Color(0xFF8DB8E3)
private val BoltColor = Color(0xFFF2B01E)

/** Простая пиктограмма погоды: солнце/луна, облако, дождь, снег, гроза, туман. */
@Composable
private fun WeatherIcon(kind: WeatherNow.Kind, day: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        when (kind) {
            WeatherNow.Kind.CLEAR -> if (day) sun(p(0.5f, 0.5f), 0.2f * u) else moon(p(0.5f, 0.5f), 0.3f * u)
            WeatherNow.Kind.PARTLY -> {
                if (day) sun(p(0.36f, 0.34f), 0.15f * u) else moon(p(0.36f, 0.34f), 0.2f * u)
                cloud(u, dy = 0.08f, color = CloudLight)
            }
            WeatherNow.Kind.CLOUDY, WeatherNow.Kind.UNKNOWN -> cloud(u, dy = 0f, color = CloudLight)
            WeatherNow.Kind.FOG -> {
                cloud(u, dy = -0.08f, color = CloudLight)
                for (y in listOf(0.76f, 0.88f)) {
                    drawLine(CloudDark, p(0.2f, y), p(0.84f, y), 0.06f * u, cap = StrokeCap.Round)
                }
            }
            WeatherNow.Kind.DRIZZLE -> {
                cloud(u, dy = -0.1f, color = CloudLight)
                for (x in listOf(0.36f, 0.52f, 0.68f)) drawCircle(RainColor, 0.035f * u, p(x, 0.82f))
            }
            WeatherNow.Kind.RAIN -> {
                cloud(u, dy = -0.1f, color = CloudDark)
                for (x in listOf(0.36f, 0.52f, 0.68f)) {
                    drawLine(RainColor, p(x, 0.74f), p(x - 0.06f, 0.94f), 0.055f * u, cap = StrokeCap.Round)
                }
            }
            WeatherNow.Kind.SNOW -> {
                cloud(u, dy = -0.1f, color = CloudLight)
                for ((x, y) in listOf(0.34f to 0.8f, 0.52f to 0.9f, 0.7f to 0.8f)) drawCircle(SnowColor, 0.05f * u, p(x, y))
            }
            WeatherNow.Kind.THUNDER -> {
                cloud(u, dy = -0.1f, color = CloudDark)
                val bolt = Path().apply {
                    moveTo(0.56f * u, 0.62f * u)
                    lineTo(0.42f * u, 0.82f * u)
                    lineTo(0.52f * u, 0.82f * u)
                    lineTo(0.46f * u, 0.98f * u)
                    lineTo(0.64f * u, 0.74f * u)
                    lineTo(0.54f * u, 0.74f * u)
                    close()
                }
                drawPath(bolt, BoltColor)
            }
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float) {
    drawCircle(SunColor, r, c)
    for (i in 0 until 8) {
        val a = i * PI / 4
        val d = Offset(cos(a).toFloat(), sin(a).toFloat())
        drawLine(SunColor, c + d * (r * 1.35f), c + d * (r * 1.8f), r * 0.28f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.moon(c: Offset, r: Float) {
    val disk = Path().apply { addOval(Rect(c, r)) }
    val cut = Path().apply { addOval(Rect(c + Offset(r * 0.55f, -r * 0.35f), r * 0.85f)) }
    drawPath(Path.combine(PathOperation.Difference, disk, cut), MoonColor)
}

/** Облако из трёх кругов и скруглённого основания; dy — сдвиг вниз в долях размера. */
private fun DrawScope.cloud(u: Float, dy: Float, color: Color) {
    drawCircle(color, 0.16f * u, Offset(0.36f * u, (0.52f + dy) * u))
    drawCircle(color, 0.21f * u, Offset(0.56f * u, (0.45f + dy) * u))
    drawCircle(color, 0.14f * u, Offset(0.72f * u, (0.57f + dy) * u))
    drawRoundRect(
        color,
        topLeft = Offset(0.2f * u, (0.52f + dy) * u),
        size = Size(0.66f * u, 0.19f * u),
        cornerRadius = CornerRadius(0.095f * u),
    )
}
