package com.proxmint;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client for the <a href="https://proxmint.com/free-proxies">Proxmint free proxy list</a>: HTTP,
 * HTTPS, SOCKS4 and SOCKS5 proxies, each proven alive by a real request made through it in the
 * last 30 minutes. No key, no signup.
 *
 * <pre>{@code
 * List<Proxmint.FreeProxy> proxies =
 *     Proxmint.freeProxies(new Proxmint.Filters().protocol("http").country("us").limit(50));
 * HttpClient client = HttpClient.newBuilder().proxy(Proxmint.rotating(proxies)).build();
 * }</pre>
 *
 * <p>API docs: <a href="https://proxmint.com/free-proxies#api">proxmint.com/free-proxies#api</a>.
 */
public final class Proxmint {
    static final URI API = URI.create("https://proxmint.com/api/free-proxies");
    /** The server caps a page here, so larger requests are paged. */
    private static final int MAX_PAGE_SIZE = 200;
    private static final String UA = "proxmint-java/0.1.0 (+https://proxmint.com/free-proxies)";
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private Proxmint() {}

    /**
     * One live proxy, as the API returns it.
     *
     * @param ip the proxy's IP address
     * @param port the proxy's port
     * @param protocol {@code http}, {@code https}, {@code socks4} or {@code socks5}
     * @param countryCode ISO 3166-1 alpha-2 in lowercase, such as {@code de}, or null
     * @param countryName the country's English name, or null
     * @param anonymity {@code elite}, {@code anonymous} or {@code transparent}, or null while
     *     unmeasured
     * @param latencyMs round trip of the last successful check, or null
     * @param uptimePct share of recent checks that passed, 0 to 100
     * @param score rank from uptime, latency and recency; the default sort
     * @param lastCheckedAt when it last passed a check, RFC 3339, or null
     */
    public record FreeProxy(
            String ip,
            int port,
            String protocol,
            String countryCode,
            String countryName,
            String anonymity,
            Integer latencyMs,
            double uptimePct,
            double score,
            String lastCheckedAt) {

        /**
         * The URL to hand an HTTP client, such as {@code socks5://203.0.113.7:1080}. An {@code
         * https} proxy is an HTTP proxy that can tunnel TLS with CONNECT, so it gets {@code
         * http://}: an {@code https://} URL would make the client attempt TLS to the proxy itself.
         *
         * @return the proxy URL
         */
        public String url() {
            return ("https".equals(protocol) ? "http" : protocol) + "://" + ip + ":" + port;
        }

        /**
         * This proxy as a {@link java.net.Proxy}: HTTP for http and https, SOCKS for socks4 and
         * socks5. Use it with {@code URL.openConnection(proxy)}, which speaks both.
         *
         * @return the proxy for {@code java.net}
         */
        public java.net.Proxy toJavaProxy() {
            java.net.Proxy.Type type =
                    protocol.startsWith("socks") ? java.net.Proxy.Type.SOCKS : java.net.Proxy.Type.HTTP;
            return new java.net.Proxy(type, new InetSocketAddress(ip, port));
        }
    }

    /** What to ask for. The defaults ask for the 20 best proxies of any kind. */
    public static final class Filters {
        private String protocol;
        private String country;
        private String anonymity;
        private String sort;
        private int limit = 20;

        /** Creates filters that ask for the 20 best proxies of any kind. */
        public Filters() {}

        /**
         * @param protocol {@code http}, {@code https}, {@code socks4} or {@code socks5}
         * @return these filters
         */
        public Filters protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        /**
         * @param country ISO 3166-1 alpha-2, such as {@code de}
         * @return these filters
         */
        public Filters country(String country) {
            this.country = country;
            return this;
        }

        /**
         * @param anonymity {@code elite}, {@code anonymous} or {@code transparent}
         * @return these filters
         */
        public Filters anonymity(String anonymity) {
            this.anonymity = anonymity;
            return this;
        }

        /**
         * @param sort {@code score} (the default), {@code latency}, {@code uptime} or {@code
         *     checked}
         * @return these filters
         */
        public Filters sort(String sort) {
            this.sort = sort;
            return this;
        }

        /**
         * @param limit rows wanted, 20 by default; paged past the API's 200-row cap
         * @return these filters
         */
        public Filters limit(int limit) {
            if (limit < 1) throw new IllegalArgumentException("limit must be positive, got " + limit);
            this.limit = limit;
            return this;
        }
    }

    /** The API refused the request or sent back something unreadable. */
    public static final class ProxmintException extends IOException {
        private final int status;

        ProxmintException(String message, int status) {
            super(message);
            this.status = status;
        }

        /**
         * @return the HTTP status; 429 means the API's limit of 60 requests a minute per IP
         */
        public int status() {
            return status;
        }
    }

    /**
     * The 20 best live proxies of any kind.
     *
     * @return proxies, best first
     * @throws IOException if the API cannot be reached or refuses the request
     * @throws InterruptedException if interrupted while waiting for the API
     */
    public static List<FreeProxy> freeProxies() throws IOException, InterruptedException {
        return freeProxies(new Filters());
    }

    /**
     * Live proxies, best first.
     *
     * @param filters what to ask for
     * @return proxies, best first
     * @throws IOException if the API cannot be reached or refuses the request
     * @throws InterruptedException if interrupted while waiting for the API
     */
    public static List<FreeProxy> freeProxies(Filters filters) throws IOException, InterruptedException {
        return fetch(API, filters);
    }

