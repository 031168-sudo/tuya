package app.tuyacontrol.heating

import java.util.Base64

/**
 * Недельная программа, записанная в сам термостат (DP Raw, base64).
 *
 *  - week_program3 (термостаты «Temp»: гостиная, коридор, летняя кухня): записи по 4 байта —
 *    час, минута, температура ×10 (2 байта, старший первым). 8 записей: 6 периодов рабочих дней + 2 выходных.
 *  - week_program1 (спальня, 智能温控器): записи по 3 байта — час, минута, температура в градусах.
 */
object WeekProgram {

    data class Period(val minutes: Int, val temp: Double) {
        fun text() = "%02d:%02d %s°".format(minutes / 60, minutes % 60, if (temp % 1.0 == 0.0) temp.toInt().toString() else temp.toString())
    }

    /** Размер записи для кода DP; null — формат неизвестен. */
    fun recordSize(code: String): Int? = when (code) {
        "week_program3" -> 4
        "week_program1" -> 3
        else -> null
    }

    fun decode(code: String, base64: String): List<Period>? {
        val size = recordSize(code) ?: return null
        val b = runCatching { Base64.getDecoder().decode(base64.trim()) }.getOrNull() ?: return null
        if (b.isEmpty() || b.size % size != 0) return null
        return (0 until b.size / size).map { i ->
            val o = i * size
            val h = b[o].toInt() and 0xFF
            val m = b[o + 1].toInt() and 0xFF
            val t = if (size == 4) (((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)) / 10.0
            else (b[o + 2].toInt() and 0xFF).toDouble()
            Period(h * 60 + m, t)
        }
    }

    fun encode(code: String, periods: List<Period>): String? {
        val size = recordSize(code) ?: return null
        val out = ByteArray(periods.size * size)
        periods.forEachIndexed { i, p ->
            val o = i * size
            out[o] = (p.minutes / 60).toByte()
            out[o + 1] = (p.minutes % 60).toByte()
            if (size == 4) {
                val t = Math.round(p.temp * 10).toInt()
                out[o + 2] = (t shr 8).toByte()
                out[o + 3] = t.toByte()
            } else {
                out[o + 2] = Math.round(p.temp).toInt().toByte()
            }
        }
        return Base64.getEncoder().encodeToString(out)
    }
}
