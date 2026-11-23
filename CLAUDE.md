# gtv-live-stream — project context

Android TV companion app: HTTP-driven overlay that plays a live RTSP/HLS/MP4 stream in a small
corner window. Read `docs/SPEC.md` first; it is the contract.

## Hard rules

- Kotlin + plain Android Views. **No Compose** in the MVP.
- Package `to.silva.gtvlivestream`. App label `GTV Live Stream`.
- The overlay is `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`, and must never take D-pad focus.
- Every request except `GET /health` requires `X-Auth-Token` matching the stored token.
- Keep dependencies to: media3 (exoplayer, exoplayer-hls, exoplayer-rtsp, ui), nanohttpd 2.3.1,
  androidx.core-ktx, androidx.appcompat. Nothing else without a note in the PR.
- `compileSdk 35`, `minSdk 23`, `targetSdk 34`, JDK 17, AGP 8.5.x, Kotlin 1.9.24.
- Do not hardcode camera credentials or LAN IPs in source. All hosts/URLs arrive in the request
  body or are entered on the settings screen.
- `./gradlew assembleDebug` must pass; unit tests for the request parser must pass.

## Layout

```
app/src/main/java/to/silva/gtvlivestream/
  MainActivity.kt        settings + status
  OverlayService.kt      foreground service, owns HTTP + overlay
  HttpApiServer.kt       NanoHTTPD routes, token auth
  ShowRequest.kt         payload parsing + clamping (unit tested)
  OverlayController.kt   WindowManager overlay + auto-hide
  PlayerFactory.kt       ExoPlayer setup (HLS/RTSP/progressive, fast start)
  Prefs.kt               SharedPreferences wrapper
  BootReceiver.kt        restart service on boot
app/src/test/java/...    parser unit tests
docs/SPEC.md             the contract
docs/INTEGRATIONS.md     client examples (Home Assistant, curl, Node-RED)
docs/BUILD.md            debug/release signing
docs/PLAY.md             Play Store checklist
store/                   listing copy + icons/feature graphic/banner
PRIVACY.md               Play data-safety source of truth
```

## Style

- 4-space indent, explicit types on public APIs, no wildcard imports.
- All user-visible strings in `res/values/strings.xml`.
- Log with a single `TAG` per class; never log the token or full stream URLs (they carry creds).
