### Fixed (2026-09-11 — #998: the registry endpoint omitted the management port, so every registry-based call targeted 443)

- **One endpoint builder, where there were two that disagreed.** `BootstrapPhasePost` computed the
  management endpoint twice: `buildResult` appended `operations.ports.management`, and
  `registerClusterLocally` — the writer of the PERSISTED `~/.aether/clusters.toml` entry, which is the
  single value every later management command resolves — appended nothing. So a cluster was registered as
  `https://<ip>`, `URI.create(endpoint + path)` sent the request to the scheme default 443, and the
  management API was on 8080. Both paths now read `BootstrapPhasePost.managementEndpoint`.
  [mechanism: the drift was possible because the value had two constructions; it now has one, and a test
  asserts the reported endpoint IS the registered one]
- **Systematic, not cloud-specific.** The omission is in the only registry writer and does not branch on
  target type, so every cluster with at least one collected address was registered port-less. Measured on
  the operator's live registry: **5 of 5 entries carry no port** — including the two that are not
  cloud-bootstrapped. `resolveEndpoint()` is shared by `getPath`/`postPath`/`putPath`, so the broken entry
  was not specific to `destroy`; `destroy` is where it was noticed, because #995's fix made the failure
  visible.
- **The no-address fallback read a hardcoded `9090`**, which is not the management port under any
  configuration (`PortMapping.defaultPortMapping()` is 8080). It now reads config like the other branch.
- **Node enumeration is a GATE: an unreachable cluster is no longer treated as an empty one.**
  `fetchNodeIds` returned `List<String>` with `.or(List.of())`, which flattened a `ConnectException` into
  an empty node list — so DRAIN_NODES and SHUTDOWN_NODES were skipped and three paid VMs were deleted
  **without a graceful drain**, under a summary reading `Nodes processed: 0 / Drains succeeded: 0/0`. It
  now returns `Result<List<String>>` and a failure **refuses the destroy before anything is deleted**: no
  VM, firewall, key or ledger is touched, the registry entry is kept, and the exit code is non-zero, so the
  whole operation is retryable. `--force-undrained` is the operator electing the undrained teardown.
  [mechanism: refusing is recoverable and an undrained destroy is not — nothing deleted means the retry is
  the same command]
- **A port-less endpoint is now named as an observation.** The refusal reports that the endpoint carries no
  explicit port, which scheme default the request therefore used, and the port an Aether management API
  listens on. It states the observed shape and does not assert that the port is the cause. This is the
  line an operator gets on any entry written before this fix, which is all of them.
- **Two cross-cluster leaks on the same path, found while establishing the scope and fixed here.** Without
  the first, fixing the port alone would NOT have made the reported command work:
  - `resolveApiKey()` reads `registry.current()`'s `api_key_env`, so a `--cluster X` destroy presented the
    **ACTIVE** cluster's credential to X and would have earned a 401 the moment the port was right. The
    target's own key is now installed, file first then its recorded `api_key_env`.
  - a `--cluster X` whose registry entry is absent or blank installed no endpoint override at all, so
    `resolveEndpoint()` fell through to `registry.current()`: destroy would enumerate, **drain and shut
    down a healthy unrelated cluster's nodes** while reaping X's cloud resources. The synthesized entry is
    deliberate (a cluster whose entry is gone must still have its resources reaped) but it must not borrow
    another cluster's nodes, so enumeration now fails with that hazard named and issues no request.
- Pinned by `BootstrapPhasePostEndpointTest` (the live cluster's shape; a non-default port, so a literal
  `:8080` reddens; the `http` scheme under `tls.autoGenerate = false`, re-pinning #209 through the
  consolidation; the localhost fallback asserted both as the configured port and as *not* `9090`; and the
  reported-equals-registered symmetry) and `ClusterDestroyCommandTest.EnumerationGate` (zero cleanups and
  zero registry removals on refusal, with the `--force-undrained` run as the positive control that reaches
  both; zero HTTP requests for a target with no endpoint, with a one-request control on the same stub; the
  port note, with a fully-specified endpoint as the negative control).
- **[unverified: no cloud run]** — every claim is established in-JVM. That a cloud `cluster destroy` now
  enumerates, drains and shuts down its nodes is **not** demonstrated here; it needs one bootstrap/destroy
  cycle against a real Hetzner cluster, whose registry entry must be written by this build (a pre-existing
  entry is port-less and will, correctly, be refused).
