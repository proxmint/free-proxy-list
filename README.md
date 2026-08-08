# Free Proxy List — checked every 30 minutes

**637 live proxies** · 71 countries · updated **2026-08-08 21:27 UTC**

Every proxy in this list is re-tested every 30 minutes by a real HTTP
request *through it* to our own echo endpoint — not pinged, not assumed. Every entry here last passed that test within **5.5 hours** (median 31 minutes).
Entries that stop answering are dropped, not left to rot. That is the only thing
this repo does differently, and it is the whole point: most public lists are
unvalidated scrapes where the majority of entries are already dead when you
download them.

| | |
|---|---|
| Live now | **637** |
| Countries | 71 |
| Median latency | 1965 ms |
| Re-validated | every 30 minutes |

## Files

| File | Count | Format |
|---|---|---|
| [`proxies/all.txt`](proxies/all.txt) | 637 | `protocol://ip:port` |
| [`proxies/http.txt`](proxies/http.txt) | 212 | `ip:port` |
| [`proxies/https.txt`](proxies/https.txt) | 41 | `ip:port` |
| [`proxies/socks4.txt`](proxies/socks4.txt) | 209 | `ip:port` |
| [`proxies/socks5.txt`](proxies/socks5.txt) | 175 | `ip:port` |
| [`proxies/elite.txt`](proxies/elite.txt) | 489 | `protocol://ip:port`, no leak found |
| [`proxies/all.json`](proxies/all.json) | 637 | country, anonymity, latency, uptime, score |

Top countries: United States (103) · Russia (66) · China (54) · Germany (32) · France (31) · Indonesia (30) · Singapore (26) · Vietnam (24) · Netherlands (16) · Brazil (16)

## Use it

```bash
curl -s https://raw.githubusercontent.com/proxmint/free-proxy-list/main/proxies/socks5.txt
```

Live JSON/text API, same data, with filters (country, protocol) and sorting:

```bash
curl 'https://proxmint.com/api/free-proxies?protocol=socks5&format=txt'
```

No key, no signup, 60 req/min. Docs: <https://proxmint.com/free-proxies#api>

## Where these come from

**We are not the origin of these proxies and do not claim to be.** Candidates are
pulled from the public lists below — all of which are doing the hard part — and
then independently validated by us. Counts are how many of *this* repo's current
live entries each source contributed. Go star them:

| Source | Live entries now |
|---|---|
| [monosans/proxy-list](https://github.com/monosans/proxy-list) | 219 |
| [proxylist.geonode.com](https://proxylist.geonode.com) | 51 |
| [TheSpeedX/PROXY-List](https://github.com/TheSpeedX/PROXY-List) | 237 |
| [proxifly/free-proxy-list](https://github.com/proxifly/free-proxy-list) | 130 |

## Fields in `all.json`

| Field | Meaning |
|---|---|
| `uptimePct` | share of our checks this proxy has passed (EWMA, 0–100) |
| `latencyMs` | round trip through the proxy on its last successful check |
| `score` | reliability rank combining uptime, latency and recency |
| `anonymity` | `elite` — added nothing a direct request wouldn't · `anonymous` — announces itself as a proxy · `transparent` — leaked the caller's IP · empty — not measurable, see below |

### How anonymity is measured

We fetch our own echo endpoint through the proxy and diff the forwarding headers
it arrives with against the headers a *direct* request arrives with. Whatever the
proxy added is the rating. The source list's own claim is never trusted, and the
baseline is measured on every run rather than hardcoded.

**Where the field is empty, and why.** Our echo is served over https, and an http
proxy carrying an https request opens a CONNECT tunnel: it relays encrypted bytes
it cannot read, so it has no opportunity to add `Via` or `X-Forwarded-For` even
if it normally would. A blank result there is the transport telling us nothing,
so we publish nothing rather than upgrade silence to `elite`. SOCKS proxies are
rated because SOCKS is a layer-4 byte relay with no headers to add in the first
place.

**One further limit, stated plainly:** our echo sits behind a CDN that overwrites
inbound `X-Forwarded-For`, so a proxy that leaks your IP *only* through that
header, and sets no `Via` or `Forwarded`, is rated `elite` here. Read `elite`
as **"we found no leak"**, not "there is none".

## Warning

These are free public proxies run by strangers. They are slow, short-lived, and
some are honeypots that log or tamper with traffic. Use them for testing and
research. **Never send logged-in or sensitive traffic through one.** We verify
that a proxy forwards traffic — nothing more. We do not operate, audit, or
endorse any of them.

Needing proxies that stay up is a different problem, and the one [Proxmint](https://proxmint.com) sells.

---

*Regenerated automatically every 30 minutes by [Proxmint](https://proxmint.com). Data is public information, published as-is.*
