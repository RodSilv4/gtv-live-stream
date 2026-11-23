# Build brief — implement the MVP

Implement the Kotlin sources for the MVP described in `docs/SPEC.md`. The Gradle files, manifest,
layout, strings, colors, theme and drawables **already exist** — do not modify them. Create only
the files listed below, exactly at these paths.

Package: `to.silva.gtvlivestream`. Language: Kotlin (1.9), plain Android views, **no Compose**.
Compile target: `compileSdk 34`, `minSdk 23`, JDK 17. Available dependencies: media3 1.4.1
(exoplayer, exoplayer-hls, exoplayer-rtsp, ui), nanohttpd 2.3.1, androidx.core-ktx, junit 4.13.2.

Do not run Gradle or any build command — the SDK is not installed in this environment. Just write
the files. Report the list of files created.

## 1. `app/src/main/java/to/silva/gtvlivestream/ShowRequest.kt`

```kotlin
package to.silva.gtvlivestream

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

data class ShowDefaults(
    val position: String,
    val widthPct: Int,
    val heightPct: Int,
    val durationS: Int,
    val mute: Boolean,
)

object ShowRequestParser {
    val POSITIONS: Set<String> = linkedSetOf("top-left", "top-right", "bottom-left", "bottom-right", "center")

    /** Parses a JSON body. Throws IllegalArgumentException with a short reason on invalid input. */
    fun parse(json: String, defaults: ShowDefaults): ShowRequest
}
```

Rules:
- JSON parsing with `org.json.JSONObject`.
- `url` is required, must start with `http://`, `https://`, `rtsp://` or `rtsps://` (case-insensitive);
  anything else → `IllegalArgumentException("url must be http(s) or rtsp(s)")`.
- `title` default `"Live"`, trimmed, max 80 chars.
- `subtitle` optional; blank → null; max 120 chars.
- `position` must be one of `POSITIONS`, else `defaults.position`; if that is invalid too, `"bottom-right"`.
- `width_pct` / `height_pct` default from `defaults`; clamp to 15..100.
- `duration_s` default from `defaults`; clamp to 0..600 (0 means "stay until /hide").
- `mute` default from `defaults`.
- Accept both `width_pct` and `widthPercent` style keys? No — only the keys in SPEC.md.

## 2. `app/src/main/java/to/silva/gtvlivestream/Prefs.kt`

```kotlin
package to.silva.gtvlivestream

class Prefs(context: android.content.Context) {
    var port: Int                 // default 8765, clamped 1024..65535
    var token: String             // 24 lowercase hex chars, auto-generated when empty
    var position: String          // default "bottom-right"
    var widthPct: Int             // default 38
    var heightPct: Int            // default 38
    var durationS: Int            // default 40
    var mute: Boolean             // default true
    var startOnBoot: Boolean      // default true
    fun defaults(): ShowDefaults
    fun regenerateToken(): String
}
```

SharedPreferences file name: `"gtv_live_stream"`. Token generation: `SecureRandom`, 12 bytes, hex.
Never log the token.

## 3. `app/src/main/java/to/silva/gtvlivestream/PlayerFactory.kt`

```kotlin
package to.silva.gtvlivestream

object PlayerFactory {
    fun build(context: android.content.Context): androidx.media3.exoplayer.ExoPlayer
}
```

- `DefaultLoadControl.Builder()` with `setBufferDurationsMs(1500, 8000, 500, 800)` and
  `setPrioritizeTimeOverSizeThresholds(true)`.
- `DefaultRenderersFactory` with `setEnableDecoderFallback(true)`.
- `setHandleAudioBecomingNoisy(false)`, `setWakeMode(C.WAKE_MODE_NETWORK)`.
- Mute is controlled by the caller with `player.volume = 0f`.
- Do not set a MediaSource — playback is driven by `OverlayController`.

## 4. `app/src/main/java/to/silva/gtvlivestream/OverlayController.kt`

```kotlin
package to.silva.gtvlivestream

class OverlayController(private val context: android.content.Context) {
    fun show(request: ShowRequest, onClosed: () -> Unit)
    fun hide()
    fun release()
    val isShowing: Boolean
}
```

Behaviour:
- Lazily create one `WindowManager` overlay `FrameLayout` and one `ExoPlayer` (via PlayerFactory);
  reuse them across shows; `hide()` removes the view and stops playback but keeps the player.
- Root: `FrameLayout` with a `GradientDrawable` background — solid `#0B0F14` at 92% alpha, corner
  radius 16dp, 2dp stroke `#34D399`. 12dp padding.
- Header row (horizontal, 28dp): 8dp circular red `#EF4444` dot, title `TextView` (white, 14sp,
  medium), subtitle `TextView` (`#94A3B8`, 11sp), clock `TextView` right-aligned (`#94A3B8`, 11sp,
  `HH:mm`).
- `androidx.media3.ui.PlayerView` with `use_controller=false` (call `setUseController(false)`),
  `resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT`, `layout_height = 0` with `weight = 1f`
  inside a vertical `LinearLayout`, aspect 16:9 approximated by fixing the width and computing
  height from the media's video size — if the player has no video size yet, use 9/16 of the width.
- Window params: `TYPE_APPLICATION_OVERLAY` on API >= 26 else `TYPE_PHONE`;
  flags `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_NO_LIMITS`; gravity per request
  with 24dp margins; width = `screenWidth * widthPct / 100`, height computed from the video aspect
  (clamped to `screenHeight * heightPct / 100`).
- Fade in via `alpha` 0→1 over 150ms; fade out 200ms before removal.
- Auto-hide with `Handler(Looper.getMainLooper())` when `durationS > 0`.
- Touch: tap on the root (outside the video) hides; tap on the PlayerView toggles mute
  (`player.volume = if (player.volume == 0f) 1f else 0f`) and restarts the auto-hide timer.
