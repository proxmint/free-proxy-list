/**
 * Zero-dependency client for the Proxmint free-proxy API.
 *
 * Every proxy this returns was proven alive by a real request made *through it*
 * within the last half hour — the endpoint is the same one that backs
 * https://proxmint.com/free-proxies. No key, no signup.
 */

const API = "https://proxmint.com/api/free-proxies";

/** Server caps pageSize here, so anything larger has to be paged. */
const MAX_PAGE_SIZE = 200;

const UA = "proxmint-npm/0.1.0 (+https://proxmint.com/free-proxies)";

export class ProxmintError extends Error {
  constructor(message, status) {
    super(message);
    this.name = "ProxmintError";
    this.status = status;
  }
}

/**
 * Fetch validated live proxies.
 *
 * @param {object} [options]
 * @param {"http"|"https"|"socks4"|"socks5"} [options.protocol]
 * @param {string}  [options.country]    ISO 3166-1 alpha-2, e.g. "de"
 * @param {"elite"|"anonymous"|"transparent"} [options.anonymity]
 * @param {"score"|"latency"|"uptime"|"checked"} [options.sort="score"]
 * @param {number}  [options.limit=20]   total rows wanted; paged automatically
 * @param {AbortSignal} [options.signal]
 * @returns {Promise<import("./index.js").Proxy[]>}
 */
export async function fetchProxies(options = {}) {
  const { protocol, country, anonymity, sort, limit = 20, signal } = options;

  if (!Number.isInteger(limit) || limit < 1) {
    throw new TypeError(`limit must be a positive integer, got ${limit}`);
  }

  const out = [];
  // The API caps a page at MAX_PAGE_SIZE, so a caller asking for more than that
  // would otherwise be silently truncated — the pool is bigger than one page.
  for (let page = 1; out.length < limit; page++) {
    const params = new URLSearchParams();
    if (protocol) params.set("protocol", protocol);
    if (country) params.set("country", country.toLowerCase());
    if (anonymity) params.set("anonymity", anonymity);
    if (sort) params.set("sort", sort);
    params.set("page", String(page));
    params.set("pageSize", String(Math.min(limit - out.length, MAX_PAGE_SIZE)));

    const res = await fetch(`${API}?${params}`, {
      headers: { accept: "application/json", "user-agent": UA },
      signal,
    });

    if (res.status === 429) {
      throw new ProxmintError("rate limited — the API allows 60 requests/minute per IP", 429);
    }
    if (!res.ok) {
      throw new ProxmintError(`Proxmint API returned ${res.status} ${res.statusText}`, res.status);
    }

    const body = await res.json();
    const rows = body?.rows ?? [];
    out.push(...rows);

    // Short page means we reached the end of the pool, not of what was asked for.
    if (rows.length === 0 || out.length >= (body?.total ?? 0)) break;
  }

  return out.slice(0, limit);
}

/** `ip:port` strings, the form most proxy clients want to be handed. */
export async function fetchProxyLines(options = {}) {
  const rows = await fetchProxies(options);
  return rows.map((p) => `${p.ip}:${p.port}`);
}

/** `protocol://ip:port` URLs, for anything that takes a proxy URL directly. */
export async function fetchProxyUrls(options = {}) {
  const rows = await fetchProxies(options);
  return rows.map((p) => `${p.protocol}://${p.ip}:${p.port}`);
}
