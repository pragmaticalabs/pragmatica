### Fixed
- HTTP/1.1 server startup refuses failed TLS configuration instead of opening a plaintext listener, reports the actual ephemeral port, and exposes response-flush completion for graceful application shutdown.