- `show()` while already showing replaces the media item and restarts the timer — never stack views.
- `release()` removes the view, releases the player, clears handler callbacks.
- Guard every `WindowManager` call in try/catch (`SecurityException` / `IllegalArgumentException`)
  and report failure through `onClosed()`.

## 5. `app/src/main/java/to/silva/gtvlivestream/HttpApiServer.kt`

```kotlin
package to.silva.gtvlivestream

class HttpApiServer(
    private val port: Int,
    private val tokenProvider: () -> String,
    private val isPlaying: () -> Boolean,
    private val onShow: (ShowRequest, () -> Unit) -> Unit,
    private val onHide: () -> Unit,
) : fi.iki.elonen.NanoHTTPD(port)
```

- `serve(session)` routes: `GET /health` (no auth), `POST /show` (auth), `POST /hide` (auth),
  anything else → 404 JSON `{"ok":false,"error":"not found"}`.
- Auth: header `X-Auth-Token` (case-insensitive lookup via `session.headers`) or query `token`;
  compare with `MessageDigest.isEqual` on UTF-8 bytes against `tokenProvider()`.
- `/show`: read `session.parseBody(HashMap())`, body from `map["postData"]`; parse with
  `ShowRequestParser.parse(body, ShowDefaults(...))` — pass defaults through a constructor
  parameter `defaultsProvider: () -> ShowDefaults` (add it as the last parameter). Respond
  `{"ok":true,"playing":true}`; on `IllegalArgumentException` respond 400 with the message.
- `/hide`: `onHide()`, respond `{"ok":true}`.
- `/health`: `{"ok":true,"app":"gtv-live-stream","version":"0.1.0","playing":<bool>,"port":<port>}`.
- All responses `application/json`, charset utf-8, with `Access-Control-Allow-Origin: *`.
- Never log header values or bodies.

## 6. `app/src/main/java/to/silva/gtvlivestream/OverlayService.kt`

```kotlin
package to.silva.gtvlivestream

class OverlayService : android.app.Service() {
    companion object {
        const val ACTION_START = "to.silva.gtvlivestream.START"
        const val ACTION_STOP = "to.silva.gtvlivestream.STOP"
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var lastError: String? = null
        fun start(context: android.content.Context)
        fun stop(context: android.content.Context)
    }
}
```

- `onCreate`: create notification channel `gtv_live_stream` (low importance), `startForeground(1, notification)`
  with `PendingIntent` to `MainActivity`; build `Prefs`, `OverlayController`, `HttpApiServer`
  (port, `{ prefs.token }`, `{ overlay.isShowing }`, show callback that calls
  `overlay.show(request) { }`, hide callback → `overlay.hide()`), start server.
- `onStartCommand`: handle `ACTION_STOP` → `stopSelf()`; return `START_STICKY`.
- `onDestroy`: stop server, release overlay, `stopForeground(true)`, `isRunning = false`.
- `start(context)`: `ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java).setAction(ACTION_START))`.
- `stop(context)`: `context.startService(Intent(...).setAction(ACTION_STOP))` (fall back silently on
  API 26+ if the service is not running).
- Set `lastError` on server start failure.

## 7. `app/src/main/java/to/silva/gtvlivestream/BootReceiver.kt`

```kotlin
package to.silva.gtvlivestream

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent)
}
```

Start the service when `Prefs(context).startOnBoot` is true and
`Settings.canDrawOverlays(context)` is true.

## 8. `app/src/main/java/to/silva/gtvlivestream/MainActivity.kt`

Plain `android.app.Activity`. Uses `R.layout.activity_main` and these view ids:
`statusText`, `ipText`, `portInput`, `tokenInput`, `copyTokenButton`, `regenTokenButton`,
`positionSpinner`, `widthInput`, `heightInput`, `durationInput`, `muteSwitch`, `bootSwitch`,
`saveButton`, `grantButton`, `startButton`, `stopButton`, `testButton`, `yamlText`.

- Populate fields from `Prefs` in `onCreate`; populate `positionSpinner` with `ShowRequestParser.POSITIONS`.
- `saveButton`: read + clamp, persist, toast `R.string.toast_saved`, refresh status/YAML.
- `grantButton`: `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` with `package:` URI.
- `startButton` / `stopButton`: call `OverlayService.start/stop`, toast, refresh after 800ms.
- `testButton`: `POST http://127.0.0.1:<port>/show` with the stored token and
  `{"url":"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8","title":"Test","subtitle":"gtv-live-stream","duration_s":15}`
  on a background thread (`HttpURLConnection`); toast success/failure. No third-party HTTP lib.
- `copyTokenButton`: `ClipboardManager`.
- `regenTokenButton`: new token, update field, toast "restart the service to apply".
- `yamlText`: build the `rest_command` snippet with device IP, port and token.
- `onResume`: refresh status (`OverlayService.isRunning`, `Settings.canDrawOverlays`), IP line:
  all non-loopback IPv4 addresses, comma separated.
- Keep all logic on the UI thread except the test POST.

## 9. `app/src/test/java/to/silva/gtvlivestream/ShowRequestTest.kt`

JUnit 4 tests (no Android framework): valid parse, defaults applied, percentage clamping,
duration clamping, unknown position falls back to default, missing url throws
`IllegalArgumentException`, non-http(s)/rtsp(s) scheme throws.

## Definition of done

- All nine files exist, compile-plausible Kotlin (balanced braces, imports present for every symbol
  used, no references to classes that are not in this repo or the declared dependencies).
- No TODO stubs; every function has a real body.
- Report: files created with line counts, plus anything in the spec you could not implement and why.
