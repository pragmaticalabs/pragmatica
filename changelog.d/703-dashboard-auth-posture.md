### Fixed (2026-09-13 — #703: dashboard demanded an API key that Forge/Ember never mint and the server did not require)
- **The API-key overlay was gated on the absence of a key in `sessionStorage`, never on whether the
  server wants one.** `AetherAuth.init()` showed the overlay whenever nothing was stored, then
  "validated" whatever was typed by sending it as `X-API-Key` on `GET /api/nodes/status` — a request
  that answers 200 for ANY value when management security is off, because
  `ManagementServer.handleRequest` runs `validateManagementSecurity` only when `securityEnabled`
  (`config.appHttp().securityEnabled()`, `NONE` under the in-JVM harness overloads of
  `AppHttpConfig.appHttpConfig`), and Forge's dashboard port (`ForgeRequestHandler`) has no gate at
  all. So the overlay accepted any non-empty string and taught operators that a credential existed
  and had been checked, when neither was true.
  [mechanism: `ManagementServer.handleRequest` `if (securityEnabled)` branch; `ForgeRequestHandler` has no credential check]
- The dashboard now **learns the posture from the gate itself**: with no stored key, `init()` sends
  the same status request bare — no header — and classifies the answer. 2xx means the server served
  the dashboard's data to an unauthenticated caller (posture `open`): no overlay, the app starts.
  401/403 means the gate refused (posture `key`): the overlay appears exactly as before. Anything
  else — 404, 5xx, unreachable — says nothing about the gate, so the dashboard falls back to asking,
  as before, never to `open`; a blank or arbitrary key is not accepted anywhere. A 401 mid-session
  (`onUnauthorized`) records the `key` posture regardless of what the load-time probe saw.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/api/DashboardAuthPostureContractTest.java` —
  structural assertions on the JS text, the same instrument as `DashboardPollingGateContractTest`;
  there is no JS runner in this repository]
- Probing the enforcement rather than reading a declared field was deliberate: a `securityEnabled`
  field on a status response could disagree with the gate (the comment-versus-code family), while
  the gate cannot disagree with itself. It also makes the fix independent of how #665 settles the
  harness default — a harness that starts minting keys will answer 401 and get the overlay.
  [design intent — unverified]
- `app.js` gates startup on `AetherAuth.isReady()` (key held OR posture `open`) instead of
  `hasValidKey()`. The `aether-auth-success` event is reused as the start signal for the open posture.
- Not changed, and a limit on where this fix bites: the dashboard's REST paths are unversioned
  (`/api/nodes/status`) while a real node serves only `/api/v1/...` (`ManagementRoute.API_BASE`), so
  against a real node the posture probe answers 404 with security off and the overlay still appears,
  and with security on a CORRECT key is still refused by the overlay's own validation (401 clears,
  then the router 404s and `r.ok` is false). That is the pre-existing dashboard-versus-versioned-API
  drift, not this ticket; against Forge — where the defect was reported — the port answers 200 and
  the overlay no longer appears. [unverified: no live Forge run in this round — the box was over the
  process gate; the behaviour follows from `ForgeRequestHandler` having no credential check]
