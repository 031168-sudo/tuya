package app.tuyacontrol.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class Credentials(
    val accessId: String,
    val accessSecret: String,
    val endpoint: String,
)

/** Дата-центры Tuya OpenAPI. */
enum class TuyaRegion(val title: String, val host: String) {
    CENTRAL_EUROPE("Central Europe", "openapi.tuyaeu.com"),
    WESTERN_EUROPE("Western Europe", "openapi-weaz.tuyaeu.com"),
    WESTERN_AMERICA("Western America", "openapi.tuyaus.com"),
    EASTERN_AMERICA("Eastern America", "openapi-ueaz.tuyaus.com"),
    CHINA("China", "openapi.tuyacn.com"),
    INDIA("India", "openapi.tuyain.com"),
    SINGAPORE("Singapore", "openapi-sg.iotbing.com"),
}

/** Access ID / Secret хранятся только на телефоне, в зашифрованном хранилище. */
class CredentialsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = openPrefs()

    private fun openPrefs(): SharedPreferences = try {
        create()
    } catch (e: Exception) {
        // Бывает после переустановки: ключ шифрования в Keystore не совпадает с файлом.
        AppLog.e("Хранилище ключей повреждено, создаю заново", e)
        appContext.deleteSharedPreferences(FILE_NAME)
        create()
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): Credentials? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val secret = prefs.getString(KEY_SECRET, null) ?: return null
        val endpoint = prefs.getString(KEY_ENDPOINT, null) ?: TuyaRegion.CENTRAL_EUROPE.host
        return Credentials(id, secret, endpoint)
    }

    fun save(credentials: Credentials) {
        prefs.edit()
            .putString(KEY_ID, credentials.accessId)
            .putString(KEY_SECRET, credentials.accessSecret)
            .putString(KEY_ENDPOINT, credentials.endpoint)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "tuya_secure"
        const val KEY_ID = "access_id"
        const val KEY_SECRET = "access_secret"
        const val KEY_ENDPOINT = "endpoint"
    }
}
