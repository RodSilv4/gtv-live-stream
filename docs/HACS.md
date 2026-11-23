# Home Assistant integration (HACS)

The same repo ships a custom integration so Home Assistant can talk to the TV directly — no
`rest_command`, no YAML, and every TV shows up as a device with its own entities.

## What it gives you

- **Device per TV** (`Master Bedroom TV`, `Living Room TV`, …) created from the config flow.
- **`binary_sensor.<tv>_online`** — the receiver answers `/health`.
- **`binary_sensor.<tv>_playing`** — an overlay is currently on screen (attributes: version, port,
  overlay permission).
- **`gtv_live_stream.show`** — target a device and pass `url`, `title`, `subtitle`, `position`,
  `width_pct`, `height_pct`, `duration_s`, `mute`.
- **`gtv_live_stream.hide`** — target a device.

## Install

1. The repo must be reachable by HACS: either make it public, or add a GitHub token in HACS
   (HACS → Integrations → ⋮ → *Custom repositories* still needs to clone it).
2. HACS → **Integrations** → ⋮ → **Custom repositories** → paste
   `https://github.com/RodSilv4/gtv-live-stream`, category **Integration**.
3. Install **GTV Live Stream**, then restart Home Assistant.
4. **Settings → Devices & Services → Add Integration → GTV Live Stream**.
   Enter the TV's IP, an optional name (`Master Bedroom TV`), the port (8765) and the token shown in
   the TV app.
5. Repeat for each TV.

## Many TVs, many cameras

- **TVs**: add one integration entry per TV. `gtv_live_stream.show` targets devices; select several
  in one action to show the same camera on multiple TVs, or omit the target entirely to show on
  **every** configured TV.
- **Cameras**: pass either `camera` (a camera entity with a stream source — the entity picker in
  the automation UI) or `url` (any RTSP/HLS/MP4). `camera` is resolved to its stream URL, so one
  service call works for every camera you have.

```yaml
# Whole house, camera entity
- action: gtv_live_stream.show
  data:
    camera: camera.front_door
    title: Front Door
    duration_s: 40

# Two specific TVs
- action: gtv_live_stream.show
  target:
    device_id:
      - 0123456789abcdef
      - fedcba9876543210
  data:
    camera: camera.front_door
```

Frigate's HA integration currently exposes no stream source for its cameras, so `camera:` on those
entities raises a clear error. Point a **Generic Camera** at the go2rtc restream
(`rtsp://<frigate-host>:8554/front_cam_sub`) once per camera; then every camera is selectable by
entity and HA gets working live view as a bonus.

## Use it

```yaml
automation:
  - alias: "Front Door: TV popup"
    triggers:
      - trigger: state
        entity_id: binary_sensor.front_cam_person_occupancy
        to: "on"
    actions:
      - action: gtv_live_stream.show
        target:
          device_id: 0123456789abcdef
        data:
          url: "rtsp://192.168.68.12:8554/front_cam_sub"
          title: "Front Door"
          subtitle: "Person detected"
          duration_s: 40
```

`target` also accepts `entity_id: binary_sensor.master_bedroom_tv_playing`. With a single TV
configured, the target can be omitted.

## Notes

- The integration polls `/health` every 30 s; a sleeping TV simply shows `offline` and recovers.
- `show`/`hide` talk to the TV immediately and refresh the entities afterwards.
- Tokens are per TV and can be changed in the integration's **Configure** dialog.

## Replacing the rest_command

Once the integration is installed and a TV is added, delete the `gtv_live_popup` block from
`configuration.yaml` and change your automations to call `gtv_live_stream.show` with a device
target.
