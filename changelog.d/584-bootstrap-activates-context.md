### Fixed (2026-09-13 — #584: a fresh bootstrap left the active context on the previous cluster)
- **The cluster just bootstrapped was registered but never made the active context.**
  `BootstrapPhasePost.registerClusterLocally` called `ClusterRegistry.add`, whose contract is to keep
  whatever context is current (`ClusterRegistryTest.saveAndLoad_preservesState_whenRoundTripped` pins
  it), so `[current] context` in `~/.aether/clusters.toml` stayed on whatever was active before — in
  the live case a cluster dead since July — and the first context-routed command after bootstrap
  (`cluster scale`) dialled it and failed with a bare `ConnectException` unless `--cluster` was passed.
  Bootstrap now registers and activates in one step (`registerAndActivate` = `add(…).use(name)`),
  prints `Active cluster context: <name>` once the registry is saved, and `cli.md` documents the
  contract. `add` itself is unchanged.
  [verified: `aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapPhasePostContextTest.java`
  — against a temp registry whose current context is another cluster; the printed line is not
  pinned, it sits inside the step that writes the operator's real registry]
- **The ticket's first half — endpoint written without the management port — was already fixed by
  #998** (`BootstrapPhasePost.managementEndpoint` appends `operations.ports.management`, pinned by
  `BootstrapPhasePostEndpointTest`). Nothing changed there.
