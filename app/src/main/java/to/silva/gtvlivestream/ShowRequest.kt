package to.silva.gtvlivestream

import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * A parsed `POST /show` payload, already clamped to the ranges documented in docs/SPEC.md.
 */
data class ShowRequest(
    val url: String,
    val title: String,
    val subtitle: String?,
    val position: String,
    val widthPct: Int,
    val heightPct: Int,
    val durationS: Int,
    val mute: Boolean,
)

/**
 * Application defaults applied to any field a request leaves out.
 */
data class ShowDefaults(
    val position: String,
    val widthPct: Int,
    val heightPct: Int,
    val durationS: Int,
    val mute: Boolean,
)

object ShowRequestParser {
    val POSITIONS: Set<String> = linkedSetOf("top-left", "top-right", "bottom-left", "bottom-right", "center")

    private const val DEFAULT_TITLE = "Live"
    private const val FALLBACK_POSITION = "bottom-right"
    private const val MIN_PCT = 15
    private const val MAX_PCT = 100
    private const val MIN_DURATION_S = 0
    private const val MAX_DURATION_S = 600
    private const val MAX_TITLE_CHARS = 80
    private const val MAX_SUBTITLE_CHARS = 120

    /** Parses a JSON body. Throws IllegalArgumentException with a short reason on invalid input. */
    fun parse(json: String, defaults: ShowDefaults): ShowRequest {
        val obj = try {
            JSONObject(json.ifBlank { "{}" })
        } catch (e: JSONException) {
            throw IllegalArgumentException("body must be a JSON object")
        }

        if (!obj.has("url") || obj.isNull("url")) {
            throw IllegalArgumentException("url is required")
        }
        val url = obj.optString("url", "").trim()
        if (url.isEmpty()) {
            throw IllegalArgumentException("url is required")
        }
        if (!hasSupportedScheme(url)) {
            throw IllegalArgumentException("url must be http(s) or rtsp(s)")
        }

        val title: String = if (obj.has("title") && !obj.isNull("title")) {
            obj.optString("title", DEFAULT_TITLE)
                .trim()
                .take(MAX_TITLE_CHARS)
                .ifEmpty { DEFAULT_TITLE }
        } else {
            DEFAULT_TITLE
        }

        val subtitle: String? = if (obj.has("subtitle") && !obj.isNull("subtitle")) {
            obj.optString("subtitle", "").trim().take(MAX_SUBTITLE_CHARS).ifEmpty { null }
        } else {
            null
        }

        val position = resolvePosition(obj, defaults)

        val widthPct = optInt(obj, "width_pct", defaults.widthPct).coerceIn(MIN_PCT, MAX_PCT)
        val heightPct = optInt(obj, "height_pct", defaults.heightPct).coerceIn(MIN_PCT, MAX_PCT)
        val durationS = optInt(obj, "duration_s", defaults.durationS).coerceIn(MIN_DURATION_S, MAX_DURATION_S)

        val mute = if (obj.has("mute") && !obj.isNull("mute")) {
            obj.optBoolean("mute", defaults.mute)
        } else {
            defaults.mute
        }

        return ShowRequest(
            url = url,
            title = title,
            subtitle = subtitle,
            position = position,
            widthPct = widthPct,
            heightPct = heightPct,
            durationS = durationS,
            mute = mute,
        )
    }

    private fun hasSupportedScheme(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("rtsps://")
    }

    private fun resolvePosition(obj: JSONObject, defaults: ShowDefaults): String {
        val requested = obj.optString("position", "").trim().lowercase(Locale.US)
        if (requested in POSITIONS) {
            return requested
        }
        val fallback = defaults.position.trim().lowercase(Locale.US)
        return if (fallback in POSITIONS) fallback else FALLBACK_POSITION
    }

    /** Reads an int field, tolerating numeric strings and falling back on anything unusable. */
    private fun optInt(obj: JSONObject, key: String, fallback: Int): Int {
        if (!obj.has(key) || obj.isNull(key)) {
            return fallback
        }
        return when (val value = obj.opt(key)) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: fallback
            else -> fallback
        }
    }
}
