# Privacy policy

**gtv-live-stream** is a local-network application. It does not collect, transmit, or sell any data.

## What the app stores on the device

- A random access token generated on first launch.
- The port, overlay defaults (position, size, duration, mute) and the start-on-boot preference.

These values stay in the app's private storage on the TV. There is no account, no analytics SDK, no
advertising identifier, and no crash reporting service.

## Network activity

The app listens for HTTP requests on the local network and plays the media URL contained in those
requests. It makes no outbound connections except to the stream URL it is told to play. That URL is
chosen by whoever operates the app; this project has no servers and receives no data.

## Permissions

- `INTERNET` — reach the stream URL and serve the local HTTP API.
- `SYSTEM_ALERT_WINDOW` — draw the overlay window.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — keep the receiver running while the
  overlay plays.
- `RECEIVE_BOOT_COMPLETED` — restart the receiver after a reboot when enabled.
- `POST_NOTIFICATIONS` — show the required foreground-service notification.

## Removing data

Uninstalling the app deletes everything it stored. There is no server-side data to remove.

## Contact

Open an issue at https://github.com/RodSilv4/gtv-live-stream/issues.
