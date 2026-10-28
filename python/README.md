# proxmint

Free proxy list for Python, re-checked every 30 minutes, plus proxy rotation for aiohttp and httpx.
The client has no dependencies and needs no API key or signup.

Every proxy the API returns was proven alive by a real request made *through it* within the last half hour,
not pinged and not assumed. Dead entries are dropped rather than left to rot.

```bash
pip install proxmint              # the client, standard library only
pip install "proxmint[aiohttp]"   # + rotating-proxy middleware for aiohttp
pip install "proxmint[httpx]"     # + rotating transports for httpx
```

## Free proxy list

```python
from proxmint import free_proxies, proxy_urls

proxies = free_proxies(protocol="socks5", country="de", limit=50)
# [{"ip": "203.0.113.7", "port": 1080, "protocol": "socks5", "countryCode": "de",
#   "countryName": "Germany", "anonymity": "elite", "latencyMs": 412, "uptimePct": 97.5,
#   "score": 91, "lastCheckedAt": "2026-10-28T10:56:12.583Z"}, ...]

proxy_urls(sort="latency", limit=10)
# ["http://198.51.100.4:3128", "socks5://203.0.113.7:1080", ...]
```

| Filter | |
|---|---|
| `protocol` | `http` · `https` · `socks4` · `socks5` |
| `country` | ISO 3166-1 alpha-2, e.g. `de` |
| `anonymity` | `elite` · `anonymous` · `transparent` |
| `sort` | `score` (default) · `latency` · `uptime` · `checked` |
| `limit` | rows wanted (default 20); paged past the API's 200-row cap |

An `https` proxy is an HTTP proxy that can also tunnel TLS, so its URL starts with `http://`. `proxy_url(row)` builds
the URL for one row. Failures raise `ProxmintError` with a `status`. The API allows 60 requests a minute per IP.

## Rotating proxies with aiohttp

```python
import aiohttp
from proxmint.aiohttp import RotatingProxy

async with aiohttp.ClientSession(middlewares=(RotatingProxy(),)) as session:
    async with session.get("https://example.com") as resp:
        print(resp.status)
```

Each request goes out through the next proxy in the pool. A proxy that refuses the connection, times out, drops it or
answers `407` is removed, and the request is retried through another, up to `tries=3` proxies. Each proxy gets
`timeout=10` seconds to connect and send back headers. A session-wide `ClientTimeout(total=...)` covers all the tries
together, so keep it loose.

Without a list of your own, the pool is the 50 best `http` proxies on the free list, fetched again whenever it runs
dry. The filters above narrow it: `RotatingProxy(country="us", sort="latency")`. To rotate through your own proxies,
pass them: `RotatingProxy(["http://user:pass@203.0.113.7:3128", ...])`.

Needs aiohttp 3.12 or later (client middleware). aiohttp speaks HTTP proxies only.

## Rotating proxies with httpx

```python
import httpx
from proxmint.httpx import AsyncRotatingTransport, RotatingTransport

with httpx.Client(transport=RotatingTransport(country="us")) as client:
    print(client.get("https://example.com").status_code)

async with httpx.AsyncClient(transport=AsyncRotatingTransport()) as client:
    print((await client.get("https://example.com")).status_code)
```

Same rotation and the same arguments. For SOCKS, `pip install "httpx[socks]"` and pass `protocol="socks5"` or your
own `socks5://` URLs.

Retries resend the request body, so a streamed upload can't be retried.

## Warning

These are free public proxies run by strangers. They are slow, short-lived, and some are honeypots that log or tamper
with traffic. Use them for testing and research. **Never send logged-in or sensitive traffic through one.** We verify
that a proxy forwards traffic, nothing more. We do not operate, audit or endorse any of them.

Needing proxies that stay up is a different problem, and the one [Proxmint](https://proxmint.com) sells.

## Also

- Docs: <https://proxmint.com/docs/python>
- Plain text and JSON files, committed every 30 minutes: [github.com/proxmint/free-proxy-list](https://github.com/proxmint/free-proxy-list)
- HTTP API: <https://proxmint.com/free-proxies#api>
- The same list from the command line: `npx proxmint`, or `sudo snap install proxmint`
