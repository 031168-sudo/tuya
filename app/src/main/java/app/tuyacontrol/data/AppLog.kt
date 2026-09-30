package app.tuyacontrol.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал в памяти для экрана «Логи»: чтобы можно было прислать скриншот при ошибке. */
object AppLog {
    private const val TAG = "TuyaControl"
    private const val MAX_LINES = 500

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Локальные ключи устройств и токены в журнал не пишем
    private val secretPattern =
        Regex("\"(local_key|localKey|access_token|refresh_token)\"\\s*:\\s*\"[^\"]*\"")

    fun i(message: String) = add("I", message)
    fun e(message: String, error: Throwable? = null) =
        add("E", if (error != null) "$message: ${error.javaClass.simpleName}: ${error.message}" else message)

    fun clear() = _lines.update { emptyList() }

    @Synchronized
    private fun add(level: String, message: String) {
        val masked = mask(message)
        if (level == "E") Log.e(TAG, masked) else Log.i(TAG, masked)
        val line = "${timeFormat.format(Date())} $level $masked"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    fun mask(text: String): String = secretPattern.replace(text) { "\"${it.groupValues[1]}\":\"***\"" }
}
