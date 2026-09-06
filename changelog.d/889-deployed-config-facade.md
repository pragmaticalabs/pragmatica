### Fixed (2026-09-06 — #889: a slice declaring a configuration section could not be created in a deployed cluster)
- **`ctx.config()` inside a deployed slice was always `NoOpConfigFacade`**, whose every `require*`
  method fails, so the `Result.all(...)` chain generated for
  `@ResourceQualifier(type = ConfigurationSection.class)` could never produce a record
  [mechanism: the deployment path is `SliceStore.loadSlice` → `DependencyResolver.resolveWithContext`
  → `SliceLoadingContext.sliceLoadingContext(invoker, resources, sliceId, nodeCodec)`, which routes
  through the three-argument `SliceCreationContext` factory and passes `NoOpConfigFacade.INSTANCE`;
  the config-carrying overloads had no production caller; reverting `SliceLoadingContext.config()`
  to `delegate.config()` reproduces it — every `DeployedConfigSectionTest` case fails with one
  `Config service not available` cause per `require*` the generated factory emits]. No deployable
  consumer of `ConfigurationSection` existed in the repo, the examples or the ticketing demo before
  this fix's fixture [mechanism: repo-wide grep for `type = ConfigurationSection.class` finds the
  slice-processor's own generator unit test and nothing else; the first deployable consumer is
  `aether/slice-testkit`'s `EndpointSettings`], which reads as "it could not work" rather than
  "nobody wanted it".
