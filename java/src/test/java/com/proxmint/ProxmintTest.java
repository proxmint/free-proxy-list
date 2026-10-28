package com.proxmint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Offline checks: paging, errors, URLs, rotation and the JSON reader. */
class ProxmintTest {
    private final List<String> seen = new ArrayList<>();

    /** Serves a pool of {@code total} rows the way the API pages them, or {@code status} alone. */
    private HttpServer stub(int total, int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String query = ex.getRequestURI().getRawQuery();
            seen.add(query);
            Map<String, String> q = Arrays.stream(query.split("&"))
                    .map(kv -> kv.split("=", 2))
                    .collect(Collectors.toMap(kv -> kv[0], kv -> kv[1]));
            int size = Integer.parseInt(q.get("pageSize"));
            int start = (Integer.parseInt(q.get("page")) - 1) * size;
            StringJoiner rows = new StringJoiner(",");
            for (int n = start; n < Math.min(total, start + size); n++) {
                rows.add("{\"ip\":\"10.0.0.1\",\"port\":" + n
                        + ",\"protocol\":\"http\",\"latencyMs\":null,\"uptimePct\":99,\"score\":90}");
            }
            byte[] body = status == 200 ? ("{\"rows\":[" + rows + "],\"total\":" + total + "}").getBytes(UTF_8) : new byte[0];
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return server;
    }

    private static URI api(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/free-proxies");
    }

    private static Proxmint.FreeProxy proxy(String protocol, int port) {
        return new Proxmint.FreeProxy("9.9.9.9", port, protocol, null, null, null, null, 0, 0, null);
    }

    @Test
    void pagesPastThe200RowCapWithoutRepeats() throws Exception {
        HttpServer server = stub(1000, 200);
        try {
            List<Proxmint.FreeProxy> rows = Proxmint.fetch(api(server), new Proxmint.Filters().country("DE").limit(350));
            assertEquals(IntStream.range(0, 350).boxed().toList(), rows.stream().map(Proxmint.FreeProxy::port).toList());
            assertNull(rows.get(0).latencyMs());
            assertEquals(2, seen.size());
            assertTrue(seen.stream().allMatch(q -> q.contains("pageSize=200") && q.contains("country=de")), seen.toString());
            assertFalse(seen.get(0).contains("protocol="), seen.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stopsAtTheEndOfThePool() throws Exception {
        HttpServer server = stub(30, 200);
        try {
            assertEquals(30, Proxmint.fetch(api(server), new Proxmint.Filters().limit(500)).size());
            assertEquals(1, seen.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void errorsCarryTheStatus() throws Exception {
        for (int status : new int[] {429, 503}) {
            HttpServer server = stub(0, status);
            try {
                Proxmint.ProxmintException e = assertThrows(
                        Proxmint.ProxmintException.class, () -> Proxmint.fetch(api(server), new Proxmint.Filters()));
                assertEquals(status, e.status());
            } finally {
                server.stop(0);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new Proxmint.Filters().limit(0));
    }

    @Test
    void httpsProxiesGetHttpUrls() {
        assertEquals("http://9.9.9.9:8080", proxy("https", 8080).url());
        assertEquals("socks5://9.9.9.9:1080", proxy("socks5", 1080).url());
        assertEquals(java.net.Proxy.Type.SOCKS, proxy("socks4", 1080).toJavaProxy().type());
        assertEquals(java.net.Proxy.Type.HTTP, proxy("https", 8080).toJavaProxy().type());
    }

    @Test
    void rotatingTakesTurnsAndRefusesSocks() {
        ProxySelector selector = Proxmint.rotating(List.of(proxy("http", 1), proxy("https", 2)));
        URI uri = URI.create("https://example.com");
        List<Integer> ports = Stream.generate(
                        () -> ((InetSocketAddress) selector.select(uri).get(0).address()).getPort())
                .limit(3)
                .toList();
        assertEquals(List.of(1, 2, 1), ports);
        assertThrows(IllegalArgumentException.class, () -> Proxmint.rotating(List.of(proxy("socks5", 1))));
        assertThrows(IllegalArgumentException.class, () -> Proxmint.rotating(List.of()));
    }

    @Test
    void jsonReaderHandlesEscapesNumbersAndNull() {
        Map<?, ?> v = (Map<?, ?>) new Proxmint.Json(" {\"a\" : [1, -2.5e1, true, null], \"b\":\"q\\\"\\u00e9\\n\", \"c\":{}} ").parse();
        assertEquals(Arrays.asList(1.0, -25.0, true, null), v.get("a"));
        assertEquals("q\"é\n", v.get("b"));
        assertEquals(Map.of(), v.get("c"));
        assertThrows(RuntimeException.class, () -> new Proxmint.Json("{\"a\":1").parse());
        assertThrows(RuntimeException.class, () -> new Proxmint.Json("[1] x").parse());
    }
}
