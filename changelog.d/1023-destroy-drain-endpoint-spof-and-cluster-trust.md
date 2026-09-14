### Fixed (2026-09-12 — #1023: `cluster destroy` could not drain at all on a TLS-auto_generate cloud cluster)

Observed live on a 5-node Hetzner cluster on 2026-09-11, final summary
`Drains succeeded: 0/0, Shutdowns succeeded: 0/0`. Two independent failures, fixed separately because
they have different mechanisms and different risk. Both are repaired from data bootstrap **already
persists** — no new field was added to the registry or the bootstrap state.

- **The registry stores ONE node's address as the cluster endpoint, and teardown is exactly when that
  node is most likely to be gone.** `BootstrapPhasePost.managementEndpoint` builds the persisted entry
  from `addresses().getFirst()`, so the single recorded endpoint is a single point of failure: the
  observed entry named a node the operator had deliberately killed, enumeration got a `ConnectException`,
  and destroy refused — while three healthy nodes were serving the same route. `destroy` now falls back
  to the other node addresses `BootstrapPhaseCollect` already records in
  `BootstrapState.collectedAddresses`, trying the recorded endpoint first and each sibling in turn.
  Scheme and port are borrowed from the recorded entry rather than defaulted, so #998's refusal to invent
  a port applies unchanged to the candidates built from it.
  [mechanism: `NODE_LIFECYCLE_LIST` is `LEADER`-targeted and `ManagementServer.tryForwardIfNotLeader`
  forwards it, so any live node serves the same cluster-wide list; `NODE_DRAIN` and `NODE_SHUTDOWN`
  forward the same way]
  [verified: `aether/cli` `ClusterDestroyCommandTest.SingleEndpointSpofAndClusterTrust` — asserted on the
  ordered hosts the requests carried, not on a call count]
- **The endpoint that answers becomes the target for the rest of the destroy.** Enumerating from a live
  node and then draining against the dead one would be a fix in name only. When every candidate fails the
  override is restored to the recorded endpoint, the PRIMARY failure is the one reported, and #998's
  refusal stands — nothing is deleted.
- **The CLI held no trust anchor for the CA the cluster generated for itself, so TLS failed before drain
  could run.** `SSLHandshakeException … PKIX path building failed`. Traced rather than inferred:
  `ClusterHttpClient.enableClusterTrust` had exactly ONE caller,
  `ClusterBootstrapOrchestrator.configureClusterHttpClient`, on the bootstrap path. `destroy` installed no
  `SSLContext` at all, so it used the JDK default trust store, and the cluster's leaf certificates are
  signed by a CA derived from `cluster_secret` via HKDF. What refuses the connection is the default
  `X509TrustManager`: there is no path from the presented leaf to any anchor it holds. `destroy` now
  installs the same anchor bootstrap installs, derived from the `cluster_secret` its bootstrap state
  already records.
  [mechanism: verification is made strictly NARROWER than the JDK default — the cluster's own CA and
  nothing else. `enableTlsSkipVerify` (trust-all) is deliberately not used; #209 removed it from the
  bootstrap path and it must not reappear on a path that presents the operator API key and issues
  shutdown commands]
  [verified: `ClusterDestroyCommandTest` — the client is replaced for an https endpoint with a recorded
  secret, and left untouched for plain http]
- **When the secret is absent, destroy says so instead of failing with a bare PKIX error — and disables
  nothing.** An https endpoint whose bootstrap state records no `cluster_secret` gets an explicit note
  naming what cannot be derived and stating that certificate verification is deliberately not disabled to
  work around it.
  [verified: `prepareClusterTrust_saysSoAndDisablesNothing_whenHttpsButNoSecretIsRecorded` asserts the
  HTTP client is the SAME instance afterwards]
- **`Drains succeeded: 0/0` now says it is a SKIP.** A ratio over an empty set reads like a pass; the
  count was always honest, but nothing said which of the two it described. Both the drain and shutdown
  lines now carry `(SKIPPED — no nodes were enumerated…)` when the node list was empty.

- **Certificate validation is enforced, and that is demonstrated rather than asserted.** With a
  cluster-CA-signed certificate served by a stub node, the **correct** recorded secret completes the
  handshake and drains (`Drains succeeded: 3/3`); a **wrong** secret, everything else identical, is
  rejected with `PKIX path validation failed … signature check failed` and refuses. Had this been
  "fixed" by disabling validation, that control would have passed.
  [mechanism: the installed anchor is the derived cluster CA and nothing else, so a certificate from a
  different cluster's secret has no path to it — #209's MITM property, preserved on the destroy path]

- **This does NOT make drain work, and #1023 stays open.** Verified on a live 5-node Hetzner cluster
  with the registry-named node deleted at the provider: the trust path was built, the CLI fell through
  to a live member, and enumeration succeeded — then the cluster **refused every drain with HTTP 409,
  "Disruption budget exceeded"**. A full teardown necessarily drains below the budget, and `destroy` has
  no way to say "I am tearing the whole thing down". **Tracked as #1032**; this change deliberately does
  not lower that guard, which is correct and did exactly its job.
  [mechanism: a 409 is an application-level answer, so TLS, authentication, routing and leader
  forwarding all worked — the refusal is evidence about the layers beneath it]
- **An honest diagnostic found a real defect on its first live run.** The disruption-budget problem has
  presumably existed all along and was unfindable because the old path rendered the failure as
  `Drains succeeded: 0/0` **under a "destroyed successfully" line**. The same run now prints `0/3`, an
  explicit warning, and does not claim success. That is the argument for fixing diagnostics, stated as
  data rather than as principle.
- **Operator-facing cost this introduces:** a fully unreachable N-node cluster now takes roughly
  **(N−1) × 130 s** before refusing — about 17 minutes for a dead 9-node cluster, in an outage, which is
  when `destroy` is actually run. Bounded, and the phase announcement names how many candidates will be
  tried before the wait begins. A bounded overall deadline is design work, filed separately rather than
  bundled here.
- **The fallback list is a bootstrap-time snapshot.** `collectedAddresses` is written once by
  `BootstrapPhaseCollect` and never updated, so a node that auto-heal replaces *later* at a new address
  is not a fallback candidate.

**Verification scope — read this before quoting the above.** The CLI path is exercised end-to-end on the
built `aether.jar` (isolated `-Duser.home`) against a **stub node**, over real TLS, with request-level
attribution (11 requests, none addressed to the dead endpoint) and a negative control that fails for the
right reason. It was then confirmed on a live multi-node cloud cluster with a genuinely deleted endpoint
node, where a CA derived from the recorded secret validated a certificate issued by a **real node at
first leadership** (`curl` without it `ssl_verify=20`, with it `ssl_verify=0`) and the CLI fell through
to `primary-core-1`.
[verified: live 5-node Hetzner cluster, 2026-09-12 — trust path built, fallthrough from a deleted
endpoint to a live member, 3 nodes enumerated]

**What that live run did NOT establish: that a drain completes.** It did not — see #1032 above. Do not
read "the tests pass", or even "the fallthrough works", as "drain works"; that conflation is exactly
what let `0/0` read as success in the first place.
