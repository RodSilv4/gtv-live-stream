# GTV Live Stream — MVP spec

Android TV / Google TV companion app that shows a **small floating live-video window** over
whatever is currently on screen, triggered over the LAN by an HTTP client.

This is the app-shaped answer to a limit in TV platforms and casting: notifications can carry
only a still image, and casting a dashboard takes the whole screen and cannot render a live camera
stream. A local overlay service can do what neither can.

## Goals

1. `POST /show` with a stream URL → a small rounded video window appears in a screen corner within
   ~1s, plays the stream, disappears after `duration_s` or on `POST /hide`.
2. Non-interactive: the overlay must never steal D-pad focus from the app underneath.
3. Works with RTSP, HLS (m3u8) and progressive MP4 sources.
4. Survives TV sleep/wake and reboots (foreground service + boot receiver).
5. Trivial to drive from any client (Home Assistant `rest_command`, Node-RED, `curl`).
6. Bearer-token auth on every request; no cloud dependency; LAN only.

## Non-goals (MVP)

- No Play Store distribution, no Firebase, no analytics.
- No touch interaction beyond tap-to-dismiss (TV remotes cannot click a non-focusable overlay).
- No transcoding. The app plays what it is given; the source must already be TV-decodable
  (H.264 up to 1080p is safe, 4K/HEVC depends on the device).
- No notification-style popup: this is a window, not an Android notification.
- No support for overlaying DRM-protected apps (Netflix/Prime/Disney black out third-party
  overlays; that is a platform rule, not a bug).

## Components

| Component | Responsibility |
|---|---|
| `MainActivity` | Settings + status screen. Shows IP:port, token, service state, overlay defaults, buttons to grant overlay permission, start/stop service, send a test overlay, and copy the HA `rest_command` snippet. |
| `OverlayService` | Foreground service. Owns the HTTP server and the overlay lifecycle. START_STICKY, restarted by `BootReceiver`. |
| `HttpApiServer` | NanoHTTPD server bound to `0.0.0.0:8765`. Routes: `GET /health`, `POST /show`, `POST /hide`. Token check on every route except `/health` (health returns `{"ok":true}` only). |
| `OverlayController` | Builds the `WindowManager` overlay, hosts `PlayerView`/`SurfaceView`, applies position/size, runs the auto-hide timer, handles fade in/out and tap-to-dismiss. |
| `PlayerFactory` | Builds a shared ExoPlayer with HLS + RTSP + progressive support, muted by default, tuned for fast start (`minBufferMs=1000`, `bufferForPlaybackMs=500`, `bufferForPlaybackAfterRebufferMs=800`). |
| `Prefs` | SharedPreferences wrapper: port, token, default position, default width/height %, default duration, default mute, service enabled. |
| `BootReceiver` | Starts `OverlayService` on `BOOT_COMPLETED` / `QUICKBOOT_POWERON` when the service is enabled. |

## HTTP contract

All bodies are JSON, UTF-8. Auth header: `X-Auth-Token: <token>` (also accepted as `?token=`).

### `POST /show`

```json
{
  "url": "rtsp://user:pass@camera.example.com:554/stream1",
  "title": "Front Door",
  "subtitle": "Person · 7:42 PM",
  "position": "bottom-right",
  "width_pct": 38,
  "height_pct": 38,
  "duration_s": 40,
  "mute": true
}
```

- `url` (required): RTSP / HLS / MP4. `http(s)` and `rtsp(s)` accepted.
- `position`: `top-left` | `top-right` | `bottom-left` | `bottom-right` | `center`. Default from prefs.
- `width_pct` / `height_pct`: 15–100, percent of screen. Default 38.
- `duration_s`: 5–600, 0 = stay until `/hide`. Default 40.
- `mute`: default true.
- A second `/show` while visible replaces the current stream instead of stacking windows.
- Response `200 {"ok":true,"playing":true}`; `400` on bad body/url; `401` on bad token.

### `POST /hide`

Empty body or `{}`. Response `200 {"ok":true}`. No-op if nothing is showing.

### `GET /health`

`200 {"ok":true,"app":"gtv-live-stream","version":"0.1.0","playing":false,"port":8765}`

## Overlay design

- Rounded 16dp card, black `#0B0F14` at 92% opacity, 2dp accent border `#34D399`.
- Header row: 8dp live dot (`#EF4444`), title in 14sp medium white, subtitle 11sp `#94A3B8`,
  right-aligned clock.
- 16:9 `PlayerView` (use `SurfaceView`, no controller UI), letterboxed, `resize_mode=fit`.
- 12dp padding, 8dp elevation, fade in 150ms / fade out 200ms.
- `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN`, gravity per request with
  24dp margins. Tap outside the video closes it; tap on the video toggles mute.
- Never request audio focus (muted by default); if `mute=false`, request focus normally.

## Settings screen

Single scrollable screen, D-pad friendly, dark theme (`#0B0F14` background, `#34D399` accents):

- Service status (running / stopped), device IP and port.
- Toggle: start on boot.
- Token field (auto-generated 24-hex on first run) with copy + regenerate buttons.
- Port field (default 8765).
- Defaults: position dropdown, width %, height %, duration, mute.
- Buttons: Grant overlay permission (opens `ACTION_MANAGE_OVERLAY_PERMISSION`), Start service,
  Stop service, Send test overlay (posts to itself with a bundled sample HLS URL).
- Read-only `rest_command` YAML block with the token filled in, selectable.

## Build

- Kotlin, Gradle Kotlin DSL, AGP 8.5.x, Kotlin 1.9.24, JDK 17.
- `compileSdk 35`, `minSdk 23`, `targetSdk 34`.
- Dependencies: `androidx.media3:media3-exoplayer`, `media3-exoplayer-hls`, `media3-exoplayer-rtsp`,
  `media3-ui`, `org.nanohttpd:nanohttpd:2.3.1`, `androidx.core:core-ktx`, `androidx.appcompat:appcompat`.
- No Compose in the MVP (keeps the build small and fast).
- Output: `app/build/outputs/apk/debug/app-debug.apk`, installed via
  `adb connect <tv-ip>:5555 && adb install -r app-debug.apk`.

## Definition of done (MVP)

- `./gradlew assembleDebug` succeeds with no warnings that break the build.
- Unit tests cover the request parser (valid payload, missing url, bad token, clamped percentages).
- `GET /health` answers over the LAN with the app running on a TV.
- A `/show` with an RTSP camera sub-stream paints a corner window over the launcher and
  auto-dismisses; a `/show` with an HLS URL does the same.
- Killing the TV app process and rebooting the TV brings the service back.
- README documents install, HA `rest_command`, and the DRM/HDMI limitations.
