### Fixed (2026-11-25 — #3747: a swap can no longer install an HTTP/3 context that serves no connection)
- **`LiveTls.liveQuicTls` and `LiveTls.swap` refuse a server QUIC context that offers none of the ALPN tokens Netty's
  HTTP/3 codec serves (`h3`, `h3-29` to `h3-32`); a context offering only `h3` is admitted.**
  Before, a context offering none was admitted, `swap` returned success, and every later HTTP/3 connection failed with
  `QuicClosedChannelException`. It now fails with `HttpServerError.TlsContextRefused.NOT_HTTP3_ALPN` and the current
  context stays installed
  [verified: integrations/net/http-server/src/test/java/org/pragmatica/http/server/LiveTlsTest.java, LiveTlsRotationTest.java].
- `HttpServer.http3Server(config, QuicSslContext, ...)` inherits the check: a QUIC context with none of them fails
  with the same cause instead of binding a listener that completes no handshake.
- `LiveTls` javadoc: the "release once swapped out" rule now names the window between reading the holder and the
  engine retaining the context [unverified at runtime: no netty-tcnative in the repository].