- `SliceLoadingContext.config()` now serves the **materialized slice-composite** (`slice.toml` under
  the node-composite's operator KV overlay and node.toml) through a new `ConfigProviderFacade`,
  reading through the reference on each call so a composite attached after construction is seen
  [verified: `DeployedConfigFacadeTest$LoadingContextSeam#configPicksUpACompositeMaterializedAfterTheContextWasBuilt`,
  `#configServesTheCompositeOnceAttached`]. Fixed at that seam rather than by threading a
  `ConfigFacade` through `DependencyResolver.resolveWithContext` [mechanism: the composite is
  late-bound — it cannot exist until the slice classloader does, which is why `SliceStore` passes a
  builder — so a facade built at resolve time could carry only the node composite; and the existing
  `NodeDeploymentManager.ConfigServiceConfigFacade` refuses `requireStringList` outright, so a
  `List<String>` component would have stayed broken behind a fix that looked complete].
- **Absence refuses by name instead of degrading.** With no composite attached, `config()` returns
  `AbsentCompositeConfigFacade` rather than falling through to the no-op: the cause says a
  configuration SOURCE is missing (not a key), lists the four load-time conditions that leave it
  missing — no node provider, node.toml secret resolution failed, the slice's `resources.toml`
  failed to parse, or one of its `${secrets:...}` placeholders failed — and names the slice that
  asked [verified: `DeployedConfigFacadeTest$LoadingContextSeam#configRefusesByNameWhenNoCompositeIsAttached`
  for the wording, `SliceStoreTest#configRefusal_namesTheSliceLayer_whenResourcesTomlIsMalformed`
  for the parse-failure branch, where the node HAS a provider and the first draft's wording was
  false; `SliceStoreTest#buildSliceCompositeFromClassLoader_dropsWholeComposite_whenResourcesTomlIsMalformed`
  for the fact that a dropped slice layer takes the node keys with it]. A facade supplied through
  the config-carrying overload still wins [verified: `#anExplicitlySuppliedFacadeWinsOverTheRefusal`].
- **Each `[slices]` dependency now loads through a loading context of its own.** One context was
  threaded down the dependency chain, so a dependency's generated factory read `ctx.config()` from
  the PARENT's composite (first-wins materialization, builder closed over the parent artifact) and
  its refusals named the parent; the parent's later codec `bind` also overwrote the dependency's.
  `DependencyResolver.resolveWithContext` now takes an artifact-keyed composite builder and builds
  one context per slice reached [verified: `DeployedConfigSectionTest#dependencySlice_readsItsOwnConfig_notItsParents`
  — two jars declaring the same key with different values, each observes its own;
  `#dependencySlice_refusalNamesTheDependency_whenItsOwnKeyIsMissing` — a key the dependency lacks
  fails the load naming the dependency instead of being read from the parent]. Every missing-key
  refusal from `ConfigProviderFacade` names the slice as well as the key for the same reason
  [verified: `DeployedConfigFacadeTest$ReadSemantics#missingRequiredKeyFailsAndNamesTheKeyAndTheSlice`].
- **`requireStringList` accepts the idiomatic spelling.** A native TOML array reaches every
  provider as Java's `List.toString()` — `[a, b]` — because `TomlDocument#getSection` stringifies
  each value; the first draft split that on commas and returned `["[a", "b]"]` silently. Both the
  native array and the comma-joined scalar are read, whitespace and empty slots are treated alike
  in both, a present-but-empty list is a value (`[]`), and a nested array is refused by name
  [verified: `DeployedConfigFacadeTest$ReadSemantics#stringListAcceptsANativeTomlArray`,
  `#stringListSplitsOnCommasTrimmingAndDroppingEmpties`, `#stringListTreatsBothSpellingsAlike`,
  `#stringListPresentButEmptySucceedsAsEmptyList`, `#stringListRefusesANestedArrayByName`].
- **Verified through the real deployment path** by the repo's first `ConfigurationSection` slice,
  driven through `SliceStore.loadSlice` against a jar on disk that carries its own
  `META-INF/resources.toml`: manifest, child-first `SliceClassLoader`, `DependencyResolver`, the
  deferred composite builder, the secret resolver and the processor-generated
  `EndpointProbeFactory`. Each field of the parsed record is attributed to the layer that supplied
  it — a `${secrets:...}` placeholder resolved from the slice's own file, an operator override
  winning over the slice's value, a key that exists only in the slice's file, and a native TOML
  array [verified: `DeployedConfigSectionTest#deployedSlice_receivesParsedConfigRecord_withRealValues`,
  `#deployedSlice_observesEachLayerOfTheComposite`, `#deployedSlice_isDefinedBySliceClassLoader_notTheTestClasspath`].
- **Scope of the closure, stated precisely.** `createSliceFromClass` is also reached through the
  two-argument overload in `DependencyResolver.resolveSliceDependencies` and
  `createAndRegisterSlice`, bypassing this seam. Those sit in a closed subgraph whose only entries
  are `DependencyResolver.resolve` and `resolveBridge`, and both remain `static` members of a
  public interface in a published artifact — an out-of-repo caller could still reach them, and
  that path also passes `noOpResourceProvider()` and so refuses every resource, not config
  specifically [mechanism: repo-wide grep finds zero callers of either; positive control — the
  same sweep finds `resolveWithContext` called once, at `SliceStore.loadFromLocation`].
- **Known limit, not closed here:** the `get*` half of `ConfigFacade` returns `Option` and has no
  channel to refuse, and the generator wraps those reads in `Result.success(...)`, so a config
  record whose components are **all** optional is still constructed, with every value absent,
  against a missing composite [mechanism: `FactoryClassGenerator`'s component-read switch emits
  `Result.success(ctx.config().get*(...))` for the `OPTIONAL_PRIMITIVE` and
  `OPTIONAL_VALUE_OBJECT` arms]. Any record with at least
  one required component fails loudly and by name. Closing this means changing what the generator
  emits.
- **Adjacent gap, filed as #897 rather than fixed here:** `SliceTestKit` exposes no config
  registration at all, so a user can now *deploy* a `ConfigurationSection` slice and still cannot
  *unit-test* one [mechanism: `SliceTestKit.build()` constructs its context through the
  two-argument `SliceCreationContext` overload, and the builder offers `withResource`,
  `withPublisher`, `withHttp`, `withNotifications`, `withContainer` and no `withConfig`].
