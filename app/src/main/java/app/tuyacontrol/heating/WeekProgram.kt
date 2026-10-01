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

    /** Сколько периодов на рабочий день в программе (остальные записи — выходные, их не трогаем). */
    const val PERIODS = 6

    /**
     * Почасовые уставки -> не больше [PERIODS] периодов на сутки. Где периодов не хватает, держим более
     * высокую из уставок внутри периода (комфорт не проседает), выбирая границы так, чтобы переплата
     * (градусы × цена часа) была минимальной. Всегда ровно [PERIODS] записей по возрастанию времени.
     */
    fun compress(setpoints: DoubleArray, price: DoubleArray, periods: Int = PERIODS): List<Period> {
        val n = 24
        var bestCost = Double.MAX_VALUE
        var bestStarts: List<Int> = listOf(0)
        for (s in 0 until n) {
            val h = IntArray(n) { (s + it) % n }
            // cost[a][b] — переплата, если часы a..b-1 (в порядке от s) держать на максимуме
            val cost = Array(n + 1) { DoubleArray(n + 1) }
            for (a in 0 until n) {
                var mx = Double.NEGATIVE_INFINITY
                for (b in a + 1..n) {
                    mx = maxOf(mx, setpoints[h[b - 1]])
                    var c = 0.0
                    for (j in a until b) c += (mx - setpoints[h[j]]) * price[h[j]]
                    cost[a][b] = c
                }
            }
            val f = Array(periods + 1) { DoubleArray(n + 1) { Double.MAX_VALUE } }
            val from = Array(periods + 1) { IntArray(n + 1) }
            f[0][0] = 0.0
            for (k in 1..periods) for (b in 1..n) for (a in 0 until b) {
                if (f[k - 1][a] == Double.MAX_VALUE) continue
                val v = f[k - 1][a] + cost[a][b]
                if (v < f[k][b] - 1e-12) { f[k][b] = v; from[k][b] = a }
            }
            for (k in 1..periods) {
                if (f[k][n] < bestCost - 1e-9) {
                    bestCost = f[k][n]
                    val starts = mutableListOf<Int>()
                    var b = n
                    var kk = k
                    while (kk > 0) { val a = from[kk][b]; starts += h[a]; b = a; kk-- }
                    bestStarts = starts.reversed()
                }
            }
        }
        // Температура периода — максимум уставок до начала следующего
        val starts = bestStarts.sorted().toMutableList()
        fun tempOf(i: Int): Double {
            val a = starts[i]
            val b = starts[(i + 1) % starts.size]
            val len = ((b - a + n - 1) % n) + 1
            return (0 until len).maxOf { setpoints[(a + it) % n] }
        }
        var list = starts.indices.map { Period(starts[it] * 60, tempOf(it)) }
        // Дополняем до ровно [periods] записей: делим самый длинный период пополам с той же температурой
        while (list.size < periods) {
            val lens = list.indices.map { i ->
                val a = list[i].minutes
                val b = list[(i + 1) % list.size].minutes
                ((b - a + 1440 - 1) % 1440) + 1
            }
            val i = lens.indices.maxByOrNull { lens[it] }!!
            if (lens[i] < 120) break
            val mid = (list[i].minutes + (lens[i] / 60 / 2) * 60) % 1440
            list = (list + Period(mid, list[i].temp)).sortedBy { it.minutes }
        }
        return list
    }

    /** Почасовая уставка по программе (часы 0..23). */
    fun hourly(program: List<Period>): DoubleArray {
        val sorted = program.sortedBy { it.minutes }
        return DoubleArray(24) { h ->
            val m = h * 60
            (sorted.lastOrNull { it.minutes <= m } ?: sorted.last()).temp
        }
    }
}
