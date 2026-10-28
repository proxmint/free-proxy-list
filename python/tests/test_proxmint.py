"""Offline checks: paging, URL schemes, and that both rotators drop a failing proxy
and retry through the next one."""

import asyncio
import io
import json
import types
import urllib.error
import urllib.parse

import aiohttp
import httpx
import pytest

import proxmint
import proxmint.aiohttp
import proxmint.httpx


def stub_api(monkeypatch, total, status=200):
    """Serve a pool of `total` rows through urlopen; returns the queries made."""
    calls = []

    def urlopen(req, timeout):
        q = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(req.full_url).query))
        calls.append(q)
        if status != 200:
            raise urllib.error.HTTPError(req.full_url, status, "Nope", {}, None)
        page, size = int(q["page"]), int(q["pageSize"])
        start = (page - 1) * size
        rows = [{"ip": "10.0.0.1", "port": n, "protocol": "http"} for n in range(start, min(start + size, total))]
        return io.BytesIO(json.dumps({"rows": rows, "total": total}).encode())

    monkeypatch.setattr(proxmint.urllib.request, "urlopen", urlopen)
    return calls


def test_pages_past_the_200_row_cap(monkeypatch):
    calls = stub_api(monkeypatch, total=1000)
    assert [p["port"] for p in proxmint.free_proxies(limit=350)] == list(range(350))
    assert [(c["page"], c["pageSize"]) for c in calls] == [("1", "200"), ("2", "200")]


def test_stops_at_the_end_of_the_pool(monkeypatch):
    calls = stub_api(monkeypatch, total=30)
    assert len(proxmint.free_proxies(limit=500)) == 30
    assert len(calls) == 1


def test_bad_input_and_http_errors(monkeypatch):
    calls = stub_api(monkeypatch, total=10, status=429)
    with pytest.raises(ValueError):
        proxmint.free_proxies(limit=0)
    assert calls == []
    with pytest.raises(proxmint.ProxmintError) as e:
        proxmint.free_proxies(country="DE")
    assert e.value.status == 429 and calls[0]["country"] == "de"


def test_https_proxies_are_reached_over_http():
    assert proxmint.proxy_url({"protocol": "https", "ip": "1.2.3.4", "port": 8080}) == "http://1.2.3.4:8080"
    assert proxmint.proxy_url({"protocol": "socks5", "ip": "1.2.3.4", "port": 1080}) == "socks5://1.2.3.4:1080"


DEAD, GOOD, AUTH, SLOW = "http://10.0.0.1:1", "http://10.0.0.2:2", "http://10.0.0.3:3", "http://10.0.0.4:4"


class FakeTransport(httpx.BaseTransport):
    def __init__(self, proxy):
        self.proxy = str(proxy)

    def handle_request(self, request):
        if self.proxy == DEAD:
            raise httpx.ConnectError("refused", request=request)
        return httpx.Response(407 if self.proxy == AUTH else 200, text=self.proxy)


def test_httpx_drops_failing_proxies_and_retries(monkeypatch):
    monkeypatch.setattr(proxmint.httpx.httpx, "HTTPTransport", lambda proxy: FakeTransport(proxy))
    with httpx.Client(transport=proxmint.httpx.RotatingTransport([DEAD, AUTH, GOOD])) as client:
        assert client.get("http://example.com").text == GOOD  # dead, then 407, then good
        assert client.get("http://example.com").text == GOOD  # only the good one is left
    with httpx.Client(transport=proxmint.httpx.RotatingTransport([DEAD], tries=2)) as client:
        with pytest.raises(httpx.ConnectError):
            client.get("http://example.com")


class FakeRequest:
    proxy_headers = None

    def update_proxy(self, proxy, auth, headers):
        self.proxy = str(proxy)


def test_aiohttp_drops_failing_proxies_and_retries():
    async def handler(req):
        if req.proxy == DEAD:
            raise aiohttp.ServerDisconnectedError()
        if req.proxy == SLOW:
            await asyncio.sleep(5)
        return types.SimpleNamespace(status=407 if req.proxy == AUTH else 200, proxy=req.proxy, release=lambda: None)

    async def run():
        mw = proxmint.aiohttp.RotatingProxy([DEAD, AUTH, GOOD])
        assert (await mw(FakeRequest(), handler)).proxy == GOOD  # dead, then 407, then good
        assert [(await mw(FakeRequest(), handler)).proxy for _ in range(2)] == [GOOD, GOOD]
        slow_first = proxmint.aiohttp.RotatingProxy([SLOW, GOOD], timeout=0.05)
        assert (await slow_first(FakeRequest(), handler)).proxy == GOOD  # a hang costs one timeout
        with pytest.raises(aiohttp.ServerDisconnectedError):
            await proxmint.aiohttp.RotatingProxy([DEAD], tries=2)(FakeRequest(), handler)

    asyncio.run(run())
