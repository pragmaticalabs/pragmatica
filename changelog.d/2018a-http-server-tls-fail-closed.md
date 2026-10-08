### Fixed
- HTTP/1.1 server startup refuses a TLS configuration that fails to build instead of silently opening a plain-text listener on the TLS port, and reports the actual bound port when configured with port 0. A node whose TLS configuration is broken now fails to start (HttpServerError.TlsFailed, logged at ERROR) rather than serving HTTP.
