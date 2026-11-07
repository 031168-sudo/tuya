package app.tuyacontrol.rubetek

import org.json.JSONObject

/**
 * Таймер, записанный в Wi-Fi-модуль конвектора Rubetek (tmr:on[i] / tmr:off[i]).
 * Значение в модуле: (дни недели << 16) | минуты от полуночи; −1 — слот пуст. 127 — каждый день.
 */
data class ModuleTimer(val minutes: Int, val on: Boolean, val days: Int) {
    val everyDay: Boolean get() = days == EVERY_DAY

    fun text(): String = "%02d:%02d %s".format(minutes / 60, minutes % 60, if (on) "вкл" else "выкл") +
        if (everyDay) "" else " (не каждый день)"

    companion object {
        const val EVERY_DAY = 127

        /** Таймеры из состояния модуля; null — модуль таймеры не поддерживает (полей tmr: нет). */
        fun fromState(state: JSONObject): List<ModuleTimer>? {
            if ((0 until 10).none { state.has("tmr:on[$it]") || state.has("tmr:off[$it]") }) return null
            val out = mutableListOf<ModuleTimer>()
            for (i in 0 until 10) {
                for (on in listOf(true, false)) {
                    val v = state.optLong(if (on) "tmr:on[$i]" else "tmr:off[$i]", -1)
                    if (v < 0) continue
                    out += ModuleTimer((v and 0xFFFF).toInt(), on, ((v shr 16) and 0x7F).toInt())
                }
            }
            return out.sortedWith(compareBy({ it.minutes }, { it.on }))
        }
    }
}
