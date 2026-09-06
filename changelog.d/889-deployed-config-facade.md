### Fixed (2026-09-06 — #889: a slice declaring a configuration section could not be created in a deployed cluster)
- **`ctx.config()` inside a deployed slice was always `NoOpConfigFacade`**, whose every `require*`
  method fails, so the `Result.all(...)` chain generated for
  `@ResourceQualifier(type = ConfigurationSection.class)` could never produce a record
  [mechanism: the deployment path is `SliceStore.loadSlice` → `DependencyResolver.resolveWithContext`
  → `SliceLoadingContext.sliceLoadingContext(invoker, resources, sliceId, nodeCodec)`, which routes
  through the three-argument `SliceCreationContext` factory and passes `NoOpConfigFacade.INSTANCE`;
  the config-carrying overloads had no production caller at all]. A working facade *was* built, at
  `NodeDeploymentState:1261`, and wired only to `notifyInitial` — the plumbing existed and pointed at
  the wrong place.
- This explains a fact previously recorded as an adoption gap: `ConfigurationSection` had **zero**
  consumers across the repo, the 24 examples and the ticketing demo. The likelier reading is that it
  could not work, so nothing used it. The books teach it as a first-class resource type
  (`book-aether/part1-no-magic.md`, `custom-qualifiers.md`), so they taught a pattern that did not
  function once deployed.
- `SliceLoadingContext.config()` now serves the **materialized slice-composite** (`slice.toml` under
  the node-composite's operator KV overlay and node.toml) through a new `ConfigProviderFacade`.
  Fixed at that seam rather than by threading a `ConfigFacade` through
  `DependencyResolver.resolveWithContext`, for two independent reasons: the slice-composite is
  **late-bound** (it cannot exist until the slice classloader does, which is why `SliceStore` already
  passes a *builder*), so a facade constructed at resolve time could only carry the node composite
  and would silently miss the slice's own `resources.toml` and every operator override on it; and the
  existing `NodeDeploymentManager.ConfigServiceConfigFacade` — the obvious thing to thread in —
  **refuses `requireStringList` outright**, so a `List<String>` config component would have stayed
  broken behind a fix that looked complete.
- **Absence now refuses by name instead of degrading.** When no composite is attached, `config()`
  does not fall through to the no-op — whose "Config service not available" reads as a missing *key*
  and sends the operator to their `resources.toml` — but returns `AbsentCompositeConfigFacade`, whose
  cause names the missing *source*, the key, and the slice that asked. A facade supplied explicitly
  through the config-carrying overload still wins, distinguished by identity against
  `NoOpConfigFacade.INSTANCE`.
- **Verified** by the repo's first `ConfigurationSection` slice
  (`DeployedConfigSectionTest`, `aether/slice-testkit`), driven through `SliceStore.loadSlice` against
  a real slice jar on disk — manifest, child-first `SliceClassLoader`, `DependencyResolver`, the
  deferred composite builder and the processor-generated `EndpointProbeFactory`. The context is not
  hand-built, deliberately: a test constructing its own `SliceCreationContext` through the
  config-carrying overload bypasses exactly the broken code and passes against the defect.
  Mutation probe — reverting only the `config()` hunk against a committed base — turns two of the
  three deployment tests red with four `Config service not available` causes, one per `require*` the
  generated factory emits (the fifth component is optional and routed through
  `Result.success(getInt(...))`, which cannot fail). Recompilation was confirmed by mtime, since the
  tree's `-T1C` build swallows the compiler's "Compiling N source files" line, and the restore by
  reading the method body back rather than by `git status`.
- **Scope of the closure, stated precisely.** `createSliceFromClass` is also reached at
  `DependencyResolver:583` and `:613` via the two-argument overload, bypassing this seam. Those sit in
  a closed subgraph whose only entries are `DependencyResolver.resolve` and `resolveBridge`, and a
  repo-wide sweep finds **zero** callers of either (positive control: the same sweep shape finds
  `resolveWithContext` at `SliceStore.java:208`, so the null is an absence rather than a bad search).
  So no in-repo caller can hand a deployed slice a no-op facade. Both remain `static` members of a
  public interface in a published artifact, so an out-of-repo caller could still reach them — that
  path also passes `noOpResourceProvider()` and so refuses *every* resource, not config specifically.
- **Known limit, not closed here:** the `get*` half of `ConfigFacade` returns `Option` and has no
  channel to refuse, and the generator wraps those reads in `Result.success(...)`. A config record
  whose components are **all** optional is therefore still constructed, with every value absent,
  against a missing composite. Any record carrying at least one required component — every example in
  the books — fails loudly and by name. Closing the all-optional case means changing what the
  generator emits.
- **Adjacent gap found, filed as #897 rather than fixed here:** `SliceTestKit` uses the two-argument
  overload and exposes no config registration at all (`withResource`, `withPublisher`, `withHttp`,
  `withNotifications`, `withContainer`, but no `withConfig`), so a user can now *deploy* a
  `ConfigurationSection` slice and still cannot *unit-test* one. Growing the public API of a published
  artifact is a scope decision, not something to acquire inside a bug fix.
