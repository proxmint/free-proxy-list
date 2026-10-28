# Runs without a network connection: argument checks, row parsing, URLs.
library(proxmint)

fails <- function(expr) inherits(try(expr, silent = TRUE), "try-error")
stopifnot(
  fails(free_proxies(protocol = "ftp")),
  fails(free_proxies(country = "germany")),
  fails(free_proxies(limit = 0)),
  fails(free_proxies(limit = 2.5))
)

rows <- jsonlite::fromJSON('[
  {"ip":"1.2.3.4","port":1080,"protocol":"socks5","countryCode":"de","countryName":"Germany",
   "anonymity":null,"latencyMs":120,"uptimePct":99,"score":95,"lastCheckedAt":"2026-10-28T10:56:12.583Z"},
  {"ip":"5.6.7.8","port":3128,"protocol":"http","countryCode":"us","countryName":"United States",
   "anonymity":"elite","latencyMs":250,"uptimePct":80.5,"score":70,"lastCheckedAt":"2026-10-28T10:50:00.000Z"}
]')
df <- proxmint:::as_proxy_frame(rows)
stopifnot(
  identical(names(df), c("ip", "port", "protocol", "country_code", "country_name", "anonymity",
                         "latency_ms", "uptime_pct", "score", "last_checked")),
  identical(df$port, c(1080L, 3128L)),
  identical(df$anonymity, c(NA, "elite")),
  inherits(df$last_checked, "POSIXct"),
  identical(format(df$last_checked[1], "%Y-%m-%d %H:%M:%S", tz = "UTC"), "2026-10-28 10:56:12"),
  identical(proxy_urls(df), c("socks5://1.2.3.4:1080", "http://5.6.7.8:3128")),
  identical(proxy_urls(data.frame(protocol = "https", ip = "9.9.9.9", port = 8080L)), "http://9.9.9.9:8080"),
  identical(proxy_urls(NULL), character()),
  nrow(proxmint:::as_proxy_frame(NULL)) == 0
)

# Paging past the 200-row cap: the page size stays fixed, because the server skips
# (page - 1) * pageSize rows. A stubbed API serves a pool of 1000.
ns <- asNamespace("proxmint")
calls <- character()
unlockBinding("get_json", ns)
assign("get_json", function(u) {
  calls <<- c(calls, u)
  page <- as.integer(sub(".*[?&]page=([0-9]+).*", "\\1", u))
  size <- as.integer(sub(".*pageSize=([0-9]+).*", "\\1", u))
  n <- (page - 1) * size + seq_len(max(0, min(size, 1000 - (page - 1) * size))) - 1
  list(rows = data.frame(ip = rep("1.2.3.4", length(n)), port = n, protocol = rep("http", length(n))),
       total = 1000)
}, ns)
stopifnot(
  identical(free_proxies(limit = 350)$port, 0:349),
  length(calls) == 2,
  all(grepl("pageSize=200", calls))
)
