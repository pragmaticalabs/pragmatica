### Fixed (2026-09-28 — #1612: HTTP/1.1 and HTTP/3 server stop was unbounded and could not report failure)
- **The ticket's premise was false.** It said `stop()` resolved before the event loops terminated. In fact both servers already waited, through a listener on the future `shutdownGracefully()` returns, which is the termination future. The lines the ticket cited are the bind-failure branch of `onBind`, not `stop()`. [verified: integrations/net/http-server/src/test/java/org/pragmatica/http/server/HttpServerStopTest.java — the ordering and rebind tests pass against the pre-fix code]
- **`NettyHttpServer.stop()` and `Http3Server.stop()` now follow the #1610/#1614 shape.** They are a dependent chain:
  - the channel close is bounded (2 s);
  - owned groups shut down with no quiet period and are awaited, bounded caller-side (6 s);
  - the first failure is reported typed.
  
  A wedged event loop used to hang `stop()` forever; it now fails with a timeout within the bound. A failed termination used to report success. Every owned stop used to wait out the default 2 s quiet period. The `ServerShutdown` Javadoc explains why a quiet period protects nothing for HTTP. Shared groups are still left running. [verified: `HttpServerStopTest` — the wedged-loop and worker-termination tests fail against the pre-fix code]
- **A failed bind now terminates the event loops that create made before it reports `BindFailed`**, so no loop threads outlive a failed create. [verified: `HttpServerStopTest.create_bindFailure_*`, failing against the pre-fix code]
- **Callers survive the new failure outcome:**
  - `AetherNode`'s shutdown chain logs a failed management or app-HTTP stop and continues, instead of skipping storage drain and the cluster-node stop. [mechanism: `continuingPast` recovers to `unit` before the next `flatMap`; not exercised by a test]
  - `ManagementServer.stop()` and `AppHttpContext.stopServersAsync` wait for both listeners and report the first failure. [verified: aether/node/src/test/java/org/pragmatica/aether/http/fsm/AppHttpContextStopTest.java]
  - Both certificate rotations log a failed stop and restart anyway. [mechanism: recover before `restartWithNewBundle`; not exercised by a test]
