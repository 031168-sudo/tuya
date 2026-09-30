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
