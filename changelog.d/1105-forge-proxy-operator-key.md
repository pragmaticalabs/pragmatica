### Fixed (2026-09-29 — #1105: Forge proxy routes sent no X-API-Key, and three proxied targets were unversioned)
- **With a sibling `aether.toml` declaring API keys, every Forge dashboard proxy call failed.** Such a file makes the
  embedded nodes run `SecurityMode.API_KEY`, and `ForgeServer` attached the operator key to only one call (the
  startup blueprint deploy). `ObservabilityProxyRoutes`, `AlertProxyRoutes`, `MetricsProxyRoutes` and
  `DeploymentRoutes` forwarded with no `X-API-Key`. Each call was refused on the node (`AUTH_FAILURE`) and surfaced as
  a failed dashboard call.
  Every Forge→node proxy request is now built by one `NodeHttp` builder. It attaches the key `ForgeServer` resolved
  from `aether.toml`, which is threaded as an `OperatorKey` through `ForgeApiHandler` and `ForgeRouter`.
- **Also fixed — three proxy targets answered 404 on every node.** The node serves the management API only under
  `/api/v1`. Forge forwarded to the unversioned `/api/alerts/active`, `/api/alerts/history`, `/api/thresholds` and
  `/api/metrics/history`; they now go to `/api/v1/...`. The metrics history target was not in the ticket.
- [verified: `aether/forge/forge-tests/.../ForgeProxyApiKeyForgeTest` — a real three-node Ember cluster under
  `API_KEY` security, driven through the production `ForgeApiHandler`. `/api/traces/stats`, `/api/alerts/active`, `/api/alerts/thresholds`,
  `/api/alerts/history` and `/api/metrics/history` answer 200 with the key, and a keyless handler in the same run is
  refused with the node's HTTP 401/403 (the in-run control)]
- `[unverified: the ForgeServer hop (passing its resolved key into ForgeApiHandler) is pinned by compile only; the
  test builds the handler through the same public factory]`
