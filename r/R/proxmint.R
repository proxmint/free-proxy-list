API <- "https://proxmint.com/api/free-proxies"
# The server caps a page at 200 rows, so larger requests are paged.
MAX_PAGE_SIZE <- 200
UA <- "proxmint-r/0.1.1 (+https://proxmint.com/free-proxies)"

free_proxies <- function(protocol = NULL, country = NULL, anonymity = NULL,
                         sort = c("score", "latency", "uptime", "checked"),
                         limit = 20) {
  if (!is.null(protocol)) protocol <- match.arg(protocol, c("http", "https", "socks4", "socks5"))
  if (!is.null(anonymity)) anonymity <- match.arg(anonymity, c("elite", "anonymous", "transparent"))
  if (!is.null(country) && !(is.character(country) && length(country) == 1 && grepl("^[A-Za-z]{2}$", country))) {
    stop("`country` must be a two-letter ISO 3166-1 code such as \"de\".", call. = FALSE)
  }
  sort <- match.arg(sort)
  if (!is.numeric(limit) || length(limit) != 1 || is.na(limit) || limit < 1 || limit %% 1 != 0) {
    stop("`limit` must be a positive whole number.", call. = FALSE)
  }

  # A failed request warns with the HTTP status, then errors; report whichever comes first.
  fail <- function(e) {
    message("Could not fetch the Proxmint free proxy list: ", conditionMessage(e))
    invisible(NULL)
  }
  tryCatch(fetch_proxies(protocol, tolower(country), anonymity, sort, limit), error = fail, warning = fail)
}

proxy_urls <- function(proxies) {
  if (is.null(proxies) || nrow(proxies) == 0) return(character())
  # An "https" proxy is an HTTP proxy that can tunnel TLS with CONNECT, so clients reach
  # it over http://; an https:// URL makes them attempt TLS to the proxy itself.
  paste0(sub("^https$", "http", proxies$protocol), "://", proxies$ip, ":", proxies$port)
}

fetch_proxies <- function(protocol, country, anonymity, sort, limit) {
  frames <- list(as_proxy_frame(NULL))
  got <- 0
  page <- 1
  repeat {
    query <- c(protocol = protocol, country = country, anonymity = anonymity, sort = sort,
               page = page, pageSize = min(limit - got, MAX_PAGE_SIZE))
    body <- get_json(paste0(API, "?", paste(names(query), query, sep = "=", collapse = "&")))
    if (NROW(body$rows) == 0) break
    frames[[page + 1]] <- as_proxy_frame(body$rows)
    got <- got + NROW(body$rows)
    # The pool can run out before `limit` is reached.
    if (got >= limit || isTRUE(got >= body$total)) break
    page <- page + 1
  }
  out <- do.call(rbind, frames)
  out <- out[seq_len(min(limit, nrow(out))), , drop = FALSE]
  rownames(out) <- NULL
  out
}

get_json <- function(u) {
  con <- url(u, method = "libcurl", headers = c(`User-Agent` = UA, Accept = "application/json"))
  on.exit(close(con))
  jsonlite::fromJSON(paste(readLines(con, warn = FALSE, encoding = "UTF-8"), collapse = "\n"))
}

# API rows (camelCase, nullable) -> a typed data frame with snake_case columns.
as_proxy_frame <- function(rows) {
  n <- NROW(rows)
  col <- function(name) if (name %in% names(rows)) rows[[name]] else rep(NA, n)
  data.frame(
    ip = as.character(col("ip")),
    port = as.integer(col("port")),
    protocol = as.character(col("protocol")),
    country_code = as.character(col("countryCode")),
    country_name = as.character(col("countryName")),
    anonymity = as.character(col("anonymity")),
    latency_ms = as.integer(col("latencyMs")),
    uptime_pct = as.numeric(col("uptimePct")),
    score = as.numeric(col("score")),
    last_checked = as.POSIXct(as.character(col("lastCheckedAt")), format = "%Y-%m-%dT%H:%M:%OSZ", tz = "UTC"),
    stringsAsFactors = FALSE
  )
}
