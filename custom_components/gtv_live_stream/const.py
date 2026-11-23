"""Constants for the GTV Live Stream integration."""

DOMAIN = "gtv_live_stream"

DEFAULT_PORT = 8765
DEFAULT_TIMEOUT = 10

CONF_TOKEN = "token"

POSITIONS = ["top-left", "top-right", "bottom-left", "bottom-right", "center"]

PLATFORMS = ["binary_sensor"]

SERVICE_SHOW = "show"
SERVICE_HIDE = "hide"

ATTR_URL = "url"
ATTR_CAMERA = "camera"
ATTR_TITLE = "title"
ATTR_SUBTITLE = "subtitle"
ATTR_POSITION = "position"
ATTR_WIDTH_PCT = "width_pct"
ATTR_HEIGHT_PCT = "height_pct"
ATTR_DURATION_S = "duration_s"
ATTR_MUTE = "mute"

DEFAULT_WIDTH_PCT = 38
DEFAULT_HEIGHT_PCT = 38
DEFAULT_DURATION_S = 40
DEFAULT_MUTE = True
DEFAULT_TITLE = "Live view"
