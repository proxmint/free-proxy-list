# proxmint

Free proxy list, re-validated every 30 minutes. Zero dependencies, no API key, no signup.

Every proxy this returns was proven alive by a real HTTP request made *through it* — not pinged, not assumed — within the last half hour. Dead entries are dropped rather than left to rot, which is the difference between this and most public proxy lists.

```bash
npx proxmint
```

```
64.112.184.210:3128
69.55.49.177:38182
34.43.46.91:443
...
```

## CLI

```bash
npx proxmint                              # 20 fastest, ip:port, one per line
npx proxmint -p socks5 -c de -n 50        # 50 german socks5
npx proxmint --sort latency --url         # protocol://ip:port
npx proxmint --json | jq '.[] | select(.uptimePct > 90)'
```

| Option | |
|---|---|
| `-p, --protocol` | `http` · `https` · `socks4` · `socks5` |
| `-c, --country` | ISO 3166-1 alpha-2, e.g. `de` |
| `-a, --anonymity` | `elite` · `anonymous` · `transparent` |
| `-s, --sort` | `score` · `latency` · `uptime` · `checked` (default `score`) |
| `-n, --limit` | how many to print (default 20) |
| `--url` | print `protocol://ip:port` |
| `--json` | full rows as JSON |

Output goes to stdout one per line, so it pipes into anything. Messages and errors go to stderr.

## Library

```js
import { fetchProxies, fetchProxyUrls } from "proxmint";

const proxies = await fetchProxies({ protocol: "socks5", country: "de", limit: 50 });
// [{ ip, port, protocol, countryCode, countryName, anonymity,
//    latencyMs, uptimePct, score, lastCheckedAt }, ...]

const urls = await fetchProxyUrls({ sort: "latency", limit: 10 });
// ["socks5://69.55.49.177:38182", ...]
```

`fetchProxyLines()` returns bare `ip:port` strings. All three take the same options and page automatically past the API's 200-rows-per-request cap. Ships its own TypeScript types.

Failures throw `ProxmintError` with a `status`.

## Fields

| Field | Meaning |
|---|---|
| `uptimePct` | share of our checks this proxy has passed (EWMA, 0–100) |
| `latencyMs` | round trip through the proxy on its last successful check |
| `score` | reliability rank combining uptime, latency and recency |
| `anonymity` | `elite` — added nothing a direct request wouldn't · `anonymous` — announces itself as a proxy · `transparent` — leaked the caller's IP · `null` — not measurable |

**`anonymity` is `null`, never a guess.** An http proxy carrying an https request opens a CONNECT tunnel: it relays encrypted bytes it cannot read, so it has no opportunity to add `Via` or `X-Forwarded-For` even if it normally would. Silence there means *unknown*, not *elite*.

One further limit, stated plainly: our echo endpoint sits behind a CDN that overwrites inbound `X-Forwarded-For`, so a proxy that leaks your IP *only* through that header, and sets no `Via` or `Forwarded`, is rated `elite` here. Read `elite` as **"we found no leak"**, not "there is none".

## Warning

These are free public proxies run by strangers. They are slow, short-lived, and some are honeypots that log or tamper with traffic. Use them for testing and research. **Never send logged-in or sensitive traffic through one.** We verify that a proxy forwards traffic — nothing more. We do not operate, audit, or endorse any of them.

Needing proxies that stay up is a different problem, and the one [Proxmint](https://proxmint.com) sells.

## Also

- Plain text and JSON files, committed every 30 minutes: [github.com/proxmint/free-proxy-list](https://github.com/proxmint/free-proxy-list)
- HTTP API and docs: <https://proxmint.com/free-proxies#api>
- Rate limit 60 requests/minute per IP

## Requirements

Node 20 or newer. No dependencies.

## License

MIT
