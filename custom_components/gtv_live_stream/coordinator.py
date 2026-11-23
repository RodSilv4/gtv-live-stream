"""Data coordinator for GTV Live Stream."""

from __future__ import annotations

import logging
from datetime import timedelta
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .client import GtvCannotConnect, GtvClient, GtvError
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

SCAN_INTERVAL = timedelta(seconds=30)


class GtvCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Polls /health so entities reflect the receiver state."""

    config_entry: ConfigEntry

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry, client: GtvClient) -> None:
        super().__init__(
            hass,
            _LOGGER,
            name=f"{DOMAIN}-{entry.entry_id}",
            update_interval=SCAN_INTERVAL,
        )
        self.client = client
        self.config_entry = entry

    async def _async_update_data(self) -> dict[str, Any]:
        try:
            return await self.client.health()
        except (GtvCannotConnect, GtvError) as err:
            raise UpdateFailed(str(err)) from err
