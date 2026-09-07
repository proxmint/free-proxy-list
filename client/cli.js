#!/usr/bin/env node
/**
 * `npx proxmint` — print validated live proxies, one per line.
 *
 * Output defaults to bare `ip:port` so it pipes straight into anything.
 * Argument parsing is node's own util.parseArgs; this package has no deps.
 */
import { parseArgs } from "node:util";
import { fetchProxies, ProxmintError } from "./index.js";

const VERSION = "0.1.0";

const HELP = `
  proxmint — free proxies, re-validated every 30 minutes

  Usage
    npx proxmint [options]

  Options
    -p, --protocol <p>   http | https | socks4 | socks5
    -c, --country <cc>   ISO 3166-1 alpha-2, e.g. de
    -a, --anonymity <a>  elite | anonymous | transparent
    -s, --sort <s>       score | latency | uptime | checked   (default: score)
    -n, --limit <n>      how many to print                    (default: 20)
        --url            print protocol://ip:port
        --json           print the full rows as JSON
    -h, --help
    -v, --version

  Examples
    npx proxmint
    npx proxmint -p socks5 -c de -n 50
    npx proxmint --sort latency --url
    npx proxmint --json | jq '.[] | select(.uptimePct > 90)'

  Every entry was proven alive by a real request through it, minutes ago.
  These are free public proxies run by strangers: use them for testing, never
  for logged-in or sensitive traffic.  https://proxmint.com/free-proxies
`;

let values;
try {
  ({ values } = parseArgs({
    options: {
      protocol: { type: "string", short: "p" },
      country: { type: "string", short: "c" },
      anonymity: { type: "string", short: "a" },
      sort: { type: "string", short: "s" },
      limit: { type: "string", short: "n" },
      url: { type: "boolean", default: false },
      json: { type: "boolean", default: false },
      help: { type: "boolean", short: "h", default: false },
      version: { type: "boolean", short: "v", default: false },
    },
  }));
} catch (err) {
  process.stderr.write(`proxmint: ${err.message}\n\nRun \`npx proxmint --help\`.\n`);
  process.exit(2);
}

if (values.help) {
  process.stdout.write(`${HELP}\n`);
  process.exit(0);
}
if (values.version) {
  process.stdout.write(`${VERSION}\n`);
  process.exit(0);
}

const limit = values.limit === undefined ? 20 : Number(values.limit);
if (!Number.isInteger(limit) || limit < 1) {
  process.stderr.write(`proxmint: --limit must be a positive integer, got "${values.limit}"\n`);
  process.exit(2);
}

try {
  const rows = await fetchProxies({
    protocol: values.protocol,
    country: values.country,
    anonymity: values.anonymity,
    sort: values.sort,
    limit,
  });

  if (rows.length === 0) {
    // Not an error: a narrow filter legitimately matches nothing right now.
    process.stderr.write("proxmint: no proxies matched those filters\n");
    process.exit(0);
  }

  if (values.json) {
    process.stdout.write(`${JSON.stringify(rows, null, 2)}\n`);
  } else {
    const line = values.url ? (p) => `${p.protocol}://${p.ip}:${p.port}` : (p) => `${p.ip}:${p.port}`;
    process.stdout.write(`${rows.map(line).join("\n")}\n`);
  }
} catch (err) {
  // A bad filter value comes back as a 400 from the API; surface it as written
  // rather than dumping a stack trace at someone piping this into a shell.
  const msg = err instanceof ProxmintError ? err.message : `could not reach the API — ${err.message}`;
  process.stderr.write(`proxmint: ${msg}\n`);
  process.exit(1);
}
