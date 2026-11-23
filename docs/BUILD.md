# Build & release

## Toolchain

- JDK 17
- Android SDK with `platforms;android-34` and `build-tools;34.0.0`
- Gradle wrapper (8.9) — no local Gradle install needed

Point Gradle at the SDK with `local.properties` (gitignored):

```properties
sdk.dir=/path/to/android-sdk
```

## Debug build

```bash
./gradlew assembleDebug testDebugUnitTest
# app/build/outputs/apk/debug/app-debug.apk
```

## Release build

Release builds are signed from `keystore.properties` at the repo root (gitignored). Create a key
once and keep the file out of version control:

```bash
keytool -genkeypair -v \
  -keystore gtv-live-stream.jks \
  -alias gtv \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=GTV Live Stream, O=GTV Live Stream, C=CA"
```

```properties
# keystore.properties
storeFile=/absolute/path/to/gtv-live-stream.jks
storePassword=…
keyAlias=gtv
keyPassword=…
```

Build:

```bash
./gradlew assembleRelease   # APK for sideloading
./gradlew bundleRelease     # AAB for Google Play
```

Without `keystore.properties`, release builds fall back to the debug key so the build still runs;
those artifacts are **not** suitable for distribution.

## Versioning

`versionCode` and `versionName` live in `app/build.gradle.kts`. Bump both for every Play upload;
`versionCode` must increase monotonically.

## Releases and the stable download URL

The TV's Downloader app (and anything else) should point at:

```
https://github.com/RodSilv4/gtv-live-stream/releases/latest/download/gtv-live-stream.apk
```

That URL is stable because GitHub resolves `releases/latest/download/<name>` to the newest
non-draft, non-prerelease release that carries an asset with that exact filename. The release
workflow (`.github/workflows/release.yml`) enforces it:

- triggered by pushing a `v*` tag;
- builds `assembleRelease`;
- uploads `gtv-live-stream.apk` (fixed name, `--clobber`) and a versioned copy
  `gtv-live-stream-<version>.apk`.

If you create a release by hand, it **must** include an asset named exactly
`gtv-live-stream.apk`, and it must not be marked prerelease.

For a friendlier thing to type on a TV remote, add a redirect in front of it (e.g. an NPM proxy
host for `gtv.silva.to` returning `302` to the URL above). Downloader's numeric "codes" are
assigned by AFTVnews, not generated per project, so they are not an option.