    /**
     * A {@link ProxySelector} for {@code HttpClient.Builder.proxy} that hands out the proxies in
     * turn, one per request. {@code HttpClient} speaks HTTP proxies only and connects directly
     * when a selector returns a SOCKS proxy, so SOCKS proxies are refused here.
     *
     * @param proxies http or https proxies
     * @return a selector that rotates through them
     * @throws IllegalArgumentException if the list is empty or holds a SOCKS proxy
     */
    public static ProxySelector rotating(List<FreeProxy> proxies) {
        if (proxies.isEmpty()) throw new IllegalArgumentException("no proxies to rotate through");
        List<java.net.Proxy> list = new ArrayList<>();
        for (FreeProxy p : proxies) {
            if (p.protocol().startsWith("socks")) {
                throw new IllegalArgumentException(
                        "HttpClient ignores SOCKS proxies and would connect directly: " + p.url());
            }
            list.add(p.toJavaProxy());
        }
        AtomicInteger next = new AtomicInteger();
        return new ProxySelector() {
            @Override
            public List<java.net.Proxy> select(URI uri) {
                return List.of(list.get(Math.floorMod(next.getAndIncrement(), list.size())));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException e) {}
        };
    }

    static List<FreeProxy> fetch(URI api, Filters f) throws IOException, InterruptedException {
        int size = Math.min(f.limit, MAX_PAGE_SIZE); // fixed: the server skips (page - 1) * pageSize rows
        List<FreeProxy> out = new ArrayList<>();
        for (int page = 1; out.size() < f.limit; page++) {
            StringJoiner q = new StringJoiner("&");
            add(q, "protocol", f.protocol);
            add(q, "country", f.country == null ? null : f.country.toLowerCase(Locale.ROOT));
            add(q, "anonymity", f.anonymity);
            add(q, "sort", f.sort);
            add(q, "page", String.valueOf(page));
            add(q, "pageSize", String.valueOf(size));
            HttpRequest req = HttpRequest.newBuilder(URI.create(api + "?" + q))
                    .header("Accept", "application/json")
                    .header("User-Agent", UA)
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int status = res.statusCode();
            if (status == 429) {
                throw new ProxmintException("rate limited: the API allows 60 requests a minute per IP", 429);
            }
            if (status != 200) throw new ProxmintException("Proxmint API returned " + status, status);
            List<?> rows;
            double total;
            try {
                Map<?, ?> body = (Map<?, ?>) new Json(res.body()).parse();
                rows = body.get("rows") instanceof List<?> l ? l : List.of();
                total = body.get("total") instanceof Double d ? d : 0;
                for (Object r : rows) out.add(proxy((Map<?, ?>) r));
            } catch (RuntimeException e) {
                throw new ProxmintException("unreadable answer from the Proxmint API: " + e.getMessage(), status);
            }
            // A short page means the pool ended, not that the caller got what they asked for.
            if (rows.isEmpty() || out.size() >= total) break;
        }
        return out.size() > f.limit ? new ArrayList<>(out.subList(0, f.limit)) : out;
    }

    private static void add(StringJoiner q, String key, String value) {
        if (value != null) q.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static FreeProxy proxy(Map<?, ?> r) {
        return new FreeProxy(
                (String) r.get("ip"),
                ((Double) r.get("port")).intValue(),
                (String) r.get("protocol"),
                (String) r.get("countryCode"),
                (String) r.get("countryName"),
                (String) r.get("anonymity"),
                r.get("latencyMs") instanceof Double d ? Integer.valueOf(d.intValue()) : null,
                r.get("uptimePct") instanceof Double d ? d : 0,
                r.get("score") instanceof Double d ? d : 0,
                (String) r.get("lastCheckedAt"));
    }

    /** Just enough JSON for the API's answers: objects, arrays, strings, numbers, booleans, null. */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object parse() {
            Object v = value();
            ws();
            if (i != s.length()) throw error();
            return v;
        }

        private Object value() {
            ws();
            if (i >= s.length()) throw error();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                if (eat('}')) return m;
                do {
                    ws();
                    String k = string();
                    if (!eat(':')) throw error();
                    m.put(k, value());
                } while (eat(','));
                if (!eat('}')) throw error();
                return m;
            }
            if (c == '[') {
                i++;
                List<Object> a = new ArrayList<>();
                if (eat(']')) return a;
                do a.add(value());
                while (eat(','));
                if (!eat(']')) throw error();
                return a;
            }
            if (c == '"') return string();
            for (String word : new String[] {"true", "false", "null"}) {
                if (s.startsWith(word, i)) {
                    i += word.length();
                    return word.equals("null") ? null : Boolean.valueOf(word);
                }
            }
            int start = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw error();
            return Double.valueOf(s.substring(start, i));
        }

        private String string() {
            if (i >= s.length() || s.charAt(i) != '"') throw error();
            i++;
            StringBuilder b = new StringBuilder();
            for (char c = s.charAt(i++); c != '"'; c = s.charAt(i++)) {
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e);
                }
            }
            return b.toString();
        }

        private boolean eat(char c) {
            ws();
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        private void ws() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
        }

        private IllegalArgumentException error() {
            return new IllegalArgumentException("bad JSON at offset " + i);
        }
    }
}
