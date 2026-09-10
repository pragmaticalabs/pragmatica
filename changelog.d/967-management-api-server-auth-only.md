### Fixed (2026-09-10 — #967: with `[cluster] tls = true` the management API required a cluster-CA client certificate that never exists on disk, so the API was unreachable and the container liveness probe could not pass)
- **The node handed its single `Mutual` TLS config to every listener it started.** `Main.resolveTls`
  builds `TlsConfig.fromProvider(...)`, which returns `Mutual`, and `TlsContextFactory` maps `Mutual`
  to `ClientAuth.REQUIRE`. Correct for node-to-node cluster transport; wrong for the operator-facing
  management API, which authenticates callers with an API key (`SecurityPolicy.apiKeyRequired()`).
  Under `auto_generate` the CA is derived from the cluster secret and **never written to disk**, so no
  operator could present a client certificate even in principle.
- **`TlsConfig.serverAuthOnly()`** returns a server-auth-only view of an identity, and the management
  listener now uses it — at boot **and** on certificate rotation. Fixing only the boot path would have
  left a cluster that bootstrapped correctly losing its management API at the first renewal.
- **The container liveness probe could not pass under any scheme.** Plaintext was rejected with
  `NotSslRecordException`; TLS without a client certificate was refused. The probe is now
  TLS-agnostic — HTTPS first, falling back to HTTP — so it works whether `tls` is on or off.
- **Measured consequence, on a real 3-node Hetzner cluster.** The nodes formed correctly and their own
  logs read `Quorum established — consensus available`, `CTM: desired=3, active=3, ready=3 → Converged`.
  The container stayed `unhealthy`, the bootstrap readiness gate reads container health, and so
  `aether cluster bootstrap` reported `Quorum not established: 0/1 nodes healthy` and **destroyed a
  working cluster**. Cloud bootstrap was impossible on the default configuration path.
- Pinned by `TlsConfigTest.mutualConfig_requiresClientCertificate_andServerAuthOnlyViewDoesNot`, which
  asserts `SSLEngine.getNeedClientAuth()`/`getWantClientAuth()` rather than the record shape, and keeps
  the `Mutual` arm as a positive control — without it a green result would only prove the assertion
  cannot detect the difference.
