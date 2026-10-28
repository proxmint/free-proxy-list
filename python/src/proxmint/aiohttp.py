"""aiohttp client middleware that sends each request through the next proxy in a pool."""

from __future__ import annotations

import asyncio

import aiohttp
from yarl import URL

from . import _Pool

# What a dead, blocked or tampering proxy looks like from aiohttp's side.
PROXY_FAILURES = (aiohttp.ClientConnectionError, aiohttp.ClientHttpProxyError, asyncio.TimeoutError)


class RotatingProxy:
    """Client middleware (aiohttp 3.12+) that sends each request through the next
    proxy in a pool. When a proxy fails, it is dropped and the request is retried
    through another, up to `tries` proxies. Each proxy gets `timeout` seconds to
    connect and answer with headers; a session-wide ClientTimeout(total=...) would
    cover all the tries together, so one hanging proxy could use it up.

        async with aiohttp.ClientSession(middlewares=(RotatingProxy(),)) as session:
            async with session.get("https://example.com") as resp: ...

    proxies: your own http:// proxy URLs, credentials included if they need them
    (aiohttp has no SOCKS support). Without them the pool is the Proxmint free list,
    filtered by `filters` (see `proxmint.free_proxies`; protocol defaults to "http")
    and fetched again whenever it runs dry.
    """

    def __init__(self, proxies=None, *, tries: int = 3, timeout: float | None = 10, **filters):
        self._pool = _Pool(proxies, filters)
        self._tries = tries
        self._timeout = timeout

    async def __call__(self, req: aiohttp.ClientRequest, handler) -> aiohttp.ClientResponse:
        for attempt in range(1, self._tries + 1):
            proxy = await self._pool.take_async()
            req.update_proxy(URL(proxy), None, req.proxy_headers)
            try:
                resp = await asyncio.wait_for(handler(req), self._timeout)
            except PROXY_FAILURES:
                self._pool.drop(proxy)
                if attempt == self._tries:
                    raise
                continue
            if resp.status != 407 or attempt == self._tries:
                return resp
            resp.release()  # the proxy wants credentials it was not given
            self._pool.drop(proxy)
        raise AssertionError("unreachable")
