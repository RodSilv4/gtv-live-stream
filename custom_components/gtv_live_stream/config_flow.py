"""Config flow for GTV Live Stream."""

from __future__ import annotations

import logging
from typing import Any

import probatio

from homeassistant.config_entries import (
    ConfigEntry,
    ConfigFlow,
    ConfigFlowResult,
    OptionsFlowWithReload,
)
from homeassistant.const import CONF_HOST, CONF_NAME, CONF_PORT
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .client import GtvAuthError, GtvCannotConnect, GtvClient, GtvError
from .const import CONF_TOKEN, DEFAULT_PORT, DEFAULT_TIMEOUT, DOMAIN

_LOGGER = logging.getLogger(__name__)

USER_SCHEMA = probatio.Schema(
    {
        probatio.Required(CONF_HOST): str,
        probatio.Optional(CONF_NAME, default=""): str,
        probatio.Optional(CONF_PORT, default=DEFAULT_PORT): probatio.All(
            probatio.Coerce(int), probatio.Range(min=1, max=65535)
        ),
        probatio.Required(CONF_TOKEN): str,
    }
)


class CannotConnect(Exception):
    """Cannot reach the receiver."""


class InvalidAuth(Exception):
    """Token rejected."""


async def _async_validate(
    hass: HomeAssistant, host: str, port: int, token: str
) -> dict[str, Any]:
    """Check the TV answers and accepts the token (hide is a safe no-op)."""
    client = GtvClient(
        async_get_clientsession(hass),
        host,
        port,
        token,
        DEFAULT_TIMEOUT,
    )
    try:
        info = await client.health()
    except (GtvCannotConnect, GtvError) as err:
        raise CannotConnect(str(err)) from err

    try:
        await client.hide()
    except GtvAuthError as err:
        raise InvalidAuth("token rejected") from err
    except (GtvCannotConnect, GtvError) as err:
        raise CannotConnect(str(err)) from err
    return info


class GtvLiveStreamConfigFlow(ConfigFlow, domain=DOMAIN):
    """Add one TV."""

    VERSION = 1

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            host = user_input[CONF_HOST].strip()
            port = int(user_input[CONF_PORT])
            token = user_input[CONF_TOKEN].strip()
            try:
                await _async_validate(self.hass, host, port, token)
            except CannotConnect:
                errors["base"] = "cannot_connect"
            except InvalidAuth:
                errors["base"] = "invalid_auth"
            except Exception:  # noqa: BLE001 - surfaced as a form error
                _LOGGER.exception("Unexpected error validating GTV Live Stream")
                errors["base"] = "unknown"
            else:
                await self.async_set_unique_id(f"{host}:{port}")
                self._abort_if_unique_id_configured()
                name = (user_input.get(CONF_NAME) or "").strip()
                return self.async_create_entry(
                    title=name or f"GTV Live Stream ({host})",
                    data={CONF_HOST: host, CONF_PORT: port, CONF_TOKEN: token},
                )

        return self.async_show_form(
            step_id="user",
            data_schema=USER_SCHEMA,
            errors=errors,
        )

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlowWithReload:
        return GtvLiveStreamOptionsFlow()


class GtvLiveStreamOptionsFlow(OptionsFlowWithReload):
    """Change port or token; the entry reloads automatically."""

    async def async_step_init(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        entry = self.config_entry
        current_port = entry.options.get(CONF_PORT, entry.data[CONF_PORT])
        current_token = entry.options.get(CONF_TOKEN, entry.data[CONF_TOKEN])

        if user_input is not None:
            port = int(user_input[CONF_PORT])
            token = user_input[CONF_TOKEN].strip()
            try:
                await _async_validate(self.hass, entry.data[CONF_HOST], port, token)
            except CannotConnect:
                errors["base"] = "cannot_connect"
            except InvalidAuth:
                errors["base"] = "invalid_auth"
            else:
                return self.async_create_entry(
                    data={CONF_PORT: port, CONF_TOKEN: token}
                )

        return self.async_show_form(
            step_id="init",
            data_schema=probatio.Schema(
                {
                    probatio.Optional(CONF_PORT, default=current_port): probatio.All(
                        probatio.Coerce(int), probatio.Range(min=1, max=65535)
                    ),
                    probatio.Optional(CONF_TOKEN, default=current_token): str,
                }
            ),
            errors=errors,
        )
