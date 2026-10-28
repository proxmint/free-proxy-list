"""httpx transports that send each request through the next proxy in a pool."""

from __future__ import annotations

import httpx

from . import _Pool


class RotatingTransport(httpx.BaseTransport):
    """Sends each request through the next proxy in a pool. When a proxy fails, it
    is dropped and the request is retried through another, up to `tries` proxies.

        client = httpx.Client(transport=RotatingTransport())

    proxies: your own proxy URLs, credentials included if they need them (socks5://
    needs `httpx[socks]`). Without them the pool is the Proxmint free list, filtered
    by `filters` (see `proxmint.free_proxies`; protocol defaults to "http") and
    fetched again whenever it runs dry.
    """

    def __init__(self, proxies=None, *, tries: int = 3, **filters):
        self._pool = _Pool(proxies, filters)
        self._tries = tries
        self._transports: dict[str, httpx.HTTPTransport] = {}

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        for attempt in range(1, self._tries + 1):
            proxy = self._pool.take()
            transport = self._transports.get(proxy) or self._transports.setdefault(proxy, httpx.HTTPTransport(proxy=proxy))
            try:
                response = transport.handle_request(request)
            except httpx.TransportError:
                self._drop(proxy)
                if attempt == self._tries:
                    raise
                continue
            if response.status_code != 407 or attempt == self._tries:
                return response
            response.close()  # the proxy wants credentials it was not given
            self._drop(proxy)
        raise AssertionError("unreachable")

    def _drop(self, proxy: str) -> None:
        self._pool.drop(proxy)
        if (transport := self._transports.pop(proxy, None)) is not None:
            transport.close()

    def close(self) -> None:
        for transport in self._transports.values():
            transport.close()
        self._transports.clear()


class AsyncRotatingTransport(httpx.AsyncBaseTransport):
    """`RotatingTransport` for `httpx.AsyncClient`."""

    def __init__(self, proxies=None, *, tries: int = 3, **filters):
        self._pool = _Pool(proxies, filters)
        self._tries = tries
        self._transports: dict[str, httpx.AsyncHTTPTransport] = {}

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        for attempt in range(1, self._tries + 1):
            proxy = await self._pool.take_async()
            transport = self._transports.get(proxy) or self._transports.setdefault(proxy, httpx.AsyncHTTPTransport(proxy=proxy))
            try:
                response = await transport.handle_async_request(request)
            except httpx.TransportError:
                await self._drop(proxy)
                if attempt == self._tries:
                    raise
                continue
            if response.status_code != 407 or attempt == self._tries:
                return response
            await response.aclose()
            await self._drop(proxy)
        raise AssertionError("unreachable")

    async def _drop(self, proxy: str) -> None:
        self._pool.drop(proxy)
        if (transport := self._transports.pop(proxy, None)) is not None:
            await transport.aclose()

    async def aclose(self) -> None:
        for transport in self._transports.values():
            await transport.aclose()
        self._transports.clear()
