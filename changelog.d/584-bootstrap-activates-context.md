### Fixed (2026-09-13 — #584: a fresh bootstrap did not become the cluster the next command reached)
- **Two defects, one symptom.** (a) `BootstrapPhasePost.registerClusterLocally` called
  `ClusterRegistry.add`, whose contract keeps whatever context is current
  (`ClusterRegistryTest.saveAndLoad_preservesState_whenRoundTripped` pins it), so a fresh bootstrap never
  became the active context. (b) Even with the context set, `AetherCli.main` installed `localhost:8080`
  as the endpoint override **unconditionally** whenever no `--connect`/`--config` was given, so
  `ClusterHttpClient.resolveEndpoint` never reached `registry.current()` and the context steered nothing
  but `destroy`/`rotate-key`, which install their own override. The ticket's bare `ConnectException`
  from `cluster scale` was `localhost:8080` with nothing listening — not the stale context, as this
  fragment first said (caught in review by running the shaded jar against a scratch registry).
- Bootstrap now registers **and activates** (`registerAndActivate` = `add(…).use(name)`), prints
  `Active cluster context: <name>` once the registry is saved, and the CLI resolves every command's
  endpoint with one precedence: explicit `--connect`/`--endpoint`/`--config` > active cluster context
  (dialled with the credential its `api_key_env` names — `AetherCli.resolveApiKey` gained the same
  registry fallback `ClusterHttpClient.resolveApiKey` already had) > built-in `localhost:8080`, which
  applies only when no context is set. `cluster … --cluster <name>` still targets that entry.
  `cli.md` states the contract under Options and in the bootstrap section.
  [verified: `aether/cli` `AetherCliEndpointPrecedenceTest` — the real entrypoint (`AetherCli.main`
  in a child JVM under a redirected `user.home`) with a post-bootstrap registry: `cluster scale` and
  `status` with no flags dial the context's listener, never localhost; `--connect` and `--cluster`
  win; `BootstrapPhasePostContextTest` — `registerAndActivate` against a temp registry, and the real
  `registerClusterLocally` step through a registry seam saves the bootstrapped cluster as current and
  announces it]
- **Blast radius, stated:** every command's target changes when a registry with a current context
  exists and no `--connect`/`--config` is given — all 36 registered top-level subcommands (minus `generate-completion`) whose requests go through
  `AetherCli.resolveNodeUri` and every `cluster`/`stream`/`storage`/`deploy` command routed through
  `ClusterHttpClient` now dial the context instead of `localhost:8080`. An operator with a context set
  who wants a local compose node passes `--connect localhost:8080`. A registry that cannot be read is
  treated as no context. [design intent — unverified beyond the four entrypoint runs above]
- **The ticket's first half — endpoint written without the management port — was already fixed by
  #998** (`BootstrapPhasePost.managementEndpoint` appends `operations.ports.management`, pinned by
  `BootstrapPhasePostEndpointTest`). Nothing changed there.
