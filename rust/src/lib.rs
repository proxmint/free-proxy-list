//! Client for the [Proxmint free proxy list](https://proxmint.com/free-proxies): HTTP,
//! HTTPS, SOCKS4 and SOCKS5 proxies, each proven alive by a real request made through it
//! in the last 30 minutes. No key, no signup.
//!
//! ```no_run
//! let proxies = proxmint::free_proxies(&proxmint::Filters {
//!     protocol: Some("socks5"),
//!     country: Some("de"),
//!     limit: 50,
//!     ..Default::default()
//! })?;
//! for p in &proxies {
//!     println!("{} {:?} ms", p.url(), p.latency_ms);
//! }
//! # Ok::<(), proxmint::Error>(())
//! ```
//!
//! Calls block. From async code, run them on a blocking thread, such as tokio's
//! `spawn_blocking`.

use std::{fmt, time::Duration};

use serde::Deserialize;

const API: &str = "https://proxmint.com/api/free-proxies";
/// The server caps a page here, so larger requests are paged.
const MAX_PAGE_SIZE: usize = 200;
const UA: &str = concat!(
    "proxmint-rust/",
    env!("CARGO_PKG_VERSION"),
    " (+https://proxmint.com/free-proxies)"
);

/// One live proxy, as the API returns it.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
#[non_exhaustive]
pub struct Proxy {
    pub ip: String,
    pub port: u16,
    /// `http`, `https`, `socks4` or `socks5`.
    pub protocol: String,
    /// ISO 3166-1 alpha-2 in lowercase, such as `de`.
    pub country_code: Option<String>,
    pub country_name: Option<String>,
    /// `elite`, `anonymous` or `transparent`, or `None` while unmeasured.
    pub anonymity: Option<String>,
    /// Round trip of the last successful check.
    pub latency_ms: Option<u32>,
    /// Share of recent checks that passed, 0 to 100.
    pub uptime_pct: f64,
    /// Rank from uptime, latency and recency; the default sort.
    pub score: f64,
    /// When it last passed a check, RFC 3339.
    pub last_checked_at: Option<String>,
}

impl Proxy {
    /// The URL to hand an HTTP client, such as `socks5://203.0.113.7:1080`. An `https`
    /// proxy is an HTTP proxy that can tunnel TLS with CONNECT, so it gets `http://`:
    /// an `https://` URL would make the client attempt TLS to the proxy itself.
    pub fn url(&self) -> String {
        let scheme = if self.protocol == "https" {
            "http"
        } else {
            &self.protocol
        };
        format!("{scheme}://{}:{}", self.ip, self.port)
    }
}

/// What to ask for. [`Filters::default`] asks for the 20 best proxies of any kind.
#[derive(Debug, Clone)]
pub struct Filters<'a> {
    /// `http`, `https`, `socks4` or `socks5`.
    pub protocol: Option<&'a str>,
    /// ISO 3166-1 alpha-2, such as `de`.
    pub country: Option<&'a str>,
    /// `elite`, `anonymous` or `transparent`.
    pub anonymity: Option<&'a str>,
    /// `score` (the default), `latency`, `uptime` or `checked`.
    pub sort: Option<&'a str>,
    /// Rows wanted. Paged past the API's 200-row cap.
    pub limit: usize,
}

impl Default for Filters<'_> {
    fn default() -> Self {
        Filters {
            protocol: None,
            country: None,
            anonymity: None,
            sort: None,
            limit: 20,
        }
    }
}

/// Why the list could not be read.
#[derive(Debug)]
#[non_exhaustive]
pub enum Error {
    /// HTTP 429: the API allows 60 requests a minute per IP.
    RateLimited,
    /// The API answered with another error status.
    Status(u16),
    /// No answer came back, or it was not the expected JSON.
    Request(Box<dyn std::error::Error + Send + Sync>),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::RateLimited => {
                f.write_str("rate limited: the API allows 60 requests a minute per IP")
            }
            Error::Status(s) => write!(f, "Proxmint API returned {s}"),
            Error::Request(e) => write!(f, "could not read the Proxmint API: {e}"),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Request(e) => Some(e.as_ref()),
            _ => None,
        }
    }
}

/// Live proxies, best first.
pub fn free_proxies(filters: &Filters) -> Result<Vec<Proxy>, Error> {
    fetch(API, filters)
}

/// [`free_proxies`] as URLs; see [`Proxy::url`].
pub fn proxy_urls(filters: &Filters) -> Result<Vec<String>, Error> {
    Ok(free_proxies(filters)?.iter().map(Proxy::url).collect())
}

#[derive(Deserialize)]
struct Page {
    rows: Vec<Proxy>,
    #[serde(default)]
    total: usize,
}

