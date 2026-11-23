# Publishing to Google Play (Android TV)

The app is already shaped for Play: TV launcher intent, `android.software.leanback` required, no
touchscreen requirement, no ads, no data collection. What is left is console work and signing.

## 1. Prerequisites

- A Google Play developer account (one-time US$25).
- `keystore.properties` + upload key (see `docs/BUILD.md`). Keep the keystore and its passwords
  backed up — losing it means losing the ability to update the app.
- A public privacy policy URL. `PRIVACY.md` in this repo can be hosted as-is (GitHub Pages, a
  gist, or any static host).
- Release AAB: `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.

## 2. App identity (decide before the first upload)

`applicationId` in `app/build.gradle.kts` is **permanent** once the first AAB is uploaded. The
current value is `to.silva.gtvlivestream`. If the app will ship under a different brand, change the
namespace/applicationId now — after the first upload it cannot be changed.

## 3. Store listing assets

| Asset | Requirement |
|---|---|
| App icon | 512×512 PNG, 32-bit, no alpha |
| Feature graphic | 1024×500 PNG/JPEG |
| TV banner | 320×180 PNG/JPEG (required for Android TV) |
| TV screenshots | 2–8, 1920×1080 (16:9) |
| Short description | ≤80 characters |
| Full description | ≤4000 characters |

Copy for the listing is in `store/listing.md`; generated graphics are in `store/`.

## 4. Console steps

1. **Create app** → name, default language, "App", "Free".
2. **App content**: privacy policy URL; Ads = No; Data safety = *no data collected or shared*;
   content rating questionnaire (utility/productivity, no user-generated content); target audience
   13+ (TV app, no children's content).
3. **Android TV**: Play requires the TV form factor to be opted in — *Advanced settings → Form
   factors → Android TV*. The manifest already declares the leanback launcher.
4. **Production → Create release** → upload the AAB → release notes → rollout.
5. **Store settings**: category (Tools or Video Players & Editors), contact email, tags.

## 5. Review notes for the reviewer

Include in "Notes for the review team":

> This is a LAN utility. The app listens on a local HTTP port and displays a video overlay window
> when an authorised request arrives. No account or login is required. To test: launch the app,
> grant "Display over other apps", press "Start service", then POST a test payload to
> `http://<device-ip>:8765/show` (a sample URL is built into the Test button). The app contains no
> ads and collects no data.

## 6. Alternatives while waiting for review

- **Direct APK**: attach `app-release.apk` to a GitHub release; sideload over ADB or with the TV's
  Downloader app.
- **Closed testing**: Play internal testing track gives friends a normal Play install link before
  production.

## 7. After the first release

`versionCode` must increase for every upload. Tag the release in git and attach the same APK so
sideloaders and Play users run identical builds.
