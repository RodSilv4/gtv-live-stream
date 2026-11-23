"""Binary sensors for GTV Live Stream."""

from __future__ import annotations

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import DOMAIN
from .coordinator import GtvCoordinator
from .entity import GtvEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    coordinator: GtvCoordinator = hass.data[DOMAIN][entry.entry_id]
    async_add_entities(
        [
            GtvOnlineSensor(coordinator, entry),
            GtvPlayingSensor(coordinator, entry),
        ]
    )


class GtvOnlineSensor(GtvEntity, BinarySensorEntity):
    """True while the receiver answers /health."""

    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY
    _attr_translation_key = "online"

    def __init__(self, coordinator: GtvCoordinator, entry: ConfigEntry) -> None:
        super().__init__(coordinator, entry)
        self._attr_unique_id = f"{entry.entry_id}_online"

    @property
    def is_on(self) -> bool:
        return self.coordinator.last_update_success


class GtvPlayingSensor(GtvEntity, BinarySensorEntity):
    """True while an overlay window is on screen."""

    _attr_translation_key = "playing"
    _attr_icon = "mdi:play-box-multiple-outline"

    def __init__(self, coordinator: GtvCoordinator, entry: ConfigEntry) -> None:
        super().__init__(coordinator, entry)
        self._attr_unique_id = f"{entry.entry_id}_playing"

    @property
    def is_on(self) -> bool:
        return bool((self.coordinator.data or {}).get("playing"))

    @property
    def extra_state_attributes(self) -> dict[str, object]:
        data = self.coordinator.data or {}
        return {
            "overlay_permission": data.get("overlayPermission"),
            "port": data.get("port"),
            "version": data.get("version"),
        }
