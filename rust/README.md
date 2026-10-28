# proxmint

Rust client for the [Proxmint free proxy list](https://proxmint.com/free-proxies): HTTP, HTTPS,
SOCKS4 and SOCKS5 proxies, each proven alive by a real request made through it in the last 30
minutes. No key, no signup.

```toml
[dependencies]
proxmint = "0.1"
```

```rust
let proxies = proxmint::free_proxies(&proxmint::Filters {
    protocol: Some("socks5"),
    country: Some("de"),
    limit: 50,
    ..Default::default()
})?;
for p in &proxies {
    println!("{} {:?} ms", p.url(), p.latency_ms);
}
```

| Filter | Values |
|---|---|
| `protocol` | `http`, `https`, `socks4` or `socks5` |
| `country` | a two-letter code such as `de` |
| `anonymity` | `elite`, `anonymous` or `transparent` |
| `sort` | `score` (the default), `latency`, `uptime` or `checked` |
| `limit` | rows wanted, 20 by default; paged past the API's 200-row cap |

Each `Proxy` has `ip`, `port`, `protocol`, `country_code`, `country_name`, `anonymity`,
`latency_ms`, `uptime_pct`, `score` and `last_checked_at`. `Proxy::url()` gives the URL an HTTP
client wants. An `https` proxy is an HTTP proxy that can tunnel TLS with CONNECT, so its URL
starts with `http://`.

Calls block. From async code, run them on a blocking thread, such as tokio's `spawn_blocking`.
The API allows 60 requests a minute per IP; over that, calls return `Error::RateLimited`.

## Rotate proxies with reqwest

One proxy per request, in turn:

```rust
use std::sync::atomic::{AtomicUsize, Ordering};

let urls = proxmint::proxy_urls(&proxmint::Filters { protocol: Some("http"), limit: 50, ..Default::default() })?;
let next = AtomicUsize::new(0);
let client = reqwest::blocking::Client::builder()
    .proxy(reqwest::Proxy::custom(move |_| {
        (!urls.is_empty()).then(|| urls[next.fetch_add(1, Ordering::Relaxed) % urls.len()].clone())
    }))
    .build()?;
```

## Good to know

Free proxies are run by strangers. They are slow and short-lived, and some log or tamper with
traffic. Use them for testing and research, and never send logged-in or sensitive traffic
through one.

The same list is on the command line as `npx proxmint`, in Python as `pip install proxmint`, and
as plain text and JSON files on [GitHub](https://github.com/proxmint/free-proxy-list). API docs:
<https://proxmint.com/free-proxies#api>.

## Licence

MIT. The list itself is published under CC BY 4.0.
