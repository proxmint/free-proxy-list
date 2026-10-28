\name{free_proxies}
\alias{free_proxies}
\title{Free Proxies from the 'Proxmint' List}
\description{
Fetches proxies from the 'Proxmint' free proxy list
(\url{https://proxmint.com/free-proxies}). Every proxy in the list was checked
with a real request made through it within the last 30 minutes, and dead ones
are dropped. No API key is needed.
}
\usage{
free_proxies(protocol = NULL, country = NULL, anonymity = NULL,
             sort = c("score", "latency", "uptime", "checked"), limit = 20)
}
\arguments{
  \item{protocol}{\code{"http"}, \code{"https"}, \code{"socks4"} or \code{"socks5"}, or \code{NULL} for all.}
  \item{country}{A two-letter ISO 3166-1 country code such as \code{"de"}, or \code{NULL} for any country.}
  \item{anonymity}{\code{"elite"}, \code{"anonymous"} or \code{"transparent"}, or \code{NULL} for any.}
  \item{sort}{Sort order: \code{"score"} (the default), \code{"latency"}, \code{"uptime"} or \code{"checked"} (most recently checked first).}
  \item{limit}{How many proxies to return. Requests for more than 200 are fetched in pages.}
}
\details{
The public API allows 60 requests a minute per IP address. If the list cannot be
fetched (no connection, rate limited, server error), a message explains why and
\code{NULL} is returned invisibly.
}
\value{
A data frame with one row per proxy and the columns \code{ip}, \code{port}
(integer), \code{protocol}, \code{country_code}, \code{country_name},
\code{anonymity} (\code{NA} when unknown), \code{latency_ms} (integer),
\code{uptime_pct}, \code{score} and \code{last_checked} (\code{POSIXct}, UTC).
It has fewer than \code{limit} rows when fewer proxies match. \code{NULL}
(invisibly) if the list could not be fetched.
}
\seealso{\code{\link{proxy_urls}}}
\examples{
\donttest{
proxies <- free_proxies(protocol = "socks5", limit = 5)
if (!is.null(proxies)) proxy_urls(proxies)
}
}
