# proxmint for Java

Java client for the [Proxmint free proxy list](https://proxmint.com/free-proxies): HTTP, HTTPS,
SOCKS4 and SOCKS5 proxies, each proven alive by a real request made through it in the last 30
minutes. No key, no signup, no dependencies. Java 17 or later.

```xml
<dependency>
  <groupId>com.proxmint</groupId>
  <artifactId>proxmint</artifactId>
  <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("com.proxmint:proxmint:0.1.0")
```

## Read the list

```java
import com.proxmint.Proxmint;

List<Proxmint.FreeProxy> proxies =
    Proxmint.freeProxies(new Proxmint.Filters().protocol("socks5").country("de").limit(50));
for (Proxmint.FreeProxy p : proxies) {
    System.out.println(p.url() + " " + p.latencyMs() + " ms");
}
```

| Filter | Values |
|---|---|
| `protocol` | `http`, `https`, `socks4` or `socks5` |
| `country` | a two-letter code such as `de` |
| `anonymity` | `elite`, `anonymous` or `transparent` |
| `sort` | `score` (the default), `latency`, `uptime` or `checked` |
| `limit` | rows wanted, 20 by default; paged past the API's 200-row cap |

Each `FreeProxy` has `ip`, `port`, `protocol`, `countryCode`, `countryName`, `anonymity`,
`latencyMs`, `uptimePct`, `score` and `lastCheckedAt`. `url()` gives the URL an HTTP client
wants: an `https` proxy is an HTTP proxy that can tunnel TLS with CONNECT, so its URL starts with
`http://`. `toJavaProxy()` gives a `java.net.Proxy`.

Failures throw `Proxmint.ProxmintException` (an `IOException`) with the HTTP status. The API
allows 60 requests a minute per IP.

## Rotate proxies with HttpClient

```java
List<Proxmint.FreeProxy> proxies = Proxmint.freeProxies(new Proxmint.Filters().protocol("http").limit(50));
HttpClient client = HttpClient.newBuilder().proxy(Proxmint.rotating(proxies)).build();
```

Each request goes out through the next proxy in turn. `HttpClient` speaks HTTP proxies only, and
it connects directly, without a proxy, when a selector hands it a SOCKS proxy. So `rotating()`
refuses SOCKS proxies. For SOCKS, use `URL.openConnection(proxy.toJavaProxy())`.

## Good to know

Free proxies are run by strangers. They are slow and short-lived, and some log or tamper with
traffic. Use them for testing and research, and never send logged-in or sensitive traffic
through one.

The same list is on the command line as `npx proxmint`, in Python as `pip install proxmint`, in
Rust as the `proxmint` crate, and as plain text and JSON files in this repository. API docs:
<https://proxmint.com/free-proxies#api>.

## Licence

MIT. The list itself is published under CC BY 4.0.
