### Added (2026-11-25 — #2032: TLS rotation without a listener gap, part 1)
- **An HTTP server's TLS context can now change without closing or rebinding the listener.** `LiveTls` holds the
  context an HTTP/1.1 or HTTP/3 listener hands each NEW connection; `LiveTls.swap` installs another one, and
  connections already open finish on the context they handshook with
  [verified: integrations/net/http-server/src/test/java/org/pragmatica/http/server/LiveTlsRotationTest.java].
- `HttpServerConfig.withLiveTls(LiveTls<SslContext>)` and `HttpServer.http3Server(config, LiveTls<QuicSslContext>, ...)`
  take a holder; `withTls(TlsConfig)` and `http3Server(config, QuicSslContext, ...)` keep their behaviour by wrapping
  one. A swap, or a holder built over a context its protocol cannot serve (a client context; a QUIC context for
  HTTP/1.1), fails with `HttpServerError.TlsContextRefused` and leaves the current context installed
  [verified: integrations/net/http-server/src/test/java/org/pragmatica/http/server/LiveTlsTest.java].
- The holder never retains or releases a context; an open connection keeps its own reference
  [mechanism: Netty's OpenSSL engine and QUIC connection retain their parent context until freed].
- `HttpServer.http3Server` given a client QUIC context now fails with `TlsContextRefused` instead of Netty's
  `IllegalArgumentException`.
