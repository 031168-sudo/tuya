package app.tuyacontrol.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Пастельная палитра категорий. Цвет не заливается «в лоб», а подмешивается к фону
 * карточки: в тёмной теме слабо, в светлой сильнее — чтобы не резало глаза.
 */
object Pastel {
    val colors: List<Color> = listOf(
        Color(0xFFF4A9A8), // коралловый
        Color(0xFFF7C59F), // персиковый
        Color(0xFFF9E2A2), // песочный
        Color(0xFFC9E4A6), // фисташковый
        Color(0xFFA8DCC3), // мятный
        Color(0xFFA4D8E6), // голубой
        Color(0xFFABC4F0), // васильковый
        Color(0xFFC7B8EE), // лавандовый
        Color(0xFFE6B5DC), // сиреневый
        Color(0xFFF2B8C6), // розовый
        Color(0xFFD7C4AE), // бежевый
        Color(0xFFC5CCD6), // серо-голубой
    )

    val names = listOf(
        "Коралловый", "Персиковый", "Песочный", "Фисташковый", "Мятный", "Голубой",
        "Васильковый", "Лавандовый", "Сиреневый", "Розовый", "Бежевый", "Серо-голубой",
    )

    fun color(index: Int): Color = colors[Math.floorMod(index, colors.size)]

    /** Фон карточки/плитки категории. */
    @Composable
    fun container(index: Int?): Color {
        val surface = MaterialTheme.colorScheme.surfaceContainerHigh
        if (index == null) return surface
        return lerp(surface, color(index), if (isSystemInDarkTheme()) 0.22f else 0.55f)
    }

    /** Кружок под иконкой: чуть насыщеннее фона. */
    @Composable
    fun accent(index: Int?): Color {
        val surface = MaterialTheme.colorScheme.surfaceContainerHigh
        if (index == null) return MaterialTheme.colorScheme.surfaceContainerHighest
        return lerp(surface, color(index), if (isSystemInDarkTheme()) 0.45f else 0.9f)
    }
}
