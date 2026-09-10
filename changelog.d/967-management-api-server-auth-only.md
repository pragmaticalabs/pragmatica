### Fixed (2026-09-10 — #967: with `[cluster] tls = true` every HTTP listener demanded a cluster-CA client certificate that never exists on disk, so the management API AND every deployed slice were unreachable, and the container liveness probe could not pass)
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
- **The app-HTTP listener had the same defect, with a worse blast radius.** It was built from the same
  `Mutual` config, so **every client of every deployed slice** had to present a cluster-CA client
  certificate. `serverAuthOnly()` now applies there too, at boot and on rotation.
- **`[app-http.tls]` gives the user-facing listener its own identity.** Reusing the cluster certificate
  is wrong beyond client auth: its subject is the NODE ID (`CN=primary-core-0`, observed on a live
  cluster) and its issuer is an internal CA no public client trusts, so a caller gets both an untrusted
  issuer and a name that is not the service it dialled. `cert_path` and `key_path` are required
  together — half an identity is a mistake, and silently falling back to the cluster certificate would
  hide it behind a listener that starts and then rejects every real client. Absent the section, the
  listener falls back to the cluster identity with client auth stripped: reachable service-to-service,
  not suitable for public traffic.
- **A bootstrap failure no longer reports a count nobody measured.** `BootstrapError.QuorumNotEstablished`
  was constructed with a hardcoded `0`, so a cluster that had formed correctly and merely could not be
  QUERIED reported `0/2 nodes healthy`. Measured 2026-09-10 on a live 3-node cluster whose own log read
  `Quorum established — consensus available` at the moment that message was printed and the cluster torn
  down. `waitForQuorum` now records what the cluster view actually reported each poll; on timeout it
  reports that number, or says the view was never readable and the count is **UNKNOWN — not zero**.
