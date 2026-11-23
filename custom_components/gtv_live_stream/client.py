"""Small aiohttp client for the GTV Live Stream TV receiver."""

from __future__ import annotations

import asyncio
from typing import Any

import aiohttp


class GtvError(Exception):
    """Base error for the receiver."""


class GtvCannotConnect(GtvError):
    """Raised when the TV cannot be reached."""


class GtvAuthError(GtvError):
    """Raised when the TV rejects the token."""


class GtvClient:
    """HTTP client for one TV."""

    def __init__(
        self,
        session: aiohttp.ClientSession,
        host: str,
        port: int,
        token: str,
        timeout: int = 10,
    ) -> None:
        self._session = session
        self._base_url = f"http://{host}:{port}"
        self._token = token
        self._timeout = aiohttp.ClientTimeout(total=timeout)

    async def health(self) -> dict[str, Any]:
        """Return the unauthenticated /health payload."""
        return await self._request("GET", "/health", authenticated=False)

    async def show(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Show an overlay window."""
        return await self._request("POST", "/show", payload=payload)

    async def hide(self) -> dict[str, Any]:
        """Hide the overlay window."""
        return await self._request("POST", "/hide")

    async def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        authenticated: bool = True,
    ) -> dict[str, Any]:
        headers: dict[str, str] = {}
        if authenticated:
            headers["X-Auth-Token"] = self._token

        try:
            async with self._session.request(
                method,
                f"{self._base_url}{path}",
                json=payload,
                headers=headers,
                timeout=self._timeout,
            ) as response:
                if response.status == 401:
                    raise GtvAuthError("token rejected")
                if response.status >= 400:
                    body = await response.text()
                    raise GtvError(f"HTTP {response.status}: {body[:200]}")
                return await response.json(content_type=None)
        except (aiohttp.ClientError, asyncio.TimeoutError) as err:
            raise GtvCannotConnect(str(err) or err.__class__.__name__) from err
