package app.tuyacontrol.cloud

/** Описание одной точки данных (DP) устройства из спецификации Tuya. */
data class DpSpec(
    val code: String,
    /** Boolean, Integer, Enum, String, Json, Raw, Bitmap */
    val type: String,
    val unit: String = "",
    val scale: Int = 0,
    val min: Long? = null,
    val max: Long? = null,
    val step: Long = 1,
    val range: List<String> = emptyList(),
    /** true, если код есть среди functions — значит, им можно управлять. */
    val writable: Boolean = false,
    /** Номер DP для локального протокола (из модели устройства); null — неизвестен. */
    val dpId: Int? = null,
)

/** Устройство из облака. */
data class CloudDevice(
    val id: String,
    val name: String,
    val online: Boolean,
    val productName: String,
    val category: String,
    val localKey: String,
    val ip: String,
    /** code -> value; null, если облако не вернуло статус вместе со списком. */
    val status: Map<String, Any?>?,
    /** Время активации устройства, секунды Unix (0 — неизвестно). */
    val activeTime: Long = 0,
    /** Время последнего обновления устройства в облаке, мс (0 — неизвестно). */
    val updateTime: Long = 0,
)

data class StatType(val code: String, val statType: String)

/** Запись журнала. eventId: 1 — устройство в сети, 2 — не в сети, 7 — отчёт DP. */
data class LogEntry(val code: String, val value: String, val time: Long, val eventId: Int = 7)

class TuyaApiException(val code: Int, message: String) : Exception(message)

/** Облачный таймер Tuya (то, что в Smart Life — «Расписание»): выполняет облако, не устройство. */
data class CloudTimer(
    val category: String,
    val groupId: String,
    /** «HH:mm». */
    val time: String,
    /** Дни недели «1111111» (вс…сб) или пусто — однократно. */
    val loops: String,
    /** Команды, например switch_1=true. */
    val functions: List<Pair<String, Any?>>,
    val enabled: Boolean,
) {
    /** «вкл» / «выкл» для выключателя, иначе код=значение. */
    fun actionText(): String = functions.joinToString(", ") { (c, v) ->
        if (v is Boolean && (c.startsWith("switch") || c == "power")) (if (v) "вкл" else "выкл") else "$c=$v"
    }.ifEmpty { "—" }

    fun daysText(): String = when {
        loops == "1111111" -> ""
        loops.isEmpty() || loops == "0000000" -> " (однократно)"
        else -> " (" + loops.mapIndexedNotNull { i, ch -> if (ch == '1') listOf("вс", "пн", "вт", "ср", "чт", "пт", "сб")[i] else null }.joinToString(",") + ")"
    }
}