fn fetch(api: &str, f: &Filters) -> Result<Vec<Proxy>, Error> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(30)))
        .build()
        .into();
    let country = f.country.map(str::to_ascii_lowercase);
    let filters = [
        ("protocol", f.protocol),
        ("country", country.as_deref()),
        ("anonymity", f.anonymity),
        ("sort", f.sort),
    ];
    let size = f.limit.min(MAX_PAGE_SIZE); // fixed: the server skips (page - 1) * pageSize rows
    let mut out = Vec::new();
    let mut page = 1;
    while out.len() < f.limit {
        let mut req = agent
            .get(api)
            .header("Accept", "application/json")
            .header("User-Agent", UA);
        for (k, v) in filters {
            if let Some(v) = v {
                req = req.query(k, v);
            }
        }
        let body: Page = req
            .query("page", page.to_string())
            .query("pageSize", size.to_string())
            .call()
            .and_then(|mut r| r.body_mut().read_json())
            .map_err(|e| match e {
                ureq::Error::StatusCode(429) => Error::RateLimited,
                ureq::Error::StatusCode(s) => Error::Status(s),
                e => Error::Request(e.into()),
            })?;
        let n = body.rows.len();
        out.extend(body.rows);
        // A short page means the pool ended, not that the caller got what they asked for.
        if n == 0 || out.len() >= body.total {
            break;
        }
        page += 1;
    }
    out.truncate(f.limit);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader, Write};
    use std::net::TcpListener;
    use std::sync::{Arc, Mutex};

    /// Serves a pool of `total` rows the way the API pages them, or `status` with no
    /// body. Returns the API URL and the request targets it saw.
    fn stub(total: usize, status: u16) -> (String, Arc<Mutex<Vec<String>>>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let api = format!("http://{}/api/free-proxies", listener.local_addr().unwrap());
        let seen = Arc::new(Mutex::new(Vec::new()));
        let log = seen.clone();
        std::thread::spawn(move || {
            for mut s in listener.incoming().flatten() {
                let mut head = String::new();
                let mut r = BufReader::new(&s);
                while r.read_line(&mut head).unwrap() > 2 {}
                let target = head.split(' ').nth(1).unwrap().to_string();
                let q = |k: &str| -> usize {
                    let kv = target
                        .split(['?', '&'])
                        .find_map(|kv| kv.strip_prefix(&format!("{k}=")));
                    kv.unwrap().parse().unwrap()
                };
                let start = (q("page") - 1) * q("pageSize");
                let rows: Vec<String> = (start..total.min(start + q("pageSize")))
                    .map(|n| format!(r#"{{"ip":"10.0.0.1","port":{n},"protocol":"http","uptimePct":99,"score":90}}"#))
                    .collect();
                let body = if status == 200 {
                    format!(r#"{{"rows":[{}],"total":{total}}}"#, rows.join(","))
                } else {
                    String::new()
                };
                log.lock().unwrap().push(target);
                let head = format!("HTTP/1.1 {status} X\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
                s.write_all((head + &body).as_bytes()).unwrap();
            }
        });
        (api, seen)
    }

    #[test]
    fn pages_past_the_200_row_cap_without_repeats() {
        let (api, seen) = stub(1000, 200);
        let rows = fetch(
            &api,
            &Filters {
                country: Some("DE"),
                limit: 350,
                ..Default::default()
            },
        )
        .unwrap();
        assert_eq!(
            rows.iter().map(|p| p.port as usize).collect::<Vec<_>>(),
            (0..350).collect::<Vec<_>>()
        );
        let seen = seen.lock().unwrap();
        assert_eq!(seen.len(), 2);
        assert!(
            seen.iter()
                .all(|t| t.contains("pageSize=200") && t.contains("country=de")),
            "{seen:?}"
        );
        assert!(!seen[0].contains("protocol="), "{seen:?}");
    }

    #[test]
    fn stops_at_the_end_of_the_pool() {
        let (api, seen) = stub(30, 200);
        assert_eq!(
            fetch(
                &api,
                &Filters {
                    limit: 500,
                    ..Default::default()
                }
            )
            .unwrap()
            .len(),
            30
        );
        assert_eq!(seen.lock().unwrap().len(), 1);
    }

    #[test]
    fn errors_carry_the_status() {
        assert!(matches!(
            fetch(&stub(0, 429).0, &Filters::default()),
            Err(Error::RateLimited)
        ));
        assert!(matches!(
            fetch(&stub(0, 503).0, &Filters::default()),
            Err(Error::Status(503))
        ));
    }

    #[test]
    fn https_proxies_get_http_urls() {
        let p = |protocol: &str| Proxy {
            ip: "9.9.9.9".into(),
            port: 8080,
            protocol: protocol.into(),
            country_code: None,
            country_name: None,
            anonymity: None,
            latency_ms: None,
            uptime_pct: 0.0,
            score: 0.0,
            last_checked_at: None,
        };
        assert_eq!(p("https").url(), "http://9.9.9.9:8080");
        assert_eq!(p("socks5").url(), "socks5://9.9.9.9:8080");
    }
}
