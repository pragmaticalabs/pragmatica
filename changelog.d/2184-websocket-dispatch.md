### Fixed (2026-10-13 — #2184: http-server WebSocket handlers never received events, and endpoints after the first never handshook)
- **No `WebSocketHandler` registered with `NettyHttpServer` ever received a message, and every endpoint
  after the first never completed its handshake.** Netty's handshake handler consumed the upgrade request
  before the per-channel state was stored, so `Open`, `Text`, `Binary` and `Close` were all dropped. Netty
  also installs a single handshake handler bound to the first endpoint's path, so an upgrade to the second
  or a later endpoint reached the pipeline tail and the client timed out. Aether's management server and
  Forge register three endpoints each (`/ws/dashboard`, `/ws/status`, `/ws/events`), so none of the
  dashboard's WebSocket feeds worked.
- A per-channel router now selects the endpoint by request path and installs exactly one protocol handler
  for it. The session is created on `HandshakeComplete`, so `Open` is always the first message a handler
  sees. A query string on the endpoint path is accepted.
  [verified: `integrations/net/http-server/src/test/java/org/pragmatica/http/server/WebSocketEndpointTest.java`]
- `Close` is delivered exactly once, when an opened connection goes inactive. That covers a client close
  frame, a server-side `WebSocketSession.close()`, and a connection dropped without a close frame.
  [verified: `integrations/net/http-server/src/test/java/org/pragmatica/http/server/WebSocketEndpointTest.java`]
- An upgrade to a path with no registered endpoint is refused with `404` and the connection is closed. It
  previously reached the plain HTTP handler.
  [verified: `integrations/net/http-server/src/test/java/org/pragmatica/http/server/WebSocketEndpointTest.java`]
- Through a real socket, a management server's `/ws/status` now delivers its status broadcast, and
  `/ws/events` prompts for authentication on open.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/api/ManagementServerWebSocketFeedsTest.java`]
