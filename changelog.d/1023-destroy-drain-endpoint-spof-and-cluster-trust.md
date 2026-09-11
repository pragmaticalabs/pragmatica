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

**Not verified without a live cloud run.** The original failure was only reproducible on a live
TLS-auto_generate cloud cluster. What is pinned above is unit-level: endpoint-candidate construction and
fallthrough, the retarget, the restore-and-refuse path, and which trust anchor is installed. That a
drain **completes** end-to-end against a real cluster whose first recorded node is dead is
**[design intent — unverified]** — it requires a cloud bootstrap this change was not able to run.
