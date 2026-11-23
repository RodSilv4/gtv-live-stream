# gtv-live-stream

Small floating **live-video popups** on Android TV / Google TV, driven over the LAN by anything
that can send an HTTP request.

```
your automation ──POST /show──▶ gtv-live-stream (TV) ──▶ 38% corner window, live RTSP/HLS
                              ◀── GET /health ────────
```

The app has no cloud service, no account, and no dependency on a particular smart-home platform.
It listens on a port, plays the stream URL it is given, and takes the window away when told to.
Home Assistant, Node-RED, a cron job with `curl`, or your own script all work identically.

## Why

Platform notifications on Android TV can only carry a still image, and Casting a dashboard takes
over the whole screen and cannot render live camera streams. An overlay window can do what neither
can — and this one is not tied to one vendor's ecosystem.

## Status

MVP: `assembleDebug` and the 12 parser unit tests pass in CI. See
[`docs/SPEC.md`](docs/SPEC.md) for the HTTP contract.

## Install

Sideload the APK from [Releases](https://github.com/RodSilv4/gtv-live-stream/releases), or build it
yourself:

```bash
./gradlew assembleDebug
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the TV: open **GTV Live Stream**, tap **Grant overlay permission** and allow *Display over other
apps*, then **Start service**. The screen shows the device IP, port and token.

## Use it

```bash
curl -s http://<tv-ip>:8765/health

curl -s -X POST http://<tv-ip>:8765/show \
  -H "X-Auth-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{
        "url": "rtsp://user:pass@camera.example.com:554/stream1",
        "title": "Front Door",
        "subtitle": "Person · 7:42 PM",
        "position": "bottom-right",
        "width_pct": 38,
        "duration_s": 40,
        "mute": true
      }'

curl -s -X POST http://<tv-ip>:8765/hide -H "X-Auth-Token: $TOKEN"
```

`/show` replaces any window already on screen; `duration_s: 0` keeps it until `/hide`. Sources:
RTSP, HLS (`.m3u8`) and progressive MP4.

[`docs/INTEGRATIONS.md`](docs/INTEGRATIONS.md) has a Home Assistant `rest_command` example plus
other clients. The app itself never talks to Home Assistant.

## Limitations

- Overlays cannot draw over DRM-protected playback (Netflix, Prime Video, Disney+): the system
  blacks out third-party windows there.
- HDMI inputs bypass Android entirely — no overlay can appear over a console or cable box.
- Streams are not transcoded; use a TV-decodable profile (H.264, ≤1080p).

## Build

JDK 17 + Android SDK (compileSdk 34). `./gradlew assembleDebug`, `./gradlew testDebugUnitTest`.
Release/AAB signing: [`docs/BUILD.md`](docs/BUILD.md).

## License

MIT — see [LICENSE](LICENSE).
