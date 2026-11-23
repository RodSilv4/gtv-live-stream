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
