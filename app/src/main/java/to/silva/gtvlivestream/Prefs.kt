package to.silva.gtvlivestream

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * SharedPreferences wrapper for every persisted setting. The token is generated on first read and
 * is never logged.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val random: SecureRandom = SecureRandom()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(MIN_PORT, MAX_PORT)
        set(value) {
            prefs.edit().putInt(KEY_PORT, value.coerceIn(MIN_PORT, MAX_PORT)).apply()
        }

    var token: String
        get() {
            val stored = prefs.getString(KEY_TOKEN, null)
            if (!stored.isNullOrBlank()) {
                return stored
            }
            return regenerateToken()
        }
        set(value) {
            val clean = value.trim()
            if (clean.isEmpty()) {
                regenerateToken()
            } else {
                prefs.edit().putString(KEY_TOKEN, clean).apply()
            }
        }

    var position: String
        get() {
            val stored = prefs.getString(KEY_POSITION, DEFAULT_POSITION)
            return if (stored != null && stored in ShowRequestParser.POSITIONS) stored else DEFAULT_POSITION
        }
        set(value) {
            val clean = if (value in ShowRequestParser.POSITIONS) value else DEFAULT_POSITION
            prefs.edit().putString(KEY_POSITION, clean).apply()
        }

    var widthPct: Int
        get() = prefs.getInt(KEY_WIDTH_PCT, DEFAULT_PCT).coerceIn(MIN_PCT, MAX_PCT)
        set(value) {
            prefs.edit().putInt(KEY_WIDTH_PCT, value.coerceIn(MIN_PCT, MAX_PCT)).apply()
        }

    var heightPct: Int
        get() = prefs.getInt(KEY_HEIGHT_PCT, DEFAULT_PCT).coerceIn(MIN_PCT, MAX_PCT)
        set(value) {
            prefs.edit().putInt(KEY_HEIGHT_PCT, value.coerceIn(MIN_PCT, MAX_PCT)).apply()
        }

    var durationS: Int
        get() = prefs.getInt(KEY_DURATION_S, DEFAULT_DURATION_S).coerceIn(MIN_DURATION_S, MAX_DURATION_S)
        set(value) {
            prefs.edit().putInt(KEY_DURATION_S, value.coerceIn(MIN_DURATION_S, MAX_DURATION_S)).apply()
        }

    var mute: Boolean
        get() = prefs.getBoolean(KEY_MUTE, DEFAULT_MUTE)
        set(value) {
            prefs.edit().putBoolean(KEY_MUTE, value).apply()
        }

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, DEFAULT_START_ON_BOOT)
        set(value) {
            prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()
        }

    fun defaults(): ShowDefaults = ShowDefaults(
        position = position,
        widthPct = widthPct,
        heightPct = heightPct,
        durationS = durationS,
        mute = mute,
    )

    /** Generates, stores and returns a fresh 24-character lowercase hex token. */
    fun regenerateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val builder = StringBuilder(TOKEN_BYTES * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4])
            builder.append(HEX[value and 0x0F])
        }
        val token = builder.toString()
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    private companion object {
        const val FILE_NAME: String = "gtv_live_stream"

        const val KEY_PORT: String = "port"
        const val KEY_TOKEN: String = "token"
        const val KEY_POSITION: String = "position"
        const val KEY_WIDTH_PCT: String = "width_pct"
        const val KEY_HEIGHT_PCT: String = "height_pct"
        const val KEY_DURATION_S: String = "duration_s"
        const val KEY_MUTE: String = "mute"
        const val KEY_START_ON_BOOT: String = "start_on_boot"

        const val DEFAULT_PORT: Int = 8765
        const val MIN_PORT: Int = 1024
        const val MAX_PORT: Int = 65535
        const val DEFAULT_POSITION: String = "bottom-right"
        const val DEFAULT_PCT: Int = 38
        const val MIN_PCT: Int = 15
        const val MAX_PCT: Int = 100
        const val DEFAULT_DURATION_S: Int = 40
        const val MIN_DURATION_S: Int = 0
        const val MAX_DURATION_S: Int = 600
        const val DEFAULT_MUTE: Boolean = true
        const val DEFAULT_START_ON_BOOT: Boolean = true

        const val TOKEN_BYTES: Int = 12
        val HEX: CharArray = "0123456789abcdef".toCharArray()
    }
}
