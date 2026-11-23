"""Base entity for GTV Live Stream."""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN
from .coordinator import GtvCoordinator


class GtvEntity(CoordinatorEntity[GtvCoordinator]):
    """Common device metadata for the receiver."""

    _attr_has_entity_name = True

    def __init__(self, coordinator: GtvCoordinator, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._entry = entry
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            name=entry.title,
            manufacturer="GTV Live Stream",
            model="Android TV overlay receiver",
            sw_version=(coordinator.data or {}).get("version"),
            configuration_url=f"http://{entry.data['host']}:{entry.data['port']}",
        )
