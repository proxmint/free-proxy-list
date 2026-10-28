"""Client for the Proxmint free proxy list, plus proxy rotation for aiohttp and httpx.

Every proxy the API returns was proven alive by a real request made through it
within the last 30 minutes: https://proxmint.com/free-proxies. No key, no signup.
"""

from __future__ import annotations

import asyncio
import json
import threading
import urllib.error
import urllib.parse
import urllib.request

__all__ = ["ProxmintError", "free_proxies", "proxy_url", "proxy_urls"]
__version__ = "0.1.0"

API = "https://proxmint.com/api/free-proxies"
MAX_PAGE_SIZE = 200  # the server caps a page here, so larger requests are paged
UA = f"proxmint-python/{__version__} (+https://proxmint.com/free-proxies)"


class ProxmintError(Exception):
    """The API refused or could not be reached; `status` is the HTTP status, if any."""

    def __init__(self, message: str, status: int | None = None):
        super().__init__(message)
        self.status = status


def free_proxies(
    protocol: str | None = None,
    country: str | None = None,
    anonymity: str | None = None,
    sort: str | None = None,
    limit: int = 20,
    timeout: float = 30,
) -> list[dict]:
    """Live proxies, best first, as dicts with ip, port, protocol, countryCode,
    countryName, anonymity, latencyMs, uptimePct, score and lastCheckedAt.

    protocol: "http", "https", "socks4" or "socks5". country: ISO 3166-1 alpha-2,
    such as "de". anonymity: "elite", "anonymous" or "transparent". sort: "score"
    (the default), "latency", "uptime" or "checked". limit is paged past the API's
    200-row cap.
    """
    if not isinstance(limit, int) or isinstance(limit, bool) or limit < 1:
        raise ValueError(f"limit must be a positive integer, got {limit!r}")
    out: list[dict] = []
    page = 1
    while len(out) < limit:
        query = {
            "protocol": protocol,
            "country": country.lower() if country else None,
            "anonymity": anonymity,
            "sort": sort,
            "page": page,
            "pageSize": min(limit - len(out), MAX_PAGE_SIZE),
        }
        url = f"{API}?{urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})}"
        req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code == 429:
                raise ProxmintError("rate limited: the API allows 60 requests a minute per IP", 429) from None
            raise ProxmintError(f"Proxmint API returned {e.code} {e.reason}", e.code) from None
        except (urllib.error.URLError, TimeoutError) as e:
            raise ProxmintError(f"could not reach the Proxmint API: {e}") from None
        rows = body.get("rows") or []
        out.extend(rows)
        # A short page means the pool ended, not that the caller got what they asked for.
        if not rows or len(out) >= (body.get("total") or 0):
            break
        page += 1
    return out[:limit]


def proxy_url(proxy: dict) -> str:
    """The URL a client connects to for one row. An "https" proxy is an HTTP proxy
    that can tunnel TLS with CONNECT, so it is reached over http://; an https:// URL
    would make the client attempt TLS to the proxy itself."""
    scheme = "http" if proxy["protocol"] == "https" else proxy["protocol"]
    return f"{scheme}://{proxy['ip']}:{proxy['port']}"


def proxy_urls(**filters) -> list[str]:
    """`free_proxies(**filters)` as proxy URLs, best first."""
    return [proxy_url(p) for p in free_proxies(**filters)]


class _Pool:
    """Round-robin over proxy URLs, shared by the aiohttp and httpx rotators. A proxy
    that fails is dropped; an empty pool refills from the caller's list or, without
    one, from a fresh fetch of the free list."""

    def __init__(self, proxies, filters: dict):
        self._fixed = None if proxies is None else [str(p) for p in proxies]
        self._filters = {"protocol": "http", "limit": 50, **filters}
        self._urls: list[str] = []
        self._i = 0
        self._lock = threading.Lock()
        self._refilling = asyncio.Lock()

    def refill(self) -> None:
        urls = list(self._fixed) if self._fixed is not None else proxy_urls(**self._filters)
        if not urls:
            raise ProxmintError("no proxies to rotate through")
        with self._lock:
            self._urls, self._i = urls, 0

    def _next(self) -> str | None:
        with self._lock:
            if not self._urls:
                return None
            self._i %= len(self._urls)
            url = self._urls[self._i]
            self._i += 1
            return url

    def take(self) -> str:
        while (url := self._next()) is None:
            self.refill()
        return url

    async def take_async(self) -> str:
        while (url := self._next()) is None:
            async with self._refilling:  # one fetch, however many requests found the pool empty
                if not self._urls:
                    await asyncio.to_thread(self.refill)
        return url

    def drop(self, url: str) -> None:
        with self._lock:
            if url in self._urls:
                k = self._urls.index(url)
                del self._urls[k]
                if k < self._i:  # keep the cursor on the proxy that was next
                    self._i -= 1
