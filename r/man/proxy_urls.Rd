\name{proxy_urls}
\alias{proxy_urls}
\title{Proxy URLs}
\description{
Turns the rows returned by \code{\link{free_proxies}} into proxy URLs, the
form most HTTP clients take. An \code{"https"} proxy is an HTTP proxy that can
also tunnel TLS, so its URL starts with \code{http://}; \code{socks4} and
\code{socks5} proxies keep their own scheme.
}
\usage{
proxy_urls(proxies)
}
\arguments{
  \item{proxies}{A data frame returned by \code{\link{free_proxies}}, or \code{NULL}.}
}
\value{
A character vector with one URL per row, such as \code{"socks5://203.0.113.7:1080"}.
Empty if \code{proxies} is \code{NULL} or has no rows.
}
\examples{
proxies <- data.frame(protocol = "socks5", ip = "203.0.113.7", port = 1080L)
proxy_urls(proxies)
}
