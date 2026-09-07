export type Protocol = "http" | "https" | "socks4" | "socks5";
export type Anonymity = "elite" | "anonymous" | "transparent";
export type Sort = "score" | "latency" | "uptime" | "checked";

export interface Proxy {
  ip: string;
  port: number;
  protocol: Protocol;
  /** ISO 3166-1 alpha-2, lowercase. `null` when geolocation is unknown. */
  countryCode: string | null;
  countryName: string | null;
  /**
   * `null` where anonymity could not be measured — never a guess. An http proxy
   * carrying an https request opens a CONNECT tunnel and has no opportunity to
   * add headers, so silence there means "unknown", not "elite".
   */
  anonymity: Anonymity | null;
  /** Round trip through the proxy on its last successful check. */
  latencyMs: number | null;
  /** Share of our checks this proxy has passed (EWMA, 0–100). */
  uptimePct: number;
  /** Reliability rank combining uptime, latency and recency. */
  score: number;
  /** ISO timestamp of the last check. */
  lastCheckedAt: string | null;
}

export interface FetchOptions {
  protocol?: Protocol;
  /** ISO 3166-1 alpha-2, case-insensitive. */
  country?: string;
  anonymity?: Anonymity;
  /** @default "score" */
  sort?: Sort;
  /** Total rows wanted. Paged automatically past the API's 200/page cap. @default 20 */
  limit?: number;
  signal?: AbortSignal;
}

export declare class ProxmintError extends Error {
  name: "ProxmintError";
  status?: number;
}

export declare function fetchProxies(options?: FetchOptions): Promise<Proxy[]>;
/** `ip:port` strings. */
export declare function fetchProxyLines(options?: FetchOptions): Promise<string[]>;
/** `protocol://ip:port` URLs. */
export declare function fetchProxyUrls(options?: FetchOptions): Promise<string[]>;
