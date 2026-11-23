# Integrations

The app is a plain HTTP receiver. Anything that can POST JSON can drive it; the examples below are
clients, not requirements.

All examples assume:

- `<tv-ip>` — the address shown on the app's settings screen
- `<port>` — `8765` unless changed
- `<token>` — the token shown on the settings screen

## curl

```bash
curl -s -X POST "http://<tv-ip>:<port>/show" \
  -H "X-Auth-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"url":"rtsp://user:pass@camera.example.com:554/stream1",
       "title":"Front Door","subtitle":"Person",
       "position":"bottom-right","width_pct":38,"duration_s":40}'
```

## Home Assistant

Two `rest_command`s: one to show, one to hide.

```yaml
rest_command:
  gtv_live_show:
    url: "http://<tv-ip>:<port>/show"
    method: POST
    headers:
      X-Auth-Token: !secret gtv_live_token
      Content-Type: "application/json"
    payload: >
      {"url": "{{ states('input_text.gtv_live_stream_url') }}",
       "title": "{{ title | default('Live view') }}",
       "subtitle": "{{ subtitle | default('') }}",
       "position": "bottom-right",
       "width_pct": 38,
       "height_pct": 38,
       "duration_s": 40,
       "mute": true}

  gtv_live_hide:
    url: "http://<tv-ip>:<port>/hide"
    method: POST
    headers:
      X-Auth-Token: !secret gtv_live_token
```

Example automation — show a camera when a person is detected:

```yaml
automation:
  - alias: "Camera: TV live popup"
    id: camera_tv_popup
    mode: single
    triggers:
      - trigger: state
        entity_id: binary_sensor.example_person_occupancy
        to: "on"
    actions:
      - action: rest_command.gtv_live_show
        data:
          title: Front Door
          subtitle: Person detected
```

Notes:

- `input_text.gtv_live_stream_url` is just one way to hold the stream URL; a literal URL or
  `!secret` both work.
- `/show` replaces the current window, so repeated detections do not stack.
- If your platform has a "TV is playing" signal, gate the call on it so the overlay never covers a
  movie.

## Node-RED

`http request` node → POST `http://<tv-ip>:<port>/show`, header `X-Auth-Token`, JSON body as above.

## Shell / cron

```bash
#!/bin/sh
TOKEN="<token>"
curl -fsS -X POST "http://<tv-ip>:<port>/show" \
  -H "X-Auth-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/live.m3u8","title":"Live","duration_s":30}' >/dev/null
```

## Verify

```bash
curl -s "http://<tv-ip>:<port>/health"
# {"ok":true,"app":"gtv-live-stream","version":"0.1.0","playing":false,"port":8765}
```
