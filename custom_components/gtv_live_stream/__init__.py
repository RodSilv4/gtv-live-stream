"""GTV Live Stream — Home Assistant integration.

Talks to the GTV Live Stream Android TV receiver over the LAN: shows a live
overlay window and reports whether the receiver is reachable / playing.
"""

from __future__ import annotations

import logging
from typing import Any

import probatio

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import (
    ATTR_DEVICE_ID,
    ATTR_ENTITY_ID,
    CONF_HOST,
    CONF_PORT,
    Platform,
)
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.typing import ConfigType

from .client import GtvAuthError, GtvCannotConnect, GtvClient, GtvError
from .const import (
    ATTR_DURATION_S,
    ATTR_HEIGHT_PCT,
    ATTR_MUTE,
    ATTR_POSITION,
    ATTR_SUBTITLE,
    ATTR_TITLE,
    ATTR_URL,
    ATTR_WIDTH_PCT,
    CONF_TOKEN,
    DEFAULT_TIMEOUT,
    DOMAIN,
    POSITIONS,
    SERVICE_HIDE,
    SERVICE_SHOW,
)
from .coordinator import GtvCoordinator

_LOGGER = logging.getLogger(__name__)

PLATFORMS: list[Platform] = [Platform.BINARY_SENSOR]

CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)

TARGET_FIELD = probatio.Any(str, list)

SHOW_SCHEMA = probatio.Schema(
    {
        probatio.Required(ATTR_URL): str,
        probatio.Optional(ATTR_TITLE): str,
        probatio.Optional(ATTR_SUBTITLE): str,
        probatio.Optional(ATTR_POSITION): probatio.In(POSITIONS),
        probatio.Optional(ATTR_WIDTH_PCT): probatio.All(
            probatio.Coerce(int), probatio.Range(min=15, max=100)
        ),
        probatio.Optional(ATTR_HEIGHT_PCT): probatio.All(
            probatio.Coerce(int), probatio.Range(min=15, max=100)
        ),
        probatio.Optional(ATTR_DURATION_S): probatio.All(
            probatio.Coerce(int), probatio.Range(min=0, max=600)
        ),
        probatio.Optional(ATTR_MUTE): bool,
        probatio.Optional(ATTR_DEVICE_ID): TARGET_FIELD,
        probatio.Optional(ATTR_ENTITY_ID): TARGET_FIELD,
    }
)

HIDE_SCHEMA = probatio.Schema(
    {
        probatio.Optional(ATTR_DEVICE_ID): TARGET_FIELD,
        probatio.Optional(ATTR_ENTITY_ID): TARGET_FIELD,
    }
)

PAYLOAD_FIELDS = (
    ATTR_TITLE,
    ATTR_SUBTITLE,
    ATTR_POSITION,
    ATTR_WIDTH_PCT,
    ATTR_HEIGHT_PCT,
    ATTR_DURATION_S,
    ATTR_MUTE,
)


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Register the domain services once."""
    hass.data.setdefault(DOMAIN, {})
    _async_register_services(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up one TV."""
    host = entry.data[CONF_HOST]
    port = entry.options.get(CONF_PORT, entry.data[CONF_PORT])
    token = entry.options.get(CONF_TOKEN, entry.data[CONF_TOKEN])

    client = GtvClient(
        async_get_clientsession(hass),
        host,
        port,
        token,
        DEFAULT_TIMEOUT,
    )
    coordinator = GtvCoordinator(hass, entry, client)
    # A sleeping TV must not block setup: poll once, keep the entry either way.
    await coordinator.async_refresh()

    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = coordinator
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload one TV."""
    unloaded = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unloaded:
        hass.data[DOMAIN].pop(entry.entry_id, None)
    return unloaded


async def _async_update_listener(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Reload the entry (kept for callers that prefer the listener pattern)."""
    await hass.config_entries.async_reload(entry.entry_id)


def _async_register_services(hass: HomeAssistant) -> None:
    if hass.services.has_service(DOMAIN, SERVICE_SHOW):
        return

    async def async_show(call: ServiceCall) -> None:
        payload: dict[str, Any] = {ATTR_URL: call.data[ATTR_URL]}
        for field in PAYLOAD_FIELDS:
            if field in call.data and call.data[field] is not None:
                payload[field] = call.data[field]

        for coordinator in _coordinators_for_call(hass, call):
            try:
                await coordinator.client.show(payload)
            except GtvAuthError as err:
                raise HomeAssistantError(
                    "The TV rejected the token. Update the integration options."
                ) from err
            except (GtvCannotConnect, GtvError) as err:
                raise HomeAssistantError(f"Could not show the overlay: {err}") from err
            await coordinator.async_request_refresh()

    async def async_hide(call: ServiceCall) -> None:
        for coordinator in _coordinators_for_call(hass, call):
            try:
                await coordinator.client.hide()
            except GtvAuthError as err:
                raise HomeAssistantError(
                    "The TV rejected the token. Update the integration options."
                ) from err
            except (GtvCannotConnect, GtvError) as err:
                raise HomeAssistantError(f"Could not hide the overlay: {err}") from err
            await coordinator.async_request_refresh()

    hass.services.async_register(DOMAIN, SERVICE_SHOW, async_show, schema=SHOW_SCHEMA)
    hass.services.async_register(DOMAIN, SERVICE_HIDE, async_hide, schema=HIDE_SCHEMA)


def _coordinators_for_call(hass: HomeAssistant, call: ServiceCall) -> list[GtvCoordinator]:
    """Resolve the TVs a service call targets."""
    entry_ids: set[str] = set()

    device_ids = call.data.get(ATTR_DEVICE_ID) or []
    entity_ids = call.data.get(ATTR_ENTITY_ID) or []
    if isinstance(device_ids, str):
        device_ids = [device_ids]
    if isinstance(entity_ids, str):
        entity_ids = [entity_ids]

    device_registry = dr.async_get(hass)
    entity_registry = er.async_get(hass)

    for device_id in device_ids:
        device = device_registry.async_get(device_id)
        if device is not None:
            entry_ids.update(device.config_entries)

    for entity_id in entity_ids:
        entity_entry = entity_registry.async_get(entity_id)
        if entity_entry is not None and entity_entry.config_entry_id is not None:
            entry_ids.add(entity_entry.config_entry_id)

    entries = [
        entry
        for entry in (hass.config_entries.async_get_entry(entry_id) for entry_id in entry_ids)
        if entry is not None and entry.domain == DOMAIN
    ]

    if not entries:
        all_entries = hass.config_entries.async_entries(DOMAIN)
        if len(all_entries) == 1:
            entries = all_entries
        else:
            raise ServiceValidationError("Target a specific TV with device_id or entity_id")

    coordinators = [hass.data[DOMAIN].get(entry.entry_id) for entry in entries]
    resolved = [coordinator for coordinator in coordinators if coordinator is not None]
    if not resolved:
        raise ServiceValidationError("The target TV is not loaded")
    return resolved


__all__ = ["async_setup", "async_setup_entry", "async_unload_entry", "CONFIG_SCHEMA"]
