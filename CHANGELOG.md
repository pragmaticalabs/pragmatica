# Changelog

All notable changes to Pragmatica will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.0.0-rc4] - Unreleased

### Fixed (2026-09-04 — #759 review: a redeployed or deleted blueprint id kept reporting a stale terminal outcome from a prior attempt)
- **A retry of a previously FAILED/ROLLED_BACK blueprint id read as still-failed until its own
  terminal write landed.** `DeploymentOutcomeKey` is written only at the four terminal FSM
  transitions and was never cleared when a new deployment of the same id started —
  `BlueprintId` wraps the artifact, so a retry reuses the key, and the (now outcome-first)
  blueprint-status route reported the prior attempt's `FAILED`/`ROLLED_BACK` outcome while the
  live redeploy was actively converging, a false-negative on deployment health with no code path
  that would ever clear it on its own.
- **`BlueprintService.buildAllCommands` bundles a `KVCommand.Remove` of the publishing id's
  `DeploymentOutcomeKey` into the SAME consensus batch as the `AppBlueprintKey` Put that starts the
  new attempt** — covers `publishFromArtifact`. The two land atomically, so `outcome(id)` is: *at
  any instant, either the blueprint is in flight with no outcome, or terminal with exactly one
  record for the current attempt — never in flight while reporting a previous attempt's result.*
- **Round 2: the same gap existed on two more live paths that write or remove `AppBlueprintKey`
  without going through `buildAllCommands`.** DSL `publish(String)` (`SliceRoutes.handleBlueprint`)
  went through `storeBlueprintWithKey`, and `delete(id)` through `removeFromStore` — both applied a
  single-command batch touching only `AppBlueprintKey`, so a DSL republish left a prior
  FAILED/ROLLED_BACK outcome in place until the new attempt's own terminal write, and a `delete`'s
  orphaned outcome record never cleared at all (nothing ever writes that id's `AppBlueprintKey`
  again to trigger it). Both methods now bundle the same `DeploymentOutcomeKey` Remove into their
  own batch, so the guarantee above now holds for all three ways `AppBlueprintKey` changes on a
  live path: `publishFromArtifact`, DSL `publish`, and `delete`. [mechanism: same-batch
  `cluster.apply` in `buildAllCommands`, `storeBlueprintWithKey`, `removeFromStore` — all commands
  passed to one `apply(...)` call commit together or not at all]
- **One documented exception, out of scope for this fix: `ClusterDeploymentState.restorePreviousBlueprint`.**
  An ALL_OR_NOTHING rollback with a previous blueprint re-Puts `previous.id()`'s `AppBlueprintKey`
  (making it live again) in the same batch as a ROLLED_BACK outcome Put keyed by `inflight.id()` —
  the FAILING id, not `previous.id()`. `previous.id()`'s own `DeploymentOutcomeKey` is untouched,
  and `restoringBlueprints` suppresses `previous.id()`'s normal in-flight FSM tracking while the
  restore is pending, so no later terminal write ever supersedes whatever `outcome(previous.id())`
  already held from an earlier attempt of that id. A restored blueprint can therefore be live and
  serving while `outcome(previous.id())` still reports a stale, unrelated terminal record; recovery
  is the same shape as the pre-existing crash-orphaned/stuck cases documented on this method —
  consult `get(previous.id())`'s presence, not `outcome()` alone. [mechanism:
  `ClusterDeploymentState.restorePreviousBlueprint` / `rolledBackOutcomeCommand` keys the outcome
  Put on `inflight.id()`, never `previous.id()`; `restoringBlueprints` gates the two in-flight-
  tracking checks that would otherwise produce a new terminal write for `previous.id()`]
- No operator action is needed for the three covered paths — a publish or a delete of `id` clears
  its own stale outcome automatically. (Corrects the round-1 wording above, which claimed this held
  for every publish before `delete` was covered and before the `restorePreviousBlueprint` exception
  was documented.)
- **No ordering dependency, unlike the `AppBlueprintKey` Put/Remove pair #809 hardens** —
  `DeploymentOutcomeKey` has no FSM event-dispatch handler wired to it (no
  `DeploymentOutcomePutReceived`/`RemoveReceived` case exists), so this Remove's position in the
  batch relative to the blueprint Put/Remove is not load-bearing; nothing subscribes to either
  notification. `DeploymentOutcomeValue` is not fenced (`EpochBearing`/`LeaderValue`), so the
  witnessless `Remove(key)` form is admitted unconditionally by the KV applier.
- A new test pins the atomicity property directly on the recorded consensus batch, not just its
  eventual effect: the `AppBlueprintKey` write/remove and the `DeploymentOutcomeKey` Remove must
  land in ONE `cluster.apply` call — red if the fix were (hypothetically) split into two separate
  calls, a shape every existing effect-based assertion would still pass.
- Javadoc on `BlueprintService.outcome(BlueprintId id)` states the in-flight-XOR-terminal guarantee
  scoped to the three covered paths, and documents the `restorePreviousBlueprint` exception above;
  it does not resolve the pre-existing crash-orphaned/stuck ambiguity documented there (#760/#724
  review round 2 item i) — it only ensures the in-flight case is never confused with a stale
  terminal record from an earlier attempt of the same id.
- Pinning tests in `BlueprintPublishOwnershipTest.OutcomeClearedAtPublish` drive the real
  `publishFromArtifact`, DSL `publish`, and `delete` paths against a seeded FAILED outcome for the
  same blueprint id, asserting `outcome(id)` is empty after each; a no-prior-outcome control case;
  and a same-batch assertion pinning atomicity for each path. Mutation-probed: red with each
  `Remove` line dropped, green restored.
  [verified: aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/BlueprintPublishOwnershipTest.java]

### Fixed (2026-09-04 — #715: Forge/Ember clusters admitted nodes from foreign local processes)
- **Every `EmberCluster` instance (and therefore every `ForgeServer`, built directly on it) derived
  its cluster QUIC/SWIM identity from one hardcoded literal secret, shared by every process on the
  machine.** `EmberCluster.buildForgeQuicTls` built its `TlsConfig`/CA from that literal, and
  `createNode` passed `Option.empty()` for `AetherNodeConfig.certificateProvider`, so SWIM gossip
  encryption fell back to `GossipEncryptor.none()` (plaintext, unauthenticated) for every Ember/Forge
  node. Consequence: any process on the machine — no key needed, since gossip encryption was off
  entirely — could SWIM-gossip into another process's cluster, and any process holding the literal
  could QUIC-join it, up to triggering an OVERPROVISION drain of a live cluster's core from outside it.
- **Each `EmberCluster` instance now derives a fresh, unique `SecureRandom` cluster secret at
  construction**, threaded into both the QUIC `TlsConfig`/CA (`buildForgeQuicTls`) and the
  `certificateProvider` wired into every constructed node's `AetherNodeConfig`. No new admission-check
  code was added — both mechanisms already existed and needed only a real, per-instance identity to
  become effective:
  [mechanism: QUIC — `ClientAuth.REQUIRE` + per-instance CA, unchanged verification code; a client
  whose certificate doesn't chain to this instance's CA is TLS-rejected at handshake]
  [mechanism: SWIM — AES-GCM AEAD decrypt-failure → log+drop, unchanged verification code; a gossip
  datagram not encrypted under this instance's derived key fails tag verification and is discarded
  before reaching membership state]
  Neither guarantee is "secure" in general terms — both are exactly as strong as
  `SelfSignedCertificateProvider`'s HKDF derivation, which this fix does not touch.
- **`EmberCluster.withClusterSecret(byte[])` is the only sanctioned way for two separately-created
  `EmberCluster`/`ForgeServer` instances to join one cluster** — pass the same secret bytes to both
  before calling `start()`. A repo-wide grep found no existing harness, Forge scenario, or multi-JVM
  test that relies on cross-instance/cross-process joining today; no caller relied on the literal,
  one stale comment in `ClusterTestTls` was corrected.
  [mechanism: `EmberCluster.createNode` is the sole Ember/Forge construction site, so this is the
  only choke point either mechanism needs]
- Startup-time regression check for the newly-activated `CertificateRenewalScheduler`
  (dormant while `certificateProvider` was `Option.empty()`): 3-node Ember cluster start, same
  test/machine, before vs. after — 17.88s avg (3 runs) vs. 17.55s avg (3 runs), no regression.
  [mechanism: manual wall-clock A/B, 3 runs per side, same machine and test, pre-fix commit vs.
  post-fix commit — not an automated/CI-enforced measurement, and no cited test measures time]

### Fixed (2026-09-03 — #737: CursorStore leaked one block per commit; the refcount meant to reclaim it had zero readers)
- **Every cursor commit leaked its superseded block.** `CursorStore.commit` replaced a cursor's ref with
  `put` + `createRef`, which points the ref at the new block but never decrements whatever it replaced.
  Cursor blocks are content-addressed (an 8-byte offset), so every consumer group parked at the same
  offset shared one block — deleting "the old block" outright on replace would have deleted it out from
  under every other cursor still pointing at it. `BlockLifecycle#isOrphaned` (refCount<=0) — the check
  `DefaultStorageGarbageCollector` (#250) reads — had zero writers reaching it for cursor refs, so the
  leak was permanent, not merely until the next GC pass.
- **New `StorageInstance#replaceRef(name, content)`**: writes (or deduplicates) the block for
  `content`, points `name` at it, and decrements whatever `name` previously pointed to — one
  operation, never leaving `name` absent. The metadata pointer swap itself is atomic; the new
  block's credit and the displaced block's decrement are only ordered, not atomic together — a
  crash between them over-counts the displaced block (never decremented, stays live).
  `MetadataStore#replaceRef(name, blockId)` backs the swap with a pure ref-pointer move (no
  refcount side effect; callers own the counting).
  [mechanism: `DefaultStorageInstance#replaceRef` composes the existing write path (`handlePut`, which
  already credits the new block — +1 on a fresh write, +1 via dedup on an existing one) with a decrement
  of whatever the swap displaces, applied only AFTER the swap, so the new block's credit is visible
  before the old one's debit — pinned by `DefaultStorageInstanceReplaceRefOrderingTest`]
- `CursorStore.commit` now calls `replaceRef` instead of `put` + `createRef`. The default `replaceRef` on
  the `StorageInstance` interface still falls back to `put` + `createRef` for any implementor other than
  `DefaultStorageInstance`, which has two independent counting defects: it leaks the superseded block
  exactly as before (only `DefaultStorageInstance` reclaims it), and it double-counts the NEW block —
  `put` already credits it (fresh write or dedup, +1), then `createRef` credits it again (+1), so it
  sits at refCount 2 for one logical reference and never reaches zero even after an explicit
  `deleteRef`.
  [verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/segment/CursorStoreTest.java
  — `RefcountReclamation`: a repeated commit and a shared-content commit across two consumer groups both
  leave the superseded block at refCount 0 and the live block at its correct count;
  `GarbageCollectionIntegration#commit_thenCollectGarbage_reclaimsExactlySupersededBlocks`: three commits
  to one cursor, then the production `StorageGarbageCollector#collectGarbage` (not a direct metadata-store
  assertion) reclaims exactly the two superseded blocks, leaves the live one and `fetch` intact]
- **GC grace period now runs from when a block was orphaned, not from when it was last read**
  (fix round 2, same ticket). The original grace filter keyed on `lastAccessedAt`, which is set at
  creation and only refreshed on a successful read — a block read once, held referenced for a long
  time, and orphaned just now would already have a stale `lastAccessedAt` and skip the grace
  entirely. `BlockLifecycle` gained a seventh field, `orphanedAt`: stamped with the current instant
  only on the refCount transition from >0 to <=0, left unchanged by a redundant decrement at the
  floor, and cleared back to 0 on resurrection (0 → 1+). On-disk/wire compatibility: existing
  six-field snapshot and KV-Store records still parse — the 6-arg `BlockLifecycle` reconstruction
  factory derives `orphanedAt` from `lastAccessedAt` for an already-orphaned legacy entry (0 for a
  live one), so a pre-upgrade orphan becomes collectible once that borrowed timestamp clears the
  grace period, and a live entry is unaffected.
  [mechanism: `BlockLifecycle#withRefCountIncremented`/`withRefCountDecremented`
  (`integrations/storage/src/main/java/org/pragmatica/storage/BlockLifecycle.java:90-111`) stamp/clear
  `orphanedAt` only on the actual >0↔<=0 transition, never on a same-side no-op — pinned by
  `BlockLifecycleTest#withRefCountDecremented_redundantAtFloor_doesNotRestartGraceClock` and
  `#withRefCountIncremented_resurrection_clearsOrphanedAt`]
  [verified: integrations/storage/src/test/java/org/pragmatica/storage/StorageGarbageCollectorTest.java
  — `collectGarbage_orphanedNow_survivesEvenWithStaleLastAccessedAt`: a block backdated on
  `lastAccessedAt` by 10x the grace period but orphaned just now still survives collection]
- **Remaining exposure, unchanged by this fix**: a cursor block newly reaching refCount 0 is now
  GC-reachable, which surfaces it to two known gaps in GC-eligible blocks generally: #801 (a
  concurrent deduplicating `put` can resurrect a block between GC's orphan scan and its delete step,
  since the two run with no lock held across the gap) and #802 (a block demoted to the DHT alone
  drops out of every node's local GC candidate set once its private-tier lifecycle record is gone,
  with no cluster-wide process owning its reclamation). Neither is fixed here. Two callers still
  replace refs the old way and leak exactly as `CursorStore.commit` did before this fix —
  `DefaultContentStore.java:55` and `StorageSegmentSink.java:62` both call `put` + `createRef` instead
  of `replaceRef`; tracked separately by #812 (rc4), not fixed here.

### Fixed (2026-09-04 — #759 Phase 2: `GET /api/v1/blueprints/status/{id}` still answered 404 after a rollback, even though a durable outcome record now exists)
- **The status route consults `BlueprintService.outcome(id)` unconditionally, before `get(id)`.**
  A terminal `FAILED`/`ROLLED_BACK` outcome now wins over whatever `get(id)` currently holds —
  including a stale non-empty value the with-previous rollback path can leave behind (a KV-store
  defect tracked separately, out of scope here) — because the durable outcome record written at
  the same terminal transition (see the "#759 review, BLOCKING 3" and "#760 / #724 review round 2"
  entries below) is authoritative regardless of what the live KV entry happens to contain. Only
  when the outcome is `SUCCEEDED` or absent (never deployed, still in flight, or crash-orphaned —
  these three stay indistinguishable from `outcome()` alone) does the route fall back to the
  pre-existing `get(id)`-based logic.
  [mechanism: `SliceRoutes.routeBlueprintStatusByOutcome` filters on `DeploymentOutcomeStatus`
  before ever reading `get(id)`; pinned by `BlueprintStatusAggregationTest`
  (`statusRoute_outcomeFailed_returns200Failed`, `statusRoute_outcomeRolledBack_returns200RolledBack`,
  `statusRoute_outcomeSucceeded_returns404`,
  `statusRoute_blueprintPresentStalePreFailure_outcomeRolledBack_returns200RolledBack`) — unit-level,
  not a live multi-node failure-injection run]
- **This retracts the "Until #759 Phase 2" / `[design intent — unverified]` claim in the original
  #759 entry below**: post-rollback `statusUrl` GETs now answer `200` with `overallStatus`
  `FAILED`/`ROLLED_BACK`, `cause`, and `failingSlices` instead of a permanent `404`. `404
  BLUEPRINT_NOT_FOUND` now means only "no terminal outcome recorded and nothing live in the KV
  store either." `GET /api/events` remains the per-node failure timeline — not superseded, since
  `statusUrl` reports the durable summary, not the sequence of what happened on which node.
- **`BlueprintStatusResponse` gained `cause` (String), `failingSlices` (List<String>), and
  `timestampMs` (long)**, populated from the outcome record on the FAILED/ROLLED_BACK path and
  degenerate (`""`, `List.of()`, `0L`) on the unchanged `get(id)`-derived path — following the
  `CertificateStatusResponse` precedent (dormant dimensions show true degenerate values, never
  fabricated ones).
  [mechanism: `SliceRoutes.toBlueprintStatusResponse(BlueprintId, DeploymentOutcomeValue)` overload]

### Fixed (2026-09-03 — #760: a schema hold produced one `WARN` per re-evaluation tick, not per hold)
- **The hold WARN is event-driven, not tick-driven, and fired on every re-observation of an
  unchanged hold.** `tryActivateIfDependenciesReady` is reached from the slice's own LOAD, from
  ANY schema record reaching `COMPLETED`, from a sibling dependency activating, and once per
  blueprint at leader rebuild — a single long-running hold could log dozens of identical WARNs.
  A per-slice `reportedSchemaHolds` map now tracks the last-reported blocking signature: `WARN` on
  first observation or on a signature change, `DEBUG` on an unchanged repeat, and one `WARN` when
  the hold clears.
- **`GET /api/schema/status/{datasource}` (and the status-list route) gained `heldSlices`** —
  the slices a blocking record currently withholds from activation. Empty whenever the record is
  not blocking, so the operator no longer has to correlate DEBUG logs or wait for the
  `SCHEMA_ACTIVATION_BLOCKED` audit entry. (Its derivation was corrected in the review round below —
  see "`heldSlices` was a parallel derivation that never read per-node slice state.")

### Fixed (2026-09-03 — #724: a schema migration stuck `PENDING` had no recovery lever short of a redeploy)
- **`POST /api/schema/retry/{datasource}` now accepts `PENDING` as well as `FAILED`.** A migration
  that never dispatched (e.g. the orchestrator crashed before running it) was refused by the same
  409 guard as an already-`COMPLETED` record, leaving redeploy as the only lever. `MIGRATING` and
  `COMPLETED` remain refused — a runner is already in flight, or nothing marked the record failed.
- **The refusal message keeps its scripted-client substring.** `SchemaRouteError.SchemaNotFailed`
  still emits `"...is not in FAILED state (currently <STATUS>)..."`, pinned by two integration
  scripts and a unit test, now ending `"— retry applies to FAILED or PENDING migrations only"`.

### Fixed (2026-09-03 — #738: topic sections silently ignored dashed/misspelled keys)
- **A misspelled or dashed key in a topic's `resources.toml` section is now rejected at parse,
  naming the nearest correctly-spelled key** — most commonly `min-sync-replicas` where
  `min_sync_replicas` was meant. Previously the reflective config binder
  (`ProviderBasedConfigService.bindToClass`, the class every production config caller actually
  binds through — a separate, unreachable `TomlConfigService` carries the same shape but has zero
  production callers) resolved any key it did not recognize to `Option.none()`/a component
  default, making a typo byte-indistinguishable from the key never having been written. The real
  fail-open is on the **ephemeral** path, not a durability-tier mis-selection: `durability` alone
  picks the tier (`TopicConfig.topicConfig`), dash-typo-immune. `TopicConfig.declaredStreamKeys()`
  only sees a stream knob as declared through its typed `Option` field, so a dashed
  `min-sync-replicas` resolved to `none()` and stayed invisible to it — `rejectInertKeys()` then
  found nothing declared and never raised the loud #576 rejection an ephemeral topic carrying a
  (mistyped) durable-tier knob is supposed to get. The operator's likely-durable declaration was
  silently discarded with zero signal, on an otherwise-successful ephemeral bind.
- **New opt-in `@StrictKeys` annotation**, applied to `TopicConfig` only — every other config
  record bound by `ProviderBasedConfigService` (four production callers:
  `NodeDeploymentState.java:284`, `ConfigSectionPreflightValidator.java:60`, `AetherNode.java:5869`,
  `SpiResourceProvider.java:362`) is unannotated and binds exactly as before; the existing
  `SimpleConfig` test fixture — reused, not new — with an added unrecognized key proves this.
  [verified: integrations/config/config-service/src/test/java/org/pragmatica/config/ProviderBasedConfigServiceTest.java (StrictKeysScoping.config_nonAnnotatedRecord_ignoresUnrecognizedKey_exactlyAsBeforeTheHook)]
- **Scoped to exactly the keys the annotated record binds, and to the static/file-backed
  configuration layer only**: a nested sub-section under the same topic (e.g. a consumer group
  table, owned by the dashed-by-convention `StreamConfigParser`) is never inspected by this check,
  however it is spelled — and neither is an environment variable, system property, or KV-overlay
  entry landing at the same path, since none of those layers wrote the section this record
  declares (#738 review finding). `provider.keys()` (every layer merged) was the original,
  too-broad scope; the check now reads the new `ConfigurationProvider.staticKeys()`.
  [verified: aether/resource/api/src/test/java/org/pragmatica/aether/resource/TopicConfigTest.java (tomlBinding_rejectsDashedTopicLevelKey_evenWithNestedConsumerSubsectionPresent, tomlBinding_ignoresSystemPropertyKeyAtTopicSection_neverFailsStrictBind)]
- Nearest-key suggestion via a small self-contained Levenshtein-distance helper (no new
  dependency), bounded to `max(3, key length / 2)` so an unrelated key gets no suggestion at all
  rather than an unbounded argmin, and every unrecognized key in a section is reported together,
  not just the first. Operator docs (`aether/docs/slice-developers/resource-reference.md`) and the
  durable-pubsub spec (`aether/docs/specs/durable-pubsub-spec.md` §3) now state the guarantee and
  cite it.
  [verified: aether/resource/api/src/test/java/org/pragmatica/aether/resource/TopicConfigTest.java]

### Fixed (2026-09-03 — #769: `database.async_url` operator override was ignored by slice stores while the log claimed it was applied)
- **`DatabaseConnectorConfig.effectiveHost()`/`effectivePort()`/`effectiveDatabase()` gave the
  discrete `host`/`port`/`database` fields unconditional precedence over a configured URL**,
  inverting the documented contract (`resource-reference.md`: `jdbc_url`/`r2dbc_url`/`async_url`
  "replaces host/port/database"). An operator who set only `async_url` to redirect a datastore had
  the override silently discarded by every consumer of the effective accessors
  (`AsyncSqlConnectorFactory`, `PgSqlConnectorFactory`, `NotificationListenerFactory`,
  `AsyncJooqConnectorFactory`), while `effectiveAsyncUrl()` itself was already URL-first and correct.
- The three accessors now prefer the URL-derived value (via the existing, unchanged
  jdbc→r2dbc→async internal ordering) and fall back to the discrete field only when no URL yields
  one. `effectiveType()`, `effectiveUsername()`, and `effectivePassword()` are unchanged — no
  URL-derivation applies to type/credentials.

### Fixed (2026-09-03 — #250: storage GC/demotion was wired to a no-op)
- **Artifact and stream tier demotion and garbage collection now actually run.** `AetherNode`
  previously wired storage through `DelegatedStorageAdapter.noOp()` — leader-pinned
  activation/deactivation toggled correctly, but nothing ever called `.demote()` or
  `.collectGarbage()`, so tiered storage never shrank in production. A new
  `StorageMaintenanceDriver` ticks both operations on a fixed-rate timer, fanned out across every
  entry in `storageSetups` (`artifacts`, `streams`). **The `content` tier is not covered**: it is
  provisioned separately (`StorageFactory.defaultContentStorage`) as a bare `StorageInstance`
  outside `storageSetups`, so it gets no demotion and no garbage collection from this driver.
  Tracked as #783.
- **New `[timeouts.storage_maintenance]` config**: `interval` key, default `5m`. `AetherNode` logs
  `Storage maintenance enabled: demotion+GC cadence=<interval>, storage setups=<names>` at startup.
- **DHT tier is now marked cluster-shared** (`DhtStorageTier.isShared() == true`): a block orphaned
  by this node's local refcount may still be referenced by another node's local view, so node-local
  garbage collection must never delete it on that basis alone. Enforced in
  `StorageInstance.deleteFromPrivateTiers` (`integrations/storage`), which skips any tier reporting
  `isShared()` regardless of the tier list it is handed.
- Compile-forced by the new 15th `TimeoutsConfig` field: `EmberCluster.raisedSwimTimeoutsConfig()`,
  `ClusterTimeoutsAbsenceOrderingTest.configWith()`, and `StorageRoutesTest` (rebuilt for
  `StorageSetup` growing from 5 to 7 components).

### Fixed (2026-09-03 — #760 / #724 review round: `heldSlices` reported a serving slice as held, and retry could double-dispatch)
- **`heldSlices` was a parallel derivation that never read per-node slice state.** It matched
  ownership only, so `/migrate` re-arming a `COMPLETED` record while its slices were `ACTIVE` made
  `GET /api/schema/status/{datasource}` report an already-serving slice as held. `heldSlices` now
  shares the exact predicate the activation gate itself evaluates
  (`ClusterDeploymentState.blocksSliceActivation`, newly extracted and exposed as
  `BLOCKING_SCHEMA_STATUSES`) instead of re-deriving it from ownership alone.
- **`POST /api/schema/migrate/{datasource}` now refuses to re-arm a `COMPLETED` record with a live
  `ACTIVE` slice.** Re-arming has no orchestrator effect by itself (only a `PENDING` record's Put
  dispatches a run) but leaves `MIGRATING` — a blocking status with no automatic clearing path —
  ready to hold the next slice instance that reaches `LOADED`. Refused with `409 Conflict`
  (`SchemaAlreadyServing`, naming the datasource and active-slice count); a `COMPLETED` record with
  zero live `ACTIVE` slices is unaffected. See [`POST /api/schema/migrate`](aether/docs/reference/management-api.md#post-apischemamigratedatasource).
- **Retry dispatch is now single-flight per record across both the timer and the route, on a given
  leader.** A PENDING record with a live scheduled retry could double-dispatch `migrateIfNeeded`
  when the route and the timer's KV-lock release raced on the same leader; `acquireLock` now
  cancels any scheduled retry for the datasource before proceeding, so exactly one dispatch wins on
  that leader regardless of which path gets there first. Across leaders the consensus lock is the
  arbiter (`acquireLock`'s `isLockHeld` read followed by a conditional `Put`), and that read-then-Put
  window is not atomic — this fixes the same-leader double-dispatch, not a cross-leader race.
- **The dedup signature for a repeated schema hold is now a sorted join**, not an unsorted join over
  a `ConcurrentHashMap` — an unchanged multi-datasource hold could otherwise re-WARN if two
  evaluations happened to iterate the blocking set in different orders.

### Changed (2026-09-03 — #782: single machine is three containers; cluster size below 3 refused at startup)
- **A node whose CONFIGURED cluster size is below three now refuses to boot** — gated on the
  topology a node was told to run (the parsed `--peers=`/`CLUSTER_PEERS` list plus self, or the
  discovery/config arm's `cluster().nodes()`), never on however many peers a boot attempt happened
  to resolve. This matters at cloud discovery's majority-at-timeout arm specifically: a slow VM can
  make a healthy three-node boot resolve only two peers, and gating on that resolved count would
  have refused the exact boot the majority-timeout exists to allow. `ClusterSizeGate.enforce`
  (standalone gate in `aether-config`) still does the check; `Main` now feeds it
  `expectedClusterSize`. The failure message names the rule ("a cluster is at least three nodes")
  and points at the documented quick start.
  [mechanism: the gate reads the configured size before AetherNode construction, same abortBoot idiom as the cluster-name and WAL gates]
- **Docs**: removed the stale "Single Node" section (and its #782 caveat) from `docker-deployment.md`; the existing
  three-container compose is retitled "Single machine (three containers)" as the one documented quick-start path for
  a single machine, with the equivalent fix applied to `current-docker-setup.md`. The total-cluster-loss recovery
  runbook (`backup-recovery.md`) now notes that starting one node of a configured three-node cluster does not trip
  the gate, and that the node will not reach quorum until a second one joins.

### Fixed (2026-09-03 — #759 review, BLOCKING 3: a rolled-back blueprint had no outcome to read after `unloadBlueprintSlices` removed its key)
- **A durable per-blueprint deployment outcome record now survives ALL_OR_NOTHING rollback.**
  `unloadBlueprintSlices` unconditionally removed the failed blueprint's `AppBlueprintKey`, so
  `GET /api/blueprints/status/{id}` returned 404 after a rollback, with only the transient
  `DeploymentFailed` event on `/api/events` as evidence. The FSM now writes an
  `AetherKey.DeploymentOutcomeKey` / `AetherValue.DeploymentOutcomeValue` pair (`SUCCEEDED` /
  `FAILED` / `ROLLED_BACK` status, failing slice ids, cause, timestamp) at the terminal
  transition, bundled into the SAME consensus batch as the `AppBlueprintKey` removal — atomic
  with, not vulnerable to, the cleanup that used to remove the only evidence. Only the
  `SUCCEEDED` and `FAILED` write sites are wired by this fix; `ROLLED_BACK` was a defined status
  with no write site yet, deliberately left out of scope here — wired two review rounds later by
  `restorePreviousBlueprint`'s `rolledBackOutcomeCommand`
  (`ClusterDeploymentState.java:2412`), see the round 2 entry below ("The `ALL_OR_NOTHING`
  restore-previous-blueprint branch now also writes a `ROLLED_BACK` outcome").
- **Bounded by KV Put-overwrite-by-key**: the record is keyed by blueprint id, so a redeploy's
  next terminal transition simply overwrites the same key — one outcome kept per blueprint id,
  no accumulation, no separate pruning mechanism.
- **`BlueprintService.outcome(BlueprintId id) : Option<AetherValue.DeploymentOutcomeValue>`**
  exposes the read accessor for the node's status route (stream A wires the route to it after
  this branch merges — `SliceRoutes.java` / `ManagementApiResponses.java` are out of scope here).
- **`KVStoreSerializer.java` gained a TOML round-trip for the new `deployment-outcome` section**
  (`STATUS|slice1,slice2,...|cause|timestampMs`, fully lossless, not added to `LOSSY_SECTIONS`) so
  the new KV type participates in snapshot export/import like every other section.
- Pinning test `ClusterDeploymentStateTransactionalTest.DeploymentOutcomeRecord` drives a FAILED
  `NodeArtifact` Put through the real FSM dispatch path into `unloadBlueprintSlices`, asserting the
  outcome `Put` and the `AppBlueprintKey` `Remove` land in the same consensus batch. Mutation-probed:
  red with the write reverted, green restored.

### Fixed (2026-09-03 — #760 / #724 review round 2: BEST_EFFORT failures, rollback restore, wire escaping, and a `/migrate` refusal gap left durable evidence incomplete)
- **A `BEST_EFFORT` deployment failure now writes a durable `FAILED` outcome record.** `BEST_EFFORT`
  artifacts never populate `inFlightBlueprints` (that tracking is `ALL_OR_NOTHING`-only), so neither
  `rollbackBlueprintForArtifact` nor the succeeded-outcome write path ever ran for them — a partial
  `BEST_EFFORT` failure previously left no record at all for `BlueprintService.outcome()` to return.
  Merges into any existing `FAILED` record for the same blueprint (read-then-Put) so a second,
  independently failing slice in one partial deployment is added to `failingSlices` instead of
  erasing the first. A slice with no owning blueprint (a standalone deploy) is correctly a no-op.
- **The `ALL_OR_NOTHING` restore-previous-blueprint branch now also writes a `ROLLED_BACK` outcome**
  for the blueprint being replaced, bundled into the SAME consensus batch as the restored previous
  blueprint's own `AppBlueprintKey` Put — a reader of `outcome()` for the failing blueprint's id
  never observes the restore having landed without also observing the terminal record. Recorded
  against the blueprint being replaced, never the (separate, still-healthy) blueprint being restored.
- **The `deployment-outcome` KV wire form now escapes `cause` and each slice id** (backslash-escaping
  `\`, `|`, and `,`) instead of joining them unescaped — an embedded `|` in a cause message previously
  corrupted the field boundary, and an embedded `,` in a slice id previously split it into two. The
  reverse parse is escape-aware on both the outer `|` split and the inner `,` split
  (`KVStoreSerializer.splitOutcomeField`).
- **A malformed `deployment-outcome` TOML record (wrong field count, unrecognized status, non-numeric
  timestamp) now fails the section's `Result` instead of throwing** `IllegalArgumentException` /
  `NumberFormatException` out of `valueOf`/`parseLong` uncaught — composes through `Result#allOf`
  into a failure of the whole snapshot load, consistent with every other section in the file.
- **`POST /api/schema/migrate/{datasource}` now refuses `409 Conflict` against an already-`PENDING`
  record**, distinct from the existing `COMPLETED`-with-active-slices refusal: re-arming a `PENDING`
  record to `MIGRATING` has no dispatch effect of its own (only a fresh `PENDING` Put dispatches a
  run) and would strand the record with no automatic clearing path. Refused via the new
  `SchemaAlreadyPending` error, naming the datasource; retry the existing pending migration or use
  `aether schema retry` once it has failed. `POST /api/schema/retry/{datasource}` is a separate route
  (`retryMigration` → `writeRetryStatus`) and is unaffected — it still accepts `PENDING` (#724's
  original widening). See [`POST /api/schema/migrate`](aether/docs/reference/management-api.md#post-apischemamigratedatasource).
- **`recordSucceededOutcome`'s write now logs `WARN` on failure** instead of silently dropping it —
  a lost `SUCCEEDED` outcome write previously left `BlueprintService.outcome()` reporting stale or
  absent state for a blueprint that actually finished deploying, with no operator-visible signal.
- **`reportedSchemaHolds`'s WARN-dedup keying is now documented**: the dedup signature is per
  datasource-plus-blocking-set, not per datasource alone, so two different holds on the same
  datasource (e.g. `MIGRATING` then `FAILED`) each WARN once rather than the second being suppressed
  by the first's dedup entry.
- **`aether schema status` gained a `HELD SLICES` column** (between `STATUS` and `VERSION`) rendering
  `heldSlices` — previously reachable only via `--format json` or `--field heldSlices`, and the
  feature catalog and CLI/API docs described it as surfaced through the `OWNING BLUEPRINT` column,
  which names the blocked blueprint, not the slices it is blocking. Fixed in `cli.md`,
  `feature-catalog.md`, and `management-api.md`, and `management-api.md` gained the `/migrate`
  `409`-on-`PENDING` scenario above. [mechanism: `SchemaRoutes.guardReactivation` switches on the
  observed status before any orchestrator effect and returns `SchemaAlreadyPending` for `PENDING`
  without writing `MIGRATING`]
- **`BlueprintService.outcome()`'s javadoc now documents the four cases `Option.empty()` conflates**
  (never deployed / in flight and progressing / orphaned by a crashed FSM host / stuck waiting on an
  event that never arrives) — a caller cannot currently tell "will complete soon" apart from
  "permanently stuck, needs intervention" from this method alone; doing so needs additional state
  (e.g. how long the blueprint has sat in a non-terminal `get(id)`). Documentation only, no behavior
  change — a real fix is out of scope for this round.

### Fixed (2026-09-03 — #760 / #724 review round 3: fence release is per-attempt, lock write is timeout-bounded, BEST_EFFORT success now writes a durable outcome, and a stale doc path prefix)
- **The migration fence is a per-attempt token, released only by the attempt that owns it.**
  `SchemaOrchestratorService.acquireLock`'s `inFlightMigrations` release is a `remove(key, token)`
  compare-and-remove, not a bare `remove(key)` — a release that runs late can never clear a later
  attempt's fence entry, only its own. The consensus lock `Put` is now bounded by
  `schemaManager.policy().migrationTimeout()` (the same bound as the migration itself); on timeout
  or failure the fence is released synchronously via `mapError`, so an operator-triggered retry
  immediately after a failed lock write observes the fence already cleared rather than racing an
  async cleanup callback. Recovery from a wedged consensus round or partitioned leader does not
  require a leader change — the fence still releases within the migration's own timeout.
  [verified: aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/schema/SchemaOrchestratorRetrySingleFlightTest.java]
- **A `BEST_EFFORT` deployment whose slices all reach `ACTIVE` now writes a `SUCCEEDED` durable
  outcome record.** `trackInFlightBlueprint` tracks both `ALL_OR_NOTHING` and `BEST_EFFORT`
  blueprints — previously only `ALL_OR_NOTHING` deployments populated `inFlightBlueprints`, so a
  `BEST_EFFORT` slice reaching `ACTIVE` never reached `recordSucceededOutcome` and a
  fully-successful `BEST_EFFORT` deployment left `BlueprintService.outcome()` with nothing to
  return. The success path is now the same `trackBlueprintSliceActive` → `recordSucceededOutcome`
  call both atomicities share.
  [verified: aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/fsm/ClusterDeploymentStateTransactionalTest.java, `BestEffortSuccessOutcome`]
- **`management-api.md` corrected five `/api/schema/...` paths (and one anchor link) to the actual
  `/api/v1/schema/...` prefix** — every schema management-API route is composed through
  `ManagementRoute.API_BASE` (`/api/v1`) with no carve-out, so the un-prefixed form previously
  documented would 404 if followed literally.

### Fixed (2026-09-03 — #759: `POST /api/v1/blueprints/deploy` returned 200 `"deployed"` while slices had actually failed to load)
- **Deploy response now reports an earned status, not an assumed one, and always carries a
  `statusUrl`.** `status` is read off the live deployment map at response time — `pending`
  (default), `degraded` (a target instance already `FAILED`), or `deployed` (every target instance
  `ACTIVE`) — replacing the fixed literal `"deployed"` written before allocation ever ran.
  `statusUrl` points every response, whatever its status, at `GET /api/blueprints/status/{id}` so a
  `pending`/`degraded` caller can poll it. **Until #759 Phase 2**, that poll can dead-end: under the
  default `ALL_OR_NOTHING` mode a deterministic failure rolls back the whole blueprint and removes
  its KV entry outright, so `statusUrl` then answers `404 BLUEPRINT_NOT_FOUND` rather than `FAILED`
  — `GET /api/events`, matched client-side on `details.artifact`, is the only durable record of the
  failure.
  [design intent — unverified: the rollback-to-404 sequence is exercised only by unit-level tests
  (`ClusterDeploymentStateTransactionalTest.RollbackSequence`,
  `BlueprintStatusAggregationTest#statusRoute_blueprintAbsentFromKv_returns404BlueprintNotFound`),
  not a live multi-node failure-injection run]
- **`slices` replaced by `targetInstances`/`activeInstances`/`failedInstances`** on `/deploy`,
  `/publish`, and the blueprint status endpoint's per-slice entries, for consistent instance counts
  across all three.
- **The status endpoint now reports `FAILED` honestly**: a slice with `SliceState.FAILED` instances
  still present in the deployment map is reported `FAILED` rather than folded into
  `PENDING`/`DEPLOYING`, and `overallStatus` is `FAILED` if any slice is, ahead of every other
  bucket.
  [mechanism: status is derived directly from `deploymentMap()` by construction — pinned in-process
  by `BlueprintDeployStatusTest`, `BlueprintStatusAggregationTest`]

## [1.0.0-rc3] - 2026-09-02

### Changed (2026-09-02 — publish-time packaging, one commit after the `v1.0.0-rc3` tag)
- **`aether-setup` no longer publishes its 30 MB executable as the module artifact.** The shaded jar is
  now attached under the `uber` classifier (`aether-setup-<version>-uber.jar`), and the thin jar is the
  main artifact, matching the `peglib-playground` pattern. rc2 shipped the fat jar as the main artifact
  with a POM that still listed every dependency, so a consumer pulled both. Applied to the Maven Central
  publish of rc3 from `main` after the tag was cut; the tagged tree carries the old configuration.

### Fixed (2026-08-31 — the ticketing example could never complete a purchase: seven defects, each found by running the product and reading what it said about itself)
- **A scalar `@Query` ignored its own `RETURNING` clause.** `FactoryGenerator.inferScalarColumnName`
  derived the result column from the SELECT list and never consulted `RETURNING`, so
  `INSERT ... SELECT :a, :b ... RETURNING version` generated `row.getLong("$1")` — the bind
  placeholder after rewriting, a column no relation has. Every pricing write failed as
  `503 "Pricing store is unavailable"`, a fabricated diagnosis: the store was healthy and the
  query was malformed. Any scalar `INSERT…SELECT…RETURNING` store method was unusable.
- **The typed row accessor discarded the column's wire format.** `PgRow.get(int, Class)` called the
  3-arg `toObject`, which hardcodes `binary=false`, while the untyped path passed `col.isBinary()`.
  A `uuid` returned in binary was decoded as text, so `UPDATE ... RETURNING id` **committed and then
  reported failure** — the caller saw "store unavailable" for a write that had applied. `DataConverter`
  now decodes uuid from either format. The breakage was general to binary columns; uuid merely
  failed loudly enough to be noticed.
- **The router dispatched requests it could not serve.** `selectBestRoute` returned a lone candidate
  with no arity check, handing an under-supplied path to a parameterised handler that died at
  `pathParam()` — surfacing as `404 "Unknown request path"` from the http-routing layer instead of
  the ordinary no-match. The registry advertises arity-erased prefixes, so `/api/v1/quotes/` looked
  callable and was not (#764, dispatch half; publishing arity in the route registry remains open).
- **Local cross-slice calls encoded with the callee's codec.** Slices load in isolated loaders, so a
  caller holds its OWN copy of a callee's `X.Request`; `invokeViaBridge` encoded that object with the
  TARGET bridge, whose codec knows only its own copy, failing with "No codec registered for class".
  Encoding through the target had been introduced to serve request types from a parent loader
  (`Unit`); sender-bridge resolution still falls back to the target for exactly those. **This was the
  blocker — no purchase had ever completed.**
- **A still-activating dependency was classified fatal.** `verifyEndpointExists` returned an untyped
  `Cause`, which `SliceLoadingFailure.classify` funnels to `Fatal.UnexpectedError`, so a blueprint's
  slices — which activate concurrently — abandoned each other with "will NOT retry" purely on
  activation order, collapsing otherwise healthy deployments to zero routes. Now a typed
  `Intermittent.ResourceUnavailable`.
- **`findBridgeByClassLoader` threw on a bootstrap-loaded type.** `getClassLoader()` returns null for
  `Unit`/`String`, and `ConcurrentHashMap.get(null)` NPEs; the null is now lifted into an `Option`
  where it arises. Latent before — the remote path could reach it — and reachable on every local
  invoke once local calls routed through sender-bridge selection.
- **Cross-slice invocation failures were anonymous.** `SliceInvoker.invoke` now logs the failing
  slice, method and cause; the defect above spent the whole investigation as an empty error.
  [verified: ticketing walkthrough create → seat → price → open → quote → BUY green on a 3-node
  Forge cluster over repeated runs, with `/authorize` recorded in a pre-cleared gateway journal and
  bookings/tickets/payments persisted; full reactor suite 12,680 tests (counted from surefire XML)
  0 failures / 0 errors; `jbct:check` clean on all four touched modules]


### Fixed (2026-08-29 — #701: the entity fold's watermark never asserts coverage it does not have; caughtUp cannot NPE or hang on a lost CAS)
- **Blocking chosen, deliberately** (the ticket's own fork; matches the fold's existing
  refuse-over-lie posture): a failed `applyToState` on the write path now HOLDS the applied
  watermark instead of advancing past the record. The hold composes into existing machinery
  rather than adding new: later successes PARK behind the hole (contiguous drain), the checkpoint
  candidate cannot pass it (so the retention floor HOLDS and the log copy stays replayable — the
  outage is bounded by retained log, never by luck), and the next read gate replays the record
  through the path that propagates the failure loudly. The poison outage mode, named: that
  partition refuses reads and freezes its checkpoint from the poison offset until a build that can
  apply the record replays it. The prior malformed-timer-payload pin asserted the advance-past
  behavior the ticket was FILED against — rewritten to pin hold-not-advance, with the disposition
  reasoning in its doc (the malformed case IS the park path's case; the old "escape" was the
  false coverage itself). `logUnapplicable`'s message — which described the old
  divergence-then-permanent-loss behavior — rewritten to describe the hold and its recovery.
- **Two `caughtUp` liveness defects closed**: the lost-CAS path re-enters instead of
  dereferencing a slot the winner may already have nulled (the filed NPE); and the `runCatchUp`
  invocation is lifted so a synchronous throw between the WON CAS and the completion attach
  resolves the slot as a failure instead of leaving a promise nothing resolves — every later
  caller previously hung on it forever (found when the new hammer test hung the suite: same
  window as the NPE, worse outcome).
  [verified: `EntityFoldTest$WatermarkHonesty` — write-path hold with later-success parking,
  loud replay refusal on a log-held unapplicable record, and a 4-thread × 200-call hammer with a
  concurrent appender (all calls resolve, `@Timeout`-bounded); rewritten
  `EntityFoldTimerTest` pin holds at −1; durable-entity 270/0, node entity suite 48/48]

### Added (2026-08-29 — #386 D4 substrate: Projection facade — fold, rebuild lifecycle, honest at-least-once until the guard lands)
- **`Projection.of(topic).into(store, key).apply(fold)`** (spec §10, guard-independent half per
  the CTO's option-(a) parallelization): keyed fold-and-write on each durably-delivered event over
  a backing-agnostic `ProjectionStore` seam. The apply is **loudly documented and TEST-PINNED as
  at-least-once** — the §8 idempotency guard keyed `(projectionName, generation, messageId)`
  completes the facade once the context-aware subscriber shape lands (stream D executes the
  codegen half on the delivered spec); the pinning test is rewritten, not deleted, by that change.
  Rebuild is one procedure with load-bearing order: generation bumped FIRST (review finding 3 —
  replayed events must land under fresh idempotency keys, not be dedup'd by the prior pass), then
  the data reset under the settled §13-item-6 contract (data cleared, generation slot PRESERVED —
  a reset that wiped the slot would resurrect the prior pass's claims), then the group-cursor
  reset seam LAST — refused loudly by default until the D3 operator surface wires it, because a
  rebuild that silently skipped the cursor step would clear the model, replay nothing, and
  converge to an empty projection that looks caught-up.
  [verified: `ProjectionTest` — keyed folds, the at-least-once re-application pin, rebuild
  ordering observed through a recording cursor seam (gen bumped AND reset completed before the
  seam runs), loud refusal without the seam, name defaulting]

### Fixed (2026-08-29 — #700: entity checkpoint claims are advance-only in the KV substrate)
- **A checkpoint write that would LOWER the committed `throughOffset` is refused by the Rabia
  applier itself** — the third arm of the applier's value-driven fence family (`EpochBearing`
  guards authority, `VersionFenced` guards a chain, new `MonotonicFenced` guards a running max).
  The retention floor reclaims entity-log segments below the committed claim, so two honest folds
  either side of a partition handover could previously overwrite a higher claim with a lower one
  AFTER the log between them was reclaimed — leaving those records on no reachable node, a
  recovery hole the I4 intra-node guard could narrow but not close (different JVMs share no
  memory, and a caller-side read-then-write reintroduces the race one layer up). Equal watermarks
  are ACCEPTED (a fresh snapshot at unchanged coverage replaces the block pointer harmlessly);
  strictly-lower rejected, deterministically, from committed storage + the incoming value alone.
  Rejections are silent like the sibling fences; the driver doc records the consequence for its
  local advance map. The retention-floor reliance is now stated at the write site
  (`StreamEntityLogSubstrate.publishCheckpointPointer`), and `EntityCheckpointDriver`'s
  "not attempted here" note — the stale surface that described the gap — is corrected.
  [verified: `KVStoreWatermarkFenceTest` — the ticket's exact race (two writers, lower offset
  arrives last, higher claim intact), equal-accepted, first-write-passes, non-fenced-untouched,
  no-notification-on-rejection; zero behavior change for existing value types is structural
  (sole implementor, pattern-matched arm)]

### Added (2026-08-29 — #386 durable-topic dispatch WIRED: declared-durable topics now deliver at-least-once with a durable group-attributed DLQ)
- **A topic declared `durability = "durable"` is now a working durable delivery system end to
  end on a node**: publish appends the KSUID-stamped envelope to the replicated `topic:<address>`
  stream and resolves at the min-sync floor; subscriber dispatch rides `StreamConsumerManager`'s
  EXISTING placement machinery per the option-(a) ruling — three bounded seams only (topic-stream
  envelope-unwrap in `deliver`, durable `ConsumerConfig` selection in `doSubscribe`, and a
  declaration-source union centralized in one `allDeclarations()` resolution point after the
  first build caught reconcile computing keys from the union while `declarationFor` re-resolved
  from the registry alone), with placement/assignment/failover logic untouched by construction
  and the pre-existing declarative-consumer suite green unmodified as the regression fence.
  Durable-topic subscriptions synthesize consumer declarations when their `topic:*` stream exists
  (stream existence IS the durability declaration made real; ephemeral subscriptions never
  synthesize); groups are version-stable, and a blue-green window collapses to ONE dispatch loop
  per (group × partition). Dead letters for topic streams route through `RoutingDeadLetterSink`
  to the durable `DlqStreamSink` (owner-forwarding publishers, source floor inherited) while
  every other stream keeps the in-memory default unchanged. The interim `DurableTopicDispatcher`
  and its invoker seam are DELETED — superseded by the manager, so exactly one envelope-unwrap
  implementation exists. Docs moved in this same change per the landing obligation:
  `guarantees.md` §5 is rewritten as the per-operation two-tier table with per-claim evidence
  tags (including the no-silent-downgrade property and the honest PENDING list: multi-node forge
  e2e, D3 operator triad, D4 idempotency wiring, D5 publisher split), summary-matrix row 22a
  added, feature-catalog row 24 re-statused to Partial/two-tier, and resource-reference's
  subscriber section documents the durability keys (fixing its stale `topic` field name to the
  actually-bound `topic_name`).
  [verified (single-node): `StreamConsumerManagerTest$TopicGroupDispatch` — version-stable
  attach with durable config, ephemeral-ignored, blue-green collapse, envelope-unwrap delivery
  with the application payload reaching the invoker; `DlqStreamSinkTest` — group-attributed
  re-enveloping preserving messageId, DLQ-stream read-back, family routing; full node module
  971/0/0. Multi-node composed path: design intent — unverified pending forge e2e]

### Added (2026-08-29 — #386 publisher tier switch: durable topics provision the stream-backed publisher)
- **`PublisherFactory` now selects the publisher by the topic's declared durability class** (D1/D5
  substrate half): a DURABLE declaration provisions the envelope-wrapping stream-backed publisher —
  topic + DLQ streams activated eagerly at provision in one idempotent step, each `publish`
  resolving at the `min-sync == replicas >= 2` replication floor — while EPHEMERAL declarations
  keep today's RPC fan-out byte-for-byte. Wire plumbing landed with it: `aether-invoke` gains a
  cycle-free dependency on `aether-stream`; `NodeCodecs` registers the
  `org.pragmatica.aether.stream.topic` aggregate (MAILBOX-announced first); the two envelopes take
  hand-assigned one-byte SystemTags (110/111 — `TopicEventEnvelope` heads every durable event's
  payload bytes, and the `aether.stream.*` hot-prefix contract binds `DlqEnvelope` alongside it).
  **Stated intermediate window:** subscriber-side durable dispatch is the NEXT landing — until it
  arrives, a topic explicitly declared `durability = "durable"` persists publishes but delivers
  nothing to subscribers (the RPC fan-out deliberately does not fire for durable topics — replaying
  through it would fake delivery the moment dispatch lands). Nothing deployed declares durability,
  so the window is unreachable without opting in; `guarantees.md` §5 continues to describe every
  DEPLOYED topic truthfully, and its rewrite rides the dispatch landing as one change.
  [verified: `PublisherFactoryTest$DurableTierProvisioning` — durable declarations provision
  `DurableTopicPublisher` with both streams activated, §3-invalid bypass declarations refuse
  loudly; `$TopicNameFallbackDelivery` 3/3 and the ephemeral suite unchanged;
  `SystemCodecPinningTest` pins the one-byte window]

### Changed (2026-08-29 — #366 re-scope + #591 instrument hardening, per the #367 pole-gate ruling)
- **#366 re-scoped onto the shipping mechanism** (CTO ruling 2026-08-29, recorded on the ticket):
  community size in the product that ships is the PER-SOURCE WORKER COUNT — communities are minted
  one-per-source (`ClusterDeploymentState`, `source + "-w-0"`), and the `max_group_size` knob gates
  the never-wired splitting mechanism (#673). Three honest fixes ride the re-scope: an explicit
  `[worker] max_group_size < 2` now REFUSES at parse instead of silently becoming 100 (the #673
  trap: a typo produced a plausible green run, unobservable precisely because the knob changes no
  behavior) [verified: `WorkerConfigLoaderMaxGroupSizeTest` — refusal, absent-defaults, and the
  low-but-valid-survives arming pin]; `configuration.md`'s `max_group_size` row and feature-catalog
  row 99 no longer describe splitting as live (row 99 drops from Complete to Partial). The
  wire-or-delete decision on the splitter chain stays with #673, owner-grade.
- **#591's instrument survived first contact with an RBAC-authed cluster**: `coordination_slope.py`
  now attaches `X-API-Key` when `AETHER_API_KEY` is set (the live remote cluster 401s bare
  requests; the 08-27 validation ran unauthed and could not see this), and the new
  `slope_sweep.sh` driver (BSD-sed-portable, resumable via `COUNTS`) boots raw worker containers
  through the proven suite-13 join path and produces one slope row per worker-count step. The #591
  measurement itself is posted on the ticket.

### Added (2026-08-29 — #386 D2/D3 substrate: durable-topic dispatch, group-attributed DLQ stream, version-stable group identity)
- **The durable-topic dispatch substrate is complete in `aether-stream` (not yet node-wired —
  delivery semantics of deployed topics are still unchanged).** One strictly serial dispatch loop
  per (group × partition) over the stream consumer runtime decodes the `TopicEventEnvelope` and
  hands payload bytes to a `DurableSubscriberInvoker` seam (the node wires it over `SliceInvoker`;
  the handler promise is the ack, per-attempt timeout stays the slice-invoker call timeout — §6's
  single source of truth). Retries exhausted → the event is re-enveloped as a GROUP-ATTRIBUTED
  `DlqEnvelope` (original `messageId` preserved — the §8 idempotency key survives where offsets
  cannot) and appended to `topic:<address>.dlq` through the same min-sync barrier as the source
  (`DlqStreamSink`); the source cursor is held until that append resolves, so DLQ-append stalls
  block the partition visibly instead of dropping events. Consumer-group identity is
  version-stable (`groupId:artifactId#method`, §6): a slice upgrade keeps its cursor and DLQ
  attribution. `DeadLetterHandler.append` and `DeadLetterEntry` now carry the failing group —
  redrive is group-targeted (§9), and an entry without its group could only be redriven by
  re-publishing, duplicating to groups that already processed the event. Also un-inerted:
  `ConsumerConfig.checkpointInterval` now actually gates the time half of cursor checkpoints
  (previously read by nothing; safe — #576's validator rejects non-default declarative values).
  Two cadence deltas from the spec's normative defaults are stated in
  `DurableTopicDispatcher`'s doc rather than hidden (backoff base/cap, checkpoint event-count).
  [verified: `DurableTopicDispatcherTest` — end-to-end over the real generated wire format:
  in-order payload delivery with cursor advance on ack, idempotent attach/detach, and the §6→§9
  poison path (5 attempts → group-attributed `DlqEnvelope` in the DLQ stream with the original
  messageId → partition unblocked, next event dispatched); `DurableGroupIdentityTest` pins
  upgrade-stability; single-node scope — the replication barrier and multi-node placement are
  exercised by their own machinery (#410) and the pending node wiring respectively]

### Fixed (2026-08-28 — #674: consensus load metrics reach the wire)
- **An external observer can now measure coordination load on a core node.** Three disconnects,
  all fixed: the three vote-traffic recorders (`RabiaMetricsCollector.recordVoteRound1/2`,
  `recordFastPath`) were EMPTY BODIES — the engine called them all along and every call vanished,
  so round-1/round-2 vote volume, the quantity that grows with coordination load, was counted
  nowhere; the comprehensive HTTP DTO had no consensus field, so the collected block was dropped at
  the boundary; and Prometheus carried none of it. Now: `GET /api/v1/metrics/comprehensive` carries
  a `consensus` block of LIVE monotonic totals (deliberately not minute-aggregated — a differencing
  consumer needs raw totals over its own window, the `/metrics/transport` contract; present from a
  node's first request, before any minute bucket exists), and the same counters serve as
  `consensus_*` Prometheus gauges with a shared key vocabulary (`RabiaMetrics.counterMap()` — the
  gauge names ARE the map keys, pinned so a drifted key cannot silently freeze a gauge at 0). The
  CLI's `aether metrics comprehensive` passes the block through unchanged. Endpoint SCOPE is now
  documented per the #674 semantics note: `/metrics` is cluster-wide despite its LOCAL routing
  declaration (fetch once, select by id); `/metrics/comprehensive` and `/metrics/transport` are
  node-local — and the transport section's stale "per-peer byte counters" description was corrected
  to what it serves (node-level message counts, no bytes).
  [verified: `aether/forge/forge-tests/.../ClusterFormationTest.cluster_comprehensiveMetrics_carryLiveConsensusBlock`
  — live 3-node formed cluster answers with a positive decision count; unit pins in
  `RabiaMetricsCollectorTest` (counting, snapshot-vs-reset, gauge vocabulary) and
  `MetricsRoutesTest$ComprehensiveConsensusBlock` (live block on the empty-aggregate branch);
  mutations red: recorder re-emptied → 4 pins red; counterMap key drifted → vocabulary pin red]
- **Deliberately NOT exposed: the `NetworkMetrics` byte counters.** Verification found the ticket's
  scope-growth premise inverted — `NetworkMetricsHandler` is constructed and threaded into the
  snapshot collector but never installed into any channel pipeline, so its counters are
  permanently zero; putting them on the wire would ship a silent-zero instrument. The honest
  byte-counter home is the QUIC transport's own metrics; recorded on #674's close.


### Added (2026-08-28 — #386 D1: topic durability declaration, parse-enforced)
- **A topic's `resources.toml` section now declares its durability class** (durable-pubsub-spec §3,
  D1 of the 2026-07-18 ratified set): `durability = "ephemeral"` (default) keeps today's RPC
  fan-out; `durability = "durable"` declares the stream-backed tier and accepts `partitions`
  (default 1), `replicas` (default 2), `min_sync_replicas` (default = replicas), `retention`
  (default 7d). The v1 durable constraint is **rejected at parse**, not weakened silently:
  `replicas >= 2` and `min_sync_replicas == replicas` — exactly the configuration whose lossless
  owner-kill failover is proven (streaming-spec §10.5); the rejection message cites the spec
  section and #411, whose landing is the constraint's relaxation path. Stream knobs declared on an
  ephemeral topic are rejected as inert (#576 config-honesty stance) rather than ignored. Both TOML
  binders invoke the validating `TopicConfig.topicConfig` factory, so every bound declaration
  passes through it; the `SpiResourceProvider` missing-section fallback (#396) now recovers ONLY
  `SectionNotFound` — a section that exists but fails validation fails slice activation loudly
  instead of silently downgrading a declared-durable topic to fire-and-forget. Legacy single-field
  declarations (`topic_name = "..."`) keep binding unchanged, and every existing construction site
  keeps compiling via the retained single-arg constructor. Delivery semantics are UNCHANGED by this
  commit — the declaration surface lands ahead of the durable substrate (D2), and `guarantees.md`
  §5 remains the truthful description of every topic today.
  [verified: `aether/resource/api/.../TopicConfigTest.java` — TOML round-trips through
  `TomlConfigService` prove the binder invokes the factory: durable-outside-constraint rejected,
  inert-ephemeral-keys rejected, missing `topic_name` stays loud, legacy shape binds ephemeral]

### Changed (2026-08-28 — #386 D2 substrate: dead-letter sink append is failure-aware; cursor never advances past an unaccepted entry)
- **`DeadLetterHandler.record` (void, fire-and-forget) is replaced by `append` returning
  `Promise<Unit>`, and the stream consumer runtime holds the group cursor until the sink accepts
  the entry** (durable-pubsub-spec §9/§12; also the seam half of the rc3 audit note on #386 —
  retry-exhausted events were skipped past a sink whose write had no failure channel). On retry
  exhaustion (RETRY) and on SKIP, the cursor now advances only after a successful append; a failed
  append retries with backoff indefinitely while an in-flight guard holds that partition's delivery
  loop (without it the un-advanced cursor would re-deliver the exhausted event to the handler).
  Capping the retries and advancing anyway was rejected: that IS the silent loss the contract
  exists to prevent — the stall is deliberate, partition-scoped, and bounded by the operator loop
  (`DLQ_STALL` alarm surface arrives with the D3 management batch). The in-memory default sink is
  unchanged in behavior (its append cannot fail) and now carries the loud volatility statement the
  audit note demanded at the class level; the durable DLQ-stream sink lands with D3.
  [verified: `StreamConsumerRuntimeTest.DeadLetterAppendContract` — failing-sink stub: cursor and
  loop held across two failed appends with no handler re-delivery, resume on sink recovery,
  exactly one dead-letter entry (retry loop mints no duplicates); SKIP variant likewise]

### Fixed (2026-08-28 — #712: slice jars ship the message classes their manifests declare)
- **A slice jar now contains the topic message and stream event classes its own manifest
  declares, plus the full sibling `shared` package tree.** Two packaging gaps compounded:
  `SliceManifest.allImplClasses()` — the single source `PackageSlicesMojo` packages from — merged
  only impl/request/response classes, never the manifest's own `publish.message.classes` and
  `stream.event.classes` (publisher message types are constructor-injected `MessagePublisher<T>` /
  `StreamPublisher<T>` type arguments, so they appear in no method-derived list); and the sibling
  `shared` package walk was non-recursive (`Files.list`) while the slice-subpackage walk was
  recursive, so records in `shared.event` and their component types in other `shared.*`
  subpackages never reached the jar. Evidence: ticketing-sweep-holds' jar omitted the declared
  `shared.event.SeatReleased` and slice activation died with `NoClassDefFoundError`;
  notification-hub's `NotificationEvent` was present in the module jar but absent from the slice
  jars. Closure depth: declared classes by name plus their nested/member classes plus the entire
  sibling `shared` tree — no bytecode reference walk, matching the existing convention-based
  closure. Manifest keys are unchanged (consumption-side fix only), so the envelope format is
  untouched. Same-module subscribers were already covered via `request.classes` (reactive methods
  are interface methods); cross-module message-type delivery is the dependency-edge sibling #717.
  [verified: `jbct/jbct-core/src/test/java/org/pragmatica/jbct/slice/SliceManifestTest.java` and
  `jbct/jbct-maven-plugin/src/test/java/org/pragmatica/jbct/maven/PackageSlicesMessageClassesTest.java`
  (red against unfixed code, green after; jar-level assertion on a built archive); final e2e
  against the #704 rf=1 reproduction runs in the coordinating session before #712 closes]
### Changed (2026-08-28 — #692: HealthReconciler ghost-comment sweep, Java surfaces)
- **Zero `HealthReconciler` references remain in Java sources.** 21 comment/docstring references
  across `integrations/consensus`, `integrations/swim`, and `integrations/cluster` described a type
  deleted in the membership-v2 migration — the largest single stale-describing-surface cluster on
  the branch, and exactly the shape that minted five wrong ticket premises on 2026-08-27. Each was
  corrected against the verified current mechanism, not search-replaced: SWIM `FaultyObserved` edges
  drive `MembershipFsm.onSwimFaulty` via the observation listener (no `DECOMMISSIONED` node-state KV
  write exists in v2); `PeerHealthObservation`'s epoch-fencing consumer is `SwimHintsRegistry`;
  connectivity observations ride `PeerObservationBuffer` → cluster-sync pong (the leader-side
  `ReachabilityAggregator` fold named by one comment is ALSO gone — P3 removed it, SWIM is the
  single liveness signal); leader-term consumers are Aether's generation/ownership epoch suppliers;
  `NodeLifecycleKey` is itself deleted. 1012 tests green across the three modules after the sweep.
- **#692's rename item was a stale premise, corrected rather than executed:**
  `ClusterSyncContext.emitPingTimeoutIfExceeded` does NOT "emit nothing" on current HEAD — the
  S01/Option-1 wiring gives it a live effect (`collector.reportUnreachable`, the SWIM
  transport-unreachable hint path), pinned by `ClusterSyncFsmTest`'s
  `emitPingTimeout_*` tests. The name matches the behavior; no rename shipped. Remaining #692
  surfaces live under `aether/docs/**` (docs-stream territory) and are routed, not swept here.

### Fixed (2026-08-28 — #694: Ember instances round-trip the CTM's tag selector)
- **In-JVM worker reconcile can now see its own inventory.** `EmberComputeProvider.toInstanceInfo`
  returned an EMPTY tag map for every instance; an instance with no tags matches no non-empty
  selector, so the CTM's worker reconcile — which counts ACTUAL inventory through the
  `aether-cluster`/`aether-source`/`aether-role` filter — read `actual = 0` forever in-JVM,
  re-provisioning every pass and never able to see a scale-down victim, with a symptom that pointed
  the next investigation at the CTM instead of the harness (the #590 family, one layer up). Tags are
  now built from the `ProvisionContext` AT PROVISION TIME and stored per node (the ticket's ordering
  constraint: `toInstanceInfo` is also reached from `listInstances`/`instanceStatus`, which hold no
  request), mirroring `HetznerComputeProvider.labelsFor` — the three selector keys with the blank
  role defaulting to `core`, plus the provider-agnostic dotted `aether.node-id`. Nodes created
  OUTSIDE the provider (initial cluster, direct `addNode`) stay untagged — the pre-#694 shape,
  preserved deliberately and pinned. One recorded divergence: an absent cluster name stamps `""`
  where cloud providers refuse the create outright (RFC-0017 C2) — the CTM selector renders an
  unresolvable name as the same `""`, so the round-trip holds either way.
  [verified: `aether/forge/forge-tests/.../EmberInstanceTagRoundTripTest.java` — same-context
  selector finds the instance with the exact four-entry map; selectors differing in any ONE field do
  not; blank role stamps the production `core` default; untagged initial nodes match no non-empty
  selector while staying listed. Mutation (stamp propagation removed): exactly the two positive
  guards red. The three CTM-provisioning probes (`MembershipChaosCycleTest`,
  `ProvisioningRecoveryAfterFailureBurstProbeTest`, `PostRestartSlowRejoinDeficitFillProbeTest`)
  re-run green against the stamped provider.]

### Fixed (2026-08-28 — #644: periodic tasks arm in start(), not at assembly)
- **A created-but-never-started node now performs no periodic work and holds no timers.**
  `AetherNode.assembleNode` used to hand all fourteen of the node's recurring tasks to
  `SharedScheduler.scheduleAtFixedRate` at ASSEMBLY time (#642's evidence run: two held-back Ember
  nodes ran 274 snapshot ticks each over 45 minutes without ever starting) — a family that includes
  destructive WAL truncation, metadata snapshot writes, operator-visible retention alerts, and
  (pre-#702) consensus KV removals. The new `PeriodicTasks` holder accumulates arming THUNKS during
  assembly and arms them only once cluster formation resolves in `start()`; `stop()` and the
  failed-boot guard (`cancelArmedWork`) discard unarmed thunks and cancel armed handles, and a
  `stop()` racing a late formation resolution wins — CANCELLED is terminal, so a torn-down node can
  never gain work. Arming after formation is safe for every member of the family: none participates
  in `clusterNode.start()`'s resolution (verified against the promise chain — the election trigger
  itself runs after resolution), and the activation level-heal's one dropped-edge scenario requires
  `clusterNode::isActive`, which is true by arming time. One deliberate seed choice: the
  phase-change watcher's baseline is still captured at ASSEMBLY, so the formation transition is
  reported as one edge on the first armed tick instead of being swallowed into a start-time
  baseline — the deferral removes only the pre-start publishing. The two UNKNOWNs from the ticket's
  partition are settled: `publishPhaseChange` cannot publish pre-start at all now, and
  `StreamConsumerManager.reconcile` was wasteful-not-unsafe (its declaration registry is empty
  pre-start). The guard-failure path additionally stops the two constructor-armed cleanup ticks it
  could never reach (`SliceInvoker`'s stale-invocation sweep, `AdaptiveSampler`'s rate
  recalculation); `RabiaEngine`'s constructor-armed phase cleanup remains reachable only through
  `clusterNode.stop()` and is recorded on #644 as the residual, with the constructor-armed family's
  own deferral left as an explicitly-scoped follow-up.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/PeriodicTasksTest.java` (the
  deferral state machine, 8 pins) and
  `aether/forge/forge-tests/.../NodeLifecyclePeriodicArmingForgeTest.java` (the WIRING, on a real
  Ember cluster with a held-back node: zero armed while unstarted across 4s of the tightest
  interval, start arms exactly the 14 deferred, stop disarms; the arm-call-deleted mutation goes
  red on exactly the wiring pins)]
- **#557's boot-quorum projection got a named, tested seam** (the composition rider recorded on
  #644): `AetherNode.presenceMemberSupplier` replaces the inline lambda no test could reach, and
  `PresenceMemberSupplierSeamTest` pins it against a real boot-seeded `MembershipFsm` — swapping
  the observed projection for the counted one (the exact #557 regression) now goes red at the real
  wiring instead of only in aether-deployment's mirror test.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/PresenceMemberSupplierSeamTest.java`,
  mutation (observed→counted) demonstrated red]

### Fixed (2026-08-28 — #702: entity registration removals gated on live consensus-activity)
- **A node that is not a live cluster participant can no longer mass-remove its own committed entity
  keyspace registrations.** `EntityOwnershipReconciler`'s removal half read an empty declared-keyspace
  set as evidence of absence and turned every committed self-registration into a KV `Remove` issued
  into consensus — on a constructed-but-never-started node (the #644 family) that state is the boot
  window, not a retraction, and the removal path was inert only by coincidence of construction order.
  The removal half is now gated on the same live consensus-active sample (`clusterNode::isActive`)
  the activation heal reads; the gate DEFERS the prune rather than cancelling it, so the
  restart-without-the-slice heal fires on the first genuinely-active tick. The put half is
  deliberately NOT behind the gate (a registration keeps re-asserting until it sticks), and the
  minting half keeps its existing leader gate — a leader gate on removals was evaluated and rejected,
  because removals are per-node self-authority and a worker that never becomes leader could never
  shed a stale registration. Residual, accepted and documented: on a restarted ACTIVE node the window
  between activation and slice redeploy still prunes-then-reasserts (the level-triggered heal).
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/EntityOwnershipReconcilerTest.java`
  — closed-gate suppression, defer-not-cancel across an activation flip, and the put-half exemption
  are each pinned, with both mutations (gate removed / gate widened over puts) demonstrated red]

### Changed (2026-08-28 — #496 scoped audit, surfaces 2 & 3 closed: KV/durability, deployment/blueprint)
- GA claims-vs-reality audit (#496), remaining two of three green-lit surfaces. No doc content
  changed this pass — both surfaces audited clean; recorded here so the "zero findings" result is a
  verified conclusion, not silence.
- Surface 2 (KV/durability): a repo-wide sweep for flag-on-sight phrases found nothing left
  unaddressed beyond what the consensus/cluster-core pass already fixed (D1) and what stays
  correctly deferred (durable-entity D13, the #676 backup row). Substantially covered by that pass.
- Surface 3 (deployment/blueprint semantics): read all 8 candidate ops/architecture/spec docs. The
  `ALL_OR_NOTHING`/blue-green atomicity claims in `architecture/02-deployment.md`,
  `slice-developers/deployment.md`, and `guides/deploy-guide.md` already earn their wording —
  mechanism named (single consensus-batch write across all slices' keys, ~100ms/one-Rabia-round for
  the blue-green switch). `operators/deployment-recovery.md:73` is already exemplary on why
  "highly available with automatic restart" doesn't describe Aether's terminal-removal/reprovision
  recovery model. `specs/unified-deploy-spec.md`'s atomicity claim is likewise well-grounded; its
  `/api/deploy/*` route-namespace content was left untouched as entangled with the deferred
  `/api/v1` hard-cutover territory, not for a guarantee-wording reason.
- `aether/docs/reference/guarantees-corrections-needed.md`: logged both surfaces' conclusions.
  #496's scoped pass (consensus/cluster-core, KV/durability, deployment/blueprint) is now complete;
  remaining open rows (D6-D13, D16-D19) all sit in explicitly deferred streams/pub-sub/durable-entity
  territory for a later pass.

### Changed (2026-08-28 — #496 scoped audit, consensus/cluster-core surface)
- GA claims-vs-reality audit (#496), scoped to consensus/cluster-core guarantee language per
  team-lead's green-light (management-API-route content, the #676 backup row, and stream/data-plane
  claims explicitly deferred to a later pass — logged in `guarantees-corrections-needed.md`).
- `aether/docs/reference/feature-catalog.md`: KV-Store row now states the write/read consistency
  split (linearizable write order, non-linearizable local reads) instead of the unqualified
  "Consensus-replicated store" phrasing (worklist D1); Quorum-state-management row now names the
  pause/reject-writes + minority-self-fence mechanism instead of the "graceful degradation ...
  automatic restoration" euphemism (worklist D4).
- `aether/docs/operators/monitoring.md`: the threshold-replication "No single point of failure"
  claim is now scoped to the write path, with the local-read-may-lag caveat added (worklist D14 —
  applied with a context-specific rewrite, not the boilerplate one in the worklist, which didn't fit
  this section since threshold writes aren't leader-pinned).
- `aether/docs/guides/rolling-upgrade.md`: "zero downtime" is now scoped to app-downtime, with a new
  core-node quorum-margin caveat during the rolling window (worklist D15 — the guide previously
  never discussed this risk at all).
- `aether/docs/architecture/01-consensus.md`, `aether/docs/contributors/consensus.md`: fixed an
  unearned "Strong (all nodes agree)" / "Strong consistency required" claim on the leader-election
  consensus mode (new finding, not in the original worklist) — the commit is linearizably ordered,
  but nodes apply it as their own round completes, not simultaneously; same-order, not same-instant.
- Confirmed `aether/docs/architecture/14-consistency-and-partitions.md` already meets the bar (no
  changes needed) and that worklist items D2/D3 (DHT durability disclosure) were already applied in
  the 2026-07-17 docs wave.
- `aether/docs/reference/guarantees-corrections-needed.md`: marked D1-D4/D14/D15 applied, logged D20
  (the new leader-election finding), and logged the deferred rows (D6-D13/D16-D19) so the next #496
  pass is enumerable instead of rediscovered.

### Changed (2026-08-28 — #705 filed: point the compat-window gap at it)
- `aether/docs/reference/versioning-and-compatibility.md`'s open compatibility-window gap
  (noted when #321 landed, below) now points at #705 — filed by the owner off that flag,
  milestoned v1.0.0 — instead of describing it as an unfiled candidate. No policy content added;
  the duration/backport decision is still owner-grade and unmade.

### Fixed (2026-08-28 — #577 docs half: `@Sql` documented usage did not compile)
- `aether/docs/reference/configuration.md`'s Config Merge Hierarchy example called
  `@Sql("orders_db")` — `@Sql` (`aether/resource/api/.../db/Sql.java`) has no value element at all,
  fixed to `@ResourceQualifier(type = SqlConnector.class, config = "database")`, so that call is a
  compile error and no named datasource is reachable through it. Fixed the example to
  `@ResourceQualifier(config = "database.<name>")`, the annotation the rest of the page already
  documents correctly for named datasources, and added an explicit "`@Sql` takes no argument" note
  to the Multi-Datasource Convention section so the constraint isn't only implicit in the examples.
  The `[datasources.*]`/`[endpoints.*]` merge-hierarchy mechanism itself is untouched — its wiring
  is a separate, unverified question (`#577`'s "reported, needing confirmation" bucket), not this
  fix's scope. `@Sql`'s data-plane-adjacent half of #577 (`StreamConsumerAdapter` and friends)
  remains for stream B; not started here.

### Changed (2026-08-28 — #321: SemVer commitment ruled for GA)
- `aether/docs/reference/versioning-and-compatibility.md` updated with the owner ruling: Aether
  commits to semantic versioning for the product release from GA (`v1.0.0`) onward — additive
  changes only in minors, breaking changes reserved for majors — independent of the management
  API's own version axis (`management-api-versioning-spec.md` §2.6). Pre-GA rc's remain outside
  the commitment. Flags the one thing the ruling did *not* settle: the compatibility window once a
  major ships (backport/LTS/EOL policy) is still undecided and unpublished — noted as a candidate
  for its own tracking ticket, not filed, parallel to how the version-skew gap became #666.

### Added (2026-08-27 — #351 / #345 I4: durable entity timers, end to end)
- **A timer scheduled on an entity is a record in that entity's own fenced, replicated log**, so it
  survives its owner being replaced and the whole cluster restarting. `scheduleTimer` / `cancelTimer`
  work from any node: a non-owner forwards to the committed owner over three new wire verbs
  (`EntityScheduleTimerForward`, `EntityScheduleTimerForwardResponse`, `EntityCancelTimerForward`;
  tags 1668/1670 and 1669), and the owner re-runs admission on arrival, so the epoch fence still
  decides and the per-key total order stays the owner's to enforce.
- **The token is CALLER-minted**, so a re-send after a lost acknowledgement is the SAME schedule:
  owner-side dedupe on the fold's pending set means no duplicate timer and the same success answer.
  Without it a lost ack left a durable timer scheduled with no token to cancel it by — for the
  canonical consumer (reservation expiry) a spurious expiry attributable to nothing. `TimerToken`
  never crosses the wire; the token travels as a plain `String` and is re-wrapped inside the module.
  The API exposes both forms: `scheduleTimer(key, delay, onFire, token)` for callers that want
  retry-safety, and a minting `scheduleTimer(key, delay, onFire)` default for callers that do not.
- **The fire instant is stamped by the committed OWNER at append, never by the scheduling node.** The
  API's one unconditional promise is at-or-after, never before; a sender whose clock ran ahead of the
  owner's would fire a timer EARLY and it would look correct to everyone involved. Paying one network
  hop of extra delay keeps the promise. Skew shifts a fire only across a handover between owners.
- `ENTITY_TIMER_INTERVAL` is 1s, a documented CONSTANT rather than a config knob. #351 promises no
  punctuality anywhere, so this value CREATES the contract: worst-case lateness is one tick plus
  however long the partition goes without a live owner. Sub-second punctuality is explicitly not this
  mechanism's job — a millisecond deadline wants in-memory scheduling, which buys that precision by
  giving up durability across an owner change.

#### Measured, from live 5-node clusters
| Gate | delay | disruption | fire |
|---|---|---|---|
| full restart | 30s | 42,061ms downtime | 1,052ms after ready |
| owner handover | 45s | 9,344ms kill→new committed owner | 36,720ms |
| same-token resend ×5 | 8s | — | ≤1 tick |

The restart gate is the stronger of the two because its **downtime outlasts the delay** — the timer
comes due while the cluster is down, so it demonstrates "late, never lost" rather than merely
"survived". The handover gate keeps ~35s of runway, so the timer fires on its ORIGINAL instant from a
node that never saw the schedule; its target is chosen through the production `EntityPartitionArc`
and then VERIFIED to have changed owner, so a mis-targeted kill fails loudly instead of passing
vacuously. Exactly-once is asserted on entity state (`OrderState.expiries`), not on logs.
[verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/DurableEntityTimerDurabilityTest.java`,
`.../DurableEntityForgeTest.java`]

**The exactly-once evidence required repairing its own instrument first, and that repair is part of the
claim.** The quiet period separating "exactly once" from "not yet twice" was a bare
`LockSupport.parkNanos`, and `Promise.await()` can leave a residual permit on the calling thread that
the next bare park consumes immediately. Measured: **5 residual-permit hits per 20,000 awaits**, and
with a permit present the bare park returned in **0ms** where a deadline loop takes **503ms**. Until
that was fixed the gates could silently re-read the value they had just polled. All three now
measurably elapse (5,000 → 5,004-5,005ms).

### Fixed (2026-08-27 — checkpoint correctness, reached from the I4 fold work)
- **A checkpoint could claim more coverage than its contents carried.** The driver read the applied
  offset and the fold contents separately, so a fold advancing between the two reads produced a HIGH
  claim over LOW contents. Both now come from one captured fold (`checkpointCandidate`).
- **`saveCheckpoint` was a blind Put with no monotonicity**, so a regressed-but-honest claim could
  overwrite a higher one whose log beneath had already been reclaimed by the retention floor — leaving
  those records on no reachable node. Now advance-only, guarded by the previously write-only
  `checkpointedThrough`. The cross-NODE half needs a conditional substrate write and is #700.
- `EntityCheckpointDriver.register` did check-then-add, so its documented idempotence held only for
  sequential re-provisioning. Now a single atomic `putIfAbsent`, matching `EntityTimerDriver`.
- Pre-existing fold defects found here and deliberately left for their own review: #701.
### Fixed (2026-08-27 — #547: no deploy-time validation for generic resource config sections)
- **The gap.** A slice's generic resource dependencies (database/cache/HTTP-client/idempotency —
  `SliceTopology.resources()`) had zero validation at deploy time. A missing `resources.toml`
  section only surfaced later, one node at a time, as an `SpiResourceProvider.loadConfig` failure
  during slice activation — after the deploy had already been accepted.
- **Fix.** `BlueprintService.publish`/`expandAndStoreArtifact` now run a new
  `ConfigSectionPreflightValidator` against every slice's `resources()` (not `publishes()`/
  `subscribes()` — see scope note below), aggregating every missing section into one failure via
  `Result.allOf` so the deploy fails once with a **complete list**, not stop-at-first
  [verified: `ConfigSectionPreflightValidatorTest`, `BlueprintPublishOwnershipTest.ConfigPreflight`
  — the latter exercises the real `BlueprintService.publishFromArtifact` path end-to-end with an
  on-disk slice jar and a real `ConfigurationProvider`].
- **Scope, deliberately narrow.** Only generic resources are gated. Pub-sub topics/streams already
  have their own validation stage (`StreamResourceValidator`) with different, non-gating semantics —
  folding them into this hard-fail check was considered and rejected as scope creep beyond this
  ticket's acceptance criteria (`BlueprintService.java:340-345`'s own comment already documents the
  stream-validation gate as a separate stage; `ManifestGenerator` structurally excludes stream
  resources from the sections this check can see).
- **Gap 1 (silent synthesis) resolved by documenting, not removing.** `TopicConfig` is the one
  resource type that still derives its config from the manifest when its `resources.toml` section is
  absent (`SpiResourceProvider.topicNameFallback`, #396) — there is nothing to synthesise, since the
  topic name was never a value the operator was supposed to supply. Its javadoc and
  `resource-reference.md` now say so explicitly; every other resource type has no such fallback.
- **Honest limits, stated in code and docs.** The check verifies presence and shape against the
  leader's composite configuration view (KV-overlay ⊕ the leader's own `aether.toml`), checked once
  at deploy time — not environmental correctness and not cross-node config homogeneity
  `[design intent — unverified]`. The failure message names this exact view so it is not mistaken
  for a cross-node guarantee. When no `ConfigurationProvider` is wired on the node at all, the check
  fails **open** rather than manufacture a false positive — and because a quiet gate that doesn't
  gate is itself a failure mode, the skip is now logged (WARN) whenever it would have had at least
  one resource section to check, naming the count of sections and slices that went unchecked
  [verified: `BlueprintPublishOwnershipTest.ConfigPreflight.publishFromArtifact_logsVisibleSkipWarning_whenNoConfigurationProviderIsWired`
  — captures the real logger via a log4j2 programmatic appender against the live `publishFromArtifact` path].
- No false positives: a slice whose sections are all present deploys unchanged
  [verified: `ConfigSectionPreflightValidatorTest.HappyPath`].
- **Scope pinned by tests, not just by comment.** A missing publish-topic config section can never
  fail this check — `publishes()` is a list the validator never inspects, unlike a missing
  generic-resource section which hard-fails
  [verified: `ConfigSectionPreflightValidatorTest.HappyPath.validate_missingPublishTopicSection_isInvisibleToTheCheck`].
  Proven on the real manifest-generation/parsing path too: a slice jar with both a missing generic
  resource section and a co-present missing publish-topic section fails naming only the resource
  section, never the topic
  [verified: `BlueprintPublishOwnershipTest.ConfigPreflight.publishFromArtifact_namesOnlyTheMissingResourceSection_whenAMissingPublishTopicSectionCoexists`].
- New: `MissingConfigSection` (Cause), `ConfigSectionPreflightValidator` in
  `aether/aether-deployment/.../deployment/validation/`. Plumbing: `BlueprintService` gained a 5-arg
  `blueprintService(...)` factory taking `Option<ConfigurationProvider>`; `AetherNode` wires it from
  `resourceProviderSetup.nodeComposite()`. The existing 3-/4-arg factories are unchanged in behavior
  (delegate with `Option.empty()`, i.e. fail-open) — no existing call site or test required updating.

### Fixed (2026-08-27 — #269: slice-level `resources.toml` `${secrets:...}` placeholders not resolved)
- **The gap.** Node-level `aether.toml`/KV secrets already resolved eagerly via
  `ConfigurationProvider.withSecretResolution` (`AetherNode.createResourceProviderFacade`), but a
  slice's own bundled `META-INF/resources.toml` was parsed as-is — a `${secrets:db/password}`
  placeholder shipped as a literal string into any resource declared only at that layer.
- **Fix.** `SliceStore` now threads the same node-configured resolver
  (`Option<Fn1<Promise<String>, String>>` — the narrow functional shape `withSecretResolution`
  itself already takes, chosen over threading a whole `SecretsProvider` so `aether/slice` doesn't
  gain a new dependency on `aether/environment-integration`) through to a new
  `resolveIntrinsicSecrets`, which wraps the slice-intrinsic provider the same way node.toml is
  wrapped.
- **All-or-nothing, deliberately.** One failed key drops the ENTIRE slice-intrinsic layer —
  mirroring the file's own pre-existing malformed-TOML-parse-failure convention — rather than
  silently keeping the other keys or failing the whole slice load. A resource declared only in the
  slice's `resources.toml` then fails as not-configured at provision time; the node log names the
  slice, the failed key, and this consequence (`intrinsicSecretsDroppedMessage`). A slice's
  resource config that's also set in `aether.toml`/KV is unaffected. *Considered and rejected:*
  failing the entire slice load on any secret failure — inconsistent with how a malformed
  `resources.toml` already degrades, and a bigger blast radius than the gap being closed.
- **R5 redaction, same pass.** `logShadowedKeys`'s INFO line was logging both the slice-intrinsic
  and operator-override *values* when an operator config shadowed a slice default. It now logs key
  names only (`shadowedKeys`); swept the rest of `SliceStore.java` for other value-logging sites —
  this was the only one.
- Tests pin the success path (placeholder resolved, non-secret keys pass through), the no-resolver
  path (literal placeholder preserved, pre-#269 behavior), the failure path (entire layer dropped),
  the consequence message (names the slice/key/secret-path/effect), and the redaction (returns key
  names only, never a value, for any input) — via directly-testable pure functions rather than log
  capture, since this codebase's log backend (log4j2) has no unit-test appender-capture utility.
  [mechanism: `SliceStoreTest`, unit-level — not yet exercised end-to-end with a live secrets
  backend across nodes]
- Docs: `aether/docs/slice-developers/resource-reference.md` updated — it previously stated slice
  secrets were unresolved and pointed at this ticket as future work.

### Fixed (2026-08-27 — #519 dead-config-accessor gate re-homed so it can pass a clean build)
- **The gate could not pass a from-scratch reactor run, and had held the branch red for hours.** It
  scans every module in `aether/pom.xml`'s default `<modules>` list by reading each one's
  `target/classes`, but lived in `aether/node`'s test suite — and modules such as `aether/ember` depend
  on `node`, so they are built strictly AFTER it. On a clean build their output does not yet exist when
  node's tests run, and the gate's corpus precondition correctly refused. It passed locally only where
  leftover `target/` output happened to be present.
- **Moved, not redesigned.** The whole `deadsurface` package now lives in a dedicated
  `aether/dead-surface-gate` module placed LAST in the default `<modules>` list, with **test-scoped
  dependencies on all 30 scanned modules** — so corpus completeness is enforced by Maven's own
  ordering, in any build order, rather than by luck. Six of the seven files are byte-identical to their
  previous versions; `ReactorRoots` differs only in comments and one diagnostic string.
- **The loud precondition is deliberately untouched.** The instrument was well built — it detects its
  own incomplete corpus and refuses rather than reporting false DEAD accessors, which is why this
  surfaced as a red build instead of as a silently under-reporting gate. Softening it would have
  destroyed the property that made the defect visible.
- Verified from a genuinely clean tree (zero `target/` directories beforehand), so the local run
  reproduces CI's from-scratch ordering: BUILD SUCCESS, `ConfigKeyLivenessTest` 2 run / 0 failures.
  Adversarial check: deleting `aether/ember`'s output turns the gate red with its original
  corpus-incomplete message, and restoring it turns it green — the pass is not vacuous.
- A gate that only passes on a dirty tree is the inverted form of the stale-artifact trap: local green
  *because* leftovers were present, CI red *because* it was clean.

### Fixed (2026-08-27 — #278: interceptor config silent-default corruption for retry/metrics provisioning)
- `CircuitBreakerConfig`'s private `DEFAULTS` renamed to public `DEFAULT`; `RetryConfig` gained a
  TOML-bindable `BackoffStrategy` (binder resolver) plus a public `DEFAULT`. Before this, an
  otherwise-present `[retry.*]`/`[circuit_breaker.*]` TOML section with an omitted field silently
  fell back to a private, binder-invisible default, and `RetryConfig`'s backoff strategy could not
  be configured from TOML at all.
- New reflective regression-gate test (`InterceptorConfigDefaultAllowlistTest`) pins which
  interceptor configs may expose a public static final `DEFAULT` (pure tunables:
  `CircuitBreakerConfig`, `RetryConfig`) and which must NOT (identity-bearing name fields:
  `CacheConfig`, `IdempotencyConfig`, `MetricsConfig` — a shared public default would let unrelated
  TOML call sites silently collapse onto the same cache/store/metrics namespace). Adversarially
  verified (mutation testing in both directions) to actually catch a regression, not pass vacuously.
- `MetricsInterceptorFactory` now resolves the node's real `MeterRegistry` from
  `ProvisioningContext` (registered by `AetherNode` from `ManagementServer.meterRegistry()`)
  instead of each `MetricsMethodInterceptor` fabricating its own disconnected registry — metrics
  now land in the SAME registry the Management API `/metrics` endpoint scrapes.
  **Tradeoff, not yet resolved:** the registry registration only runs when
  `config.managementPort() > 0` (`AetherNode.java`, inside the management-server startup branch).
  A node with the management port disabled will now fail to PROVISION any slice using a metrics
  interceptor, rather than silently recording into a black-hole registry as before.
  [design intent — unverified] whether fail-loud is the right outcome for management-disabled
  nodes, versus falling back to a no-op registry, is an open question for the issue owner.
- Fixed a live bug in the banking example: `examples/banking/account`'s `resources.toml` had zero
  `[cache.*]` sections (would fail hard at deployment with `ConfigError.sectionNotFound`), and its
  three cache-touching methods (`getBalance`/`credit`/`debit`) pointed at three different TOML
  addresses that needed an identical explicit `cache_name` to share one `CacheBackend` instance —
  without it, `credit`/`debit`'s invalidation silently targeted a disconnected cache, leaving a
  stale balance served forever. New `CacheInvalidationTest`
  [verified: examples/banking/account/src/test/java/org/pragmatica/aether/example/banking/account/CacheInvalidationTest.java]
  proves the fix end-to-end through the real TOML-binder -> `CacheInterceptorFactory` ->
  `CacheMethodInterceptor` path — not the plain-record construction `AccountServiceTest` uses,
  which bypasses interceptor wiring entirely — including an adversarial case proving the staleness
  failure mode actually manifests when `cache_name` diverges.

### Removed (2026-08-27 — #560: orphaned `aether-lb` Dockerfile deleted, cloud-testing-spec.md marked historical)
- `aether/docker/aether-lb/Dockerfile` deleted (with its now-empty parent directory). It built
  against `aether/lb/target/aether-lb.jar`, a path nothing in the repo produces — `aether/lb` isn't
  a module, no workflow builds an `aether-lb` image, `ghcr.io/pragmaticalabs/aether-lb` was never
  published. Unbuildable since it landed; zero other references anywhere in the repo.
- `aether/tests/cloud/docs/cloud-testing-spec.md`'s existing "Superseded in part" banner (rc3,
  2026-07-30) explained current routing but never flagged the ~150 lines of Phase 6/8 provisioning
  detail below it that still describe the retired VM+container design as live. Banner finalized
  (retirement is now a decided fact, not "tracked separately") and inline **Historical** markers
  added at the six heaviest clusters (§1.3 architecture diagram, Phase 6, Phase 8, §5's bastion-as-
  LB framing, §9's sequence diagram, Open Question Q4). Whether an LB returns as a mode of
  `aether-node` on `PassiveNode` (dead code today) stays gated on the owner's roadmap answer on
  ingress AB/canary routing — see #560, [MAILBOX.md](MAILBOX.md).

### Removed (2026-08-27 — #571: `HealthSignal` / `HealthSignalSink` deleted repo-wide)
- **The whole signal channel is gone: 2 types, ~64 main-code sites across 5 modules, 116 test
  references across 20 files, landed as one atomic commit.** Splitting it was never an option — an
  intermediate state where some callers are gone and the type is not (or the reverse) breaks a fresh
  build while incremental stays green, which is exactly what `f1aed3ff4` did to this branch.
- **The deletion is behaviour-preserving by construction, not by argument.** `healthSinkRef.set` had
  zero call sites and every `HealthSignalSink` in main code was either `HealthSignalSink.noop()` or a
  pass-through parameter — **no real implementation of the interface existed anywhere in production
  code**, so every emit already went into a black hole. `ClusterDeploymentContext.healthSignalSink()`
  and `ManageableNode.healthSignalSink()` both had zero callers.
- **Two live paths sitting inside the blast radius were kept intact**, and they are the reason this
  needed one owner rather than a mechanical sweep: `ClusterSyncPongSignalFan`'s leader readiness view
  (`recordReadiness` / `readinessSnapshot()`, feeding the CDM allocatable gate and the DRAINING set)
  is a SEPARATE interface from the sink and survives untouched; `SwimHealthContext.reportHint`'s
  `bufferHealthObservation` → `observationStore.pushHealth` half is LIVE and feeds
  `PeerHealthObservation` into the `ClusterSyncPong` body — only the `emitLeaderHint` half went.
- **Test migration lost no resolution on the health plane.** `emitLeaderHint` had exactly two call
  sites and BOTH already paired it with `bufferHealthObservation` — there was no emit-without-push
  anywhere — and `PeerHealthObservation` is strictly richer than `HealthSignal.SwimHint`
  (`HealthHint`/`HealthHintWire` are isomorphic under a total bijection, plus it carries
  `producedAtMs`). All 17 health-plane assertions moved onto the observation store with no new seam.
- **One genuine loss, recorded rather than absorbed:** `HealthSignal.PingTimeout.observedAt` has no
  live carrier — `reportUnreachable(NodeId)` takes neither the epoch stamp nor the missed count. The
  count is still pinned at FSM level by `counterForPeer`; the stamp is not pinned anywhere. It was
  only ever observable through a channel with no production consumer, so what disappeared is a
  test-only assertion, not operator-visible information. Putting it on `reportUnreachable`'s payload
  would be a behaviour change and belongs in its own review.
- **Four metrics-plane tests deleted outright** as pinning only the dead channel (`translateHint` /
  `translateConnectivity` coverage went with the code they translated into); one deleted as a
  duplicate of its readiness-view sibling. Seven migrated onto `reportUnreachable` and
  `readinessSnapshot()`. The leader-transition test needed a SECOND peer to keep pinning its real
  property (leadership re-read per `fan()`, not cached at construction) — the existing entry is not
  cleared on demotion, so re-using one peer would have made it vacuous.
- **One test in the migration set was already vacuous.**
  `CoreSwimHealthDetectorHintEmissionTest.defaultFactory_doesNotFail_whenNoSinkProvided` asserted an
  `emittedSignals` list was empty, but that list was never wired to the object under test (the 4-arg
  factory hardcodes `noop()` and its own private store), so the assertion could not fail. Reduced to
  the construction smoke check it actually was, and named accordingly.
- **The QUIC disconnect listener is now inert, and every comment that claimed otherwise was
  corrected.** `AetherNode.attachQuicDisconnectListener` was the only caller of
  `setDisconnectListener` and its listener body was a single emit into the dead sink, so both are
  gone; `QuicClusterNetwork`'s leader-side `disconnectListener.onDisconnect` now runs against the
  `noop()` default for every teardown. Liveness reaches the leader through
  `PeerConnectivityObservation` instead. The interface and its transport-side contract test are kept
  — the contract is still honest, it simply has no consumer — and both now say so.
- Verified: full-reactor `mvn clean install` (142 modules, 12,365 tests, 0 failures), repo-wide JBCT
  check, and `./forge.sh ci` (38 tests). The format gate caught two files a compile-green build did
  not — the same class that broke this branch once already.

### Added (2026-08-27 — #519 phase 1: permanent dead-config-accessor CI gate)
- New `aether/node` test-scoped gate, `ConfigKeyLivenessTest`, that scans compiled bytecode
  (`BytecodeReachability`, ASM `INVOKE*`/`invokedynamic` edges, owner-qualified so same-named
  accessors on unrelated types don't produce false matches) to catch a config record accessor
  parsed from TOML but never called by any production code path — landed ahead of, or orphaned
  behind, its own consumer. Reflective binding (e.g. Jackson) is treated as live via
  `ReflectiveConfigExemptions`, not flagged. Baseline-and-ratchet, not instant-gate: the corpus was
  triaged once (see below); the test only fails on a *new* unsuppressed dead accessor from here on.
  A synthetic fixture (`selfTest_syntheticFixture_distinguishesLiveFromDeadAccessor`) is the
  permanent positive/negative-control sensor validating the scanner itself, run on every build.
- New `@ConfigKeyLive("<ticket>: <why>")` annotation (`aether-config`, `RUNTIME`-retention,
  targets `METHOD` + `RECORD_COMPONENT`) suppresses one flagged accessor with a mandatory
  ticket-backed justification, read reflectively by the gate — deliberately not
  `@SuppressWarnings`, which is `SOURCE`-retention and invisible to a bytecode-only scanner.
- Corpus discovery (`ReactorRoots`) reads the module list directly out of `aether/pom.xml`'s
  default `<modules>` block, not this JVM's own classpath: a classpath read only sees
  `aether/node`'s own dependency closure, which is blind to `aether/cli` (a sibling of `node` that
  depends on `aether-config` directly, not through `node`) — every accessor whose only real caller
  lived in `cli` was invisible to an earlier classpath-based draft and came back false-DEAD. Fails
  loud (`missingProductionOutput()`, an explicit build instruction) rather than silently scanning
  an incomplete corpus when a declared module was never compiled in the working copy.
- Phase-1 triage of the full `ClusterBootstrapConfig` tree: 9 accessors initially flagged
  (`SourceProfile.user/key/sshPort`, `FirewallRule.description`, `SshDeploymentConfig.publicKeyFiles`,
  `TlsDeploymentConfig.clusterSecret`, `OperationsConfig.timeouts`,
  `TimeoutsConfig.healthCheck/quorumFormation`) are genuinely live, called only from `aether/cli` —
  correctly resolved once `ReactorRoots` closed the corpus gap above, no suppression needed. 7
  accessors (`AutoHealSpec`'s 6 unwired fields, #675, prior entry) plus 6 more found this pass
  (`ClusterBootstrapConfig.configVersion`, `RoleSubTable.role`, `RuntimeProfile.name`,
  `InfrastructureConfig.networkingType`, `TlsDeploymentConfig.certTtl`,
  `TimeoutsConfig.drain` in `config.cluster`) are genuinely dead, filed as
  [#693](https://github.com/pragmaticalabs/pragmatica/issues/693) and suppressed with
  `@ConfigKeyLive` citing the specific unrelated same-named accessor each one was almost confused
  with (`ClusterConfigValue.configVersion()`, `NodeLifecycle`/`ReplicationBatcher`/CTM's `.drain()`,
  `TlsConfig.clusterSecret()` on `Main.resolveClusterSecret`, etc.).
- Phase 2 (status/health field liveness, deferred from this pass per scope) tracked as
  [#690](https://github.com/pragmaticalabs/pragmatica/issues/690); #519 itself updated with the
  phase-1 census.
- [mechanism: `BytecodeReachability` walks ASM `MethodNode` instructions for `INVOKEVIRTUAL/
  INVOKESTATIC/INVOKESPECIAL/INVOKEINTERFACE/INVOKEDYNAMIC`, matched by owner-qualified
  `MethodRef`, over `ReactorRoots.productionRoots()`,
  aether/node/src/test/java/org/pragmatica/aether/deadsurface/]
- [verified: `org.pragmatica.aether.deadsurface.ConfigKeyLivenessTest` — both the synthetic
  positive/negative-control fixture and the real `ClusterBootstrapConfig`-tree gate pass; full
  `aether/node` test-source tree recompiles clean against the final `ReactorRoots`]

### Fixed (2026-08-27 — #678: replacement PEERS no longer seeded from discovered-but-dead peers)
- `ClusterTopologyManagerRecord.buildProvisionContext` — the cold-path PEERS fallback used when the
  `LeaderReconciler`'s live `clusterMembers` set is empty — filtered candidate peers with
  `isDiscoveredPeer` only. Discovery is one-way and permanent (SWIM gossip adds a peer once; nothing
  ever removes it from the dial set on death), so a just-killed host stayed eligible for a
  replacement's PEERS list forever, contradicting the neighbouring class docstring's claim that
  seeding from a live set "keeps just-killed hostnames out of the PEERS list" — that claim held for
  the live-member-set path but never for this fallback.
- Added `liveObservedPeer()`, filtering on `snapshotSource.currentMembershipView().map(MembershipView::coreMemberIds)`
  — in production (`PresenceGenerationSnapshotSource`) backed by `MembershipFsm.coreObservedMembers`,
  core members narrowed to first-hand reachability evidence (a completed QUIC handshake or a SWIM
  ALIVE observation), plus self. No new dependency: `snapshotSource` was already wired into this
  record. Before any snapshot exists (BOOTING, no reachability evidence latched yet) the filter is a
  no-op passthrough, matching the BOOTING/NORMAL fallback idiom already used elsewhere in this class
  (`resolveClusterName`, `healthyActivePeerCount`).
  [mechanism: `ClusterTopologyManagerRecord.buildProvisionContext` + `liveObservedPeer`,
  aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRecord.java]
- [verified: `ClusterTopologyManagerActuatorTest.provisionReplacement_coldPath_excludesDiscoveredButUnreachablePeer`]
  — a peer discovered via SWIM gossip but absent from the latched snapshot's `coreMemberIds` is
  excluded from the cold-path PEERS list; a peer present in both is included.
- Not a rename: the constant-true `isHealthyPeer` predicate this call site used before was already
  renamed to `isDiscoveredPeer` (#558, prior commit) to stop the name from claiming a health check
  that never happened. That rename was explicit that it did not fix the underlying defect — this
  entry is that fix, landed separately as its own review per that rename's docstring.

### Fixed (2026-08-27 — #578 follow-up: cli.md corrected to match the plan-classify-then-actuate fix)
- `cli.md`'s `aether cluster apply` description claimed every apply computes a terraform-style plan
  and executes it in waves (additions → modifications → removals, `maxUnavailable`-respecting
  rolling restart). That description is only true of the `--resume`/`--rollback` path
  (`ApplyOrchestrator` → `WaveExecutor`). Plain `apply` (no flags) actuates **scale changes only** —
  `ClusterConfigApplierRecord.classify` accepts `ScaleUp`/`ScaleDown` and rejects every other
  `DiffAction` variant (`AddSource`, `RemoveSource`, `AddRole`, `RemoveRole`, `RuntimeChange`,
  `SourceFieldChange`, `ClusterLevelChange`) with a typed `UnsupportedApplyAction`, whole-plan,
  before-any-actuation (`ClusterConfigApplier.java:72-96`); `ImmutableFieldChange` is caught one
  layer up at the route (`ClusterConfigRoutes.executeDiff:433-435`). Corrected the doc to describe
  the two paths separately rather than presenting the wave-execution description as what plain
  `apply` does [verified: `aether/aether-deployment/.../ClusterConfigApplier.java`,
  `aether/node/.../ClusterConfigRoutes.java`, `aether/cli/.../ApplyOrchestrator.java:172`]. No code
  changed — doc-only, no restructuring beyond the one inaccurate paragraph.

### Fixed (2026-08-27 — #381: application-config runtime notification catalog claim corrected)
- **Catalog row 176 claimed "Runtime notification via single-threaded executor with record diff";
  the runtime push half of that claim is dead code.** `ConfigNotificationManager.notifyInitial`
  delivers a slice's merged config exactly once, at ACTIVATE
  [verified: `NodeDeploymentState.registerSliceForConfigUpdates`]. `notifyChange` — the entry point
  for pushing a config change to an already-running slice — has **zero callers repo-wide**; a
  KV-Store `ConfigKey` write is picked up only by the separate, unrelated
  `DynamicConfigManager`/`DynamicConfigurationProvider` flat-string overlay
  (`aether/node/.../api/DynamicConfigManager.java`), which never calls back into
  `ConfigNotificationManager`. The two config-update systems were built independently and never
  bridged. Row 176 downgraded **Complete → Partial**; recovery action for an operator who needs a
  changed config section picked up: redeploy/restart the affected slice.
  [design intent — unverified] whether wiring `notifyChange` is still wanted: the
  `lastParsedConfig` field it would support a diff from is itself dead (write-only, never
  populated), and `control-plane-delegation-spec.md` (`aether/docs/specs/future/`) already lists
  `ConfigNotificationManager` as a per-node, not-leader-only component in a planned future
  architecture — left in place rather than deleted for that reason, pending a decision by whoever
  owns that spec. **No functional/production code changed** — this is a claim-only correction
  (`guarantees-corrections-needed.md` C4 already tracked this exact gap and named the two options,
  "wire it or remove it"; wiring needs a KV-router registration in `AetherNode.java`, out of this
  fix's territory).

### Fixed (2026-08-27 — #616 follow-up: GossipKeyRotationKey security-operations gap, #616 closed)
- Filed **#683**: `GossipKeyRotationKey`'s consumer path (`GossipKeyRotationHandler`, wired as the
  design comment's "sole delivery path" for gossip-key rotation) has zero production writer and no
  CLI/admin trigger anywhere in the codebase. Verified the daily automatic gossip-key rotation
  claimed in `SECURITY.md` is unaffected and independently correct — every node derives its
  current/previous/next key via HKDF from `cluster_secret` + UTC day
  (`SelfSignedCertificateProvider.deriveGossipKeyWithLabel`), with no KV/consensus dependency. The
  gap is narrower and sharper: because that daily key is itself derived from `cluster_secret`,
  rotating `cluster_secret` doesn't revoke gossip access already computed from a leaked copy — the
  KV-delivered path was meant to be the in-place escape hatch and currently cannot be invoked.
  Cross-referenced #287 (closed, rc2 — chmod 600 + off-argv hardening on a different code path) and
  `SECURITY.md`'s `cluster_secret` hygiene section, where a one-line note now cites #683.
- Closed **#616** — the KV key-type census landed in `guarantees.md` §1a (commit `cb245eb36`), and
  every gap the audit surfaced now carries its own ticket (#676 backup, #679/#680/#681 spun-off
  findings, #683 gossip-key rotation).

### Fixed (2026-08-27 — #616 durability audit: KV key-type census, backup mechanism, three new gaps)
- Added `guarantees.md` §1a: a census of all 50 `AetherKey` record types (not ~40 as originally
  estimated), current-truth-only (no sufficiency judgment, no internal-ruling references).
  `EphemeralKeys.java`'s compiled, test-pinned 17-type set is documented as authoritative for the
  declared/derivable split, superseding #616's own stale 15-type hand-list (10-type discrepancy in
  both directions, enumerated). Classified 4 fully-dead key types (`StorageBlockKey`,
  `StorageRefKey`, `CloudCredentialsKey`, `StreamPartitionAssignmentKey`) and one half-wired type
  (`GossipKeyRotationKey`: consumer fully wired as the sole delivery path, zero found production
  write/trigger site, no CLI/admin route to fire a rotation).
- Documented the real backup mechanism per #676's tracked resolution: `[backup] enabled` defaults
  `false` and additionally requires a non-blank `path` (the true gate is `Main.resolveBackup`, not
  `AetherNode.resolvePersistence`'s own redundant filter); saves fire only on quorum-loss pause,
  membership reconfigure (which persists an *empty* state, not a snapshot), graceful stop, or a
  post-restore echo — never on commit. **New finding:** the on-disk payload is an opaque base64
  blob of a generic binary snapshot, not the structured/diffable TOML `feature-catalog.md:122`
  claimed — corrected there (row 206 downgraded Complete → Partial) and in `guarantees.md` §1a.
  The manual backup/restore REST API and CLI are unconditionally disabled at both node-construction
  call sites regardless of `[backup]` config — stays tracked as **#676**.
- Filed three new issues surfaced by this pass: **#679** (`ApiKeyAuditKey` is write-only — 4
  production write sites, zero read/query sites found anywhere), **#680** (`ScheduledTaskStateKey`'s
  automatic cron/interval-fire path hardcodes `nextFireAt`/`totalExecutions`/`consecutiveFailures`
  on every write instead of reading prior state — telemetry-only, scheduling itself unaffected;
  the correct read-modify-write pattern already exists in `ScheduledTaskRoutes.java`), and **#681**
  (open question, not a reopen of the already-closed #384: no production reader was found for the
  DHT `ReplicatedMap`s — `EndpointKey`/`SliceNodeKey`/`HttpNodeRouteKey` — that actually queries them
  for a routing decision; only replication-receive plumbing and node-departure cleanup reference
  them today).

### Fixed (2026-08-27 — #616 partial: guarantees.md/known-limitations.md understated declarative stream-consumer cursor durability)
- `guarantees.md` §4 (`stream.consume` bullet and summary-matrix row 19) and `known-limitations.md`'s
  streaming-substrate bullet both described automatic cursor resume as app-`StreamAccess`-only,
  claiming the declarative `[streams.X]` consumer runtime (`ConsumerRuntimeState`) "remains
  test-only — not the wired path." Verified false: `AetherNode.java:3734-3757` composes it with
  `ClusterCursorStore` in production, layering a consensus-committed KV checkpoint
  (`AetherKey.StreamCursorCheckpointKey`) on the local cursor store — resume is `max(local,
  cluster)`, so an ownership change resumes from the last checkpoint instead of offset 0, and a
  same-node restart never re-delivers what it already processed. Noted the honest edge: a
  consensus-write failure on checkpoint is logged and swallowed, degrading that checkpoint to
  local-only durability rather than blocking delivery. Flagged, not overclaimed: the composition is
  unit-tested (`ClusterCursorStoreTest`, fakes for the KV reader/writer) but no multi-node failover
  test was found exercising it end-to-end — a coverage gap, not a correctness doubt.
- Partial: this is the #616 durability-audit finding independent of the BackupService/declared-state
  question (#676), which is still gated on an owner decision before the rest of #616's durability
  model can be written. `known-limitations.md`'s separate DHT-migration claim (line 127,
  `SliceNodeKey`/`EndpointKey`/`HttpNodeRouteKey`) is left untouched — open question, not yet traced.

### Fixed (2026-08-27 — #675: bootstrap-config.md/timeout-configuration.md advertised dead auto-heal tunables)
- `bootstrap-config.md`'s `[operations.*]` schema table and Traps callout described all nine
  `[operations.auto_heal]` fields as operator-tunable. Verified reality: only `enabled` and the
  `[cluster] max_nodes` fleet cap (a different key entirely) reach a running node —
  `Main.resolveAutoHeal` always builds from `AutoHealConfig.DEFAULT`, overriding only `maxNodes`.
  The other eight fields (`retry_interval`, `startup_cooldown`, `stale_observation_ttl`,
  `quic_miss_promotion_threshold`, `provisioning_timeout`, `provision_stability_window`,
  `decommissioned_retention`, `swim_hints_ttl`) parse and validate but are discarded. Annotated
  every dead row, added the PF-25 bootstrap-rejection note for `enabled = false`, and rewrote the
  Traps callout to enumerate all eight fields and cite #675 instead of naming only two.
- `timeout-configuration.md`'s `[timeouts.scaling] auto_heal_retry` / `auto_heal_startup_cooldown`
  rows (a third, distinct config surface — `TimeoutsConfig.java`, no call sites outside
  `ConfigLoader`) were presented as live with no caveat. Annotated both table occurrences and the
  full example config, citing #675.
- Both fixes are current-truth-plus-tracked-gap, matching the #666 citation style: no promise about
  which way #675's wire-or-reject decision lands.

### Fixed (2026-08-27 — #657: guarantees.md/known-limitations.md denied shipped durable-entity capabilities)
- `guarantees.md` and `known-limitations.md` still described durable-entity CRUD and reads as
  "planned, not wired," despite `PartitionFencedDurableEntity` (via `DurableEntityFactory`) having
  shipped as the production-wired implementation under #345/#352/#596: `aether/node` now depends
  directly on `resource-durable-entity` (previously "a dependency of nothing but its own parent"),
  `InMemoryDurableEntity` is package-private/unreachable from any deployed slice, writes are
  RF=3-replicated by default with fsync-before-ack, and non-owner calls forward to the committed
  owner instead of refusing. Rewrote the one-line orientation, summary-matrix rows for
  create/update/delete and both `BOUNDED_STALE`/`LINEARIZABLE` reads, the §6 section header and
  body, and the Known Gaps entry to reflect this; the `entity.timer`/`workflow.*`/`saga.*` row is
  untouched — those remain genuinely planned.
- Corrected a stale sub-clause inside a paragraph the ticket had marked "current, no correction
  needed": it claimed `LINEARIZABLE` was still "production-DORMANT... until node-wired (#352)",
  contradicting the rest of the page (that clause was written later than the ticket's baseline).
  Fixed to state it is live since #352 shipped, with the per-call `EntityError
  .LinearizableUnavailable` case explained as a freshness-vs-safety asymmetry, not dormancy. Same
  duplicated clause fixed in the Known Gaps tracking note.
- `known-limitations.md`'s durable-entity bullet (drifted to line 131, ticket cited 105) rewritten
  to match: CRUD + both read consistencies wired, only timer/workflow/saga still planned.
- Flagged, not fixed (outside docs territory): `ReadConsistency.java:20-27` still carries stale
  javadoc referencing "#277" and "the current HA-only in-memory cut."

### Fixed (2026-08-27 — #283: stale @Notify/interceptor/qualifier/@Scheduled claims in resource-reference.md)
- `slice-developers/resource-reference.md` had drifted further since the ticket's original
  2026-06-11 assessment. Fixed: built-in qualifier count (three → four); the config-layering/secret
  explanation, which previously implied slice-bundled `resources.toml` gets the same
  `${secrets:...}` resolution as the operator `aether.toml` (it doesn't — tracked in #269); SMTP
  config (`tls` → `tls_mode`, flat `username`/`password` → nested `auth.username`/`auth.password`)
  and HTTP vendor config (`provider` → `provider_hint`, `from` → `from_address`) to match the real
  `SmtpConfig`/`SmtpAuth`/`HttpEmailConfig` record shapes (#271); the TIERED cache row's false
  "cluster-wide consistency" claim, replaced with the real local-L1/distributed-L2-fallback
  behavior and a cross-node-invalidation gap note (#279).
- Added gap notes to the Retry and Metrics TOML examples: `RetryConfig.backoff_strategy` and
  `MetricsConfig.tags` (`List<String>`) have no config-level default or binding path through the
  generic config binder today, so the examples shown don't fully provision as written (#278).
  Corrected the Rate Limit and Logging config tables' Default column from fabricated values to
  `required` — neither `RateLimitConfig` nor `LogConfig` declares a `DEFAULT` static field, so the
  binder has no fallback and omitting any key fails config binding.
- Rewrote the `@Scheduled` section: replaced the fictional `leaderOnly: boolean` field with the
  real `execution_mode` (`ExecutionMode`: `single`/`all`, default `single`) across the config
  table, all three TOML examples, and the Behavior bullets — `single` is leader-only, `all` runs on
  every quorum-participating node (leader or follower), correcting the previous "each node with the
  slice" description [verified: `ScheduleConfig.java`, `ExecutionMode.java`,
  `ScheduledTaskManager.shouldRunInCurrentState`/`startEligibleTasks`]. Cross-refs #272/#273.

### Fixed (2026-08-27 — #310: 12-management.md base paths, /api/aspects, CLI typos)
- `architecture/12-management.md`'s "Endpoint Categories" table listed every management-API
  category under a fictional `/api/v1/...` prefix; the real `ManagementRoute` enum has no version
  segment anywhere [verified: `aether/aether-management-api/.../route/ManagementRoute.java`].
  Stripped the prefix, and fixed two categories whose base path was wrong outright: "Updates" (the
  real feature is the deploy lifecycle at `/api/deploy`) and "Artifacts" (upload/download/list live
  under `/repository`, not `/api/artifacts`, which serves only `/api/artifacts/metrics`).
- `/api/aspects` and `aether aspects set <artifact> <method> METRICS` do not exist anywhere in the
  codebase; the real feature is per-method observability depth/config. Replaced with
  `/api/observability` and `aether observability depth set <artifact>#<method> <threshold>`
  [verified: `ObservabilityCommand`, `OBSERVABILITY_DEPTH_*`/`OBSERVABILITY_CONFIG_*` routes].
- Fixed the Prometheus scrape path (`/metrics/prometheus` → `/api/metrics/prometheus`) and two CLI
  typos (`aether blueprint apply/delete` → plural `blueprints`; `aether upload <jar>`, not a real
  command, → `aether artifacts push <group:artifact:version>`). Corrected the endpoint count ("30+"
  → "190+", counted directly from the enum). Left the REPL and WebSocket sections untouched — both
  verified accurate against `AetherCli.java` / `ManagementServer.java`.

### Fixed (2026-08-27 — #316: stale SecurityMode.NONE-default premise in 10-security.md)
- `architecture/10-security.md`'s "API Key Authentication" section predated #290 and described a
  stale 4-field `AppHttpConfig` (including a nonexistent `forwardTimeoutMs` field, no
  `securityMode`/`jwtConfig`) with no mention of the `JWT` mode. Replaced with a "Security Modes"
  section covering all three `SecurityMode` values (`NONE`/`API_KEY`/`JWT`), the correct default
  (`API_KEY`, per #290) and bootstrap-admin-key flow, and the real 9-field `AppHttpConfig` +
  `JwtConfig` record shapes [verified: `SecurityMode.java`, `AppHttpConfig.java`, `JwtConfig.java`].
  Fixed the "Security Boundaries" bullet to reflect the default (not an unconditional requirement)
  and added `SECURITY.md` as the page's primary trust-model/operational-posture pointer. This file
  was out of scope for the #318 sweep (which fixed `cli.md`/`management-api.md`/
  `getting-started.md` for the same stale-default class of claim) and is fixed here under its own
  ticket.

### Changed (2026-08-27 — #321 follow-through: node-binary version skew now tracked as #666)
- `reference/versioning-and-compatibility.md` ("Rolling upgrades and node version skew"):
  replaced the "no visible spec or tracking issue" language with a reference to #666 (filed
  2026-08-27), stating its scope (a version field on `Hello` plus a join-time mismatch policy,
  refuse-or-degrade, decision pending) and explicit non-goals (version negotiation, codec
  evolution rules, mixed-node-binary rolling-upgrade support). The "no recorded decision on
  runtime-owned vs. application-owned" sentence is kept but reframed as a **tracked-not-designed
  boundary** — #666 scopes that mismatch-policy decision without yet making it; this page still
  does not invent one. Added a cross-reference to the new `known-limitations.md` section.
- `reference/known-limitations.md`: added a "Node-binary version skew" row to the "Scope at a
  glance" table (tracking: #666) and a full section describing the gap, why the window to add a
  `Hello` version field closes at GA (an old node can't parse an extended `Hello`), and the
  operator-facing fallback (canary-wait rolling upgrade, no rc-skipping) until #666 lands.
  Cross-links back to `versioning-and-compatibility.md` for the technical writeup.
- No design/policy decision was made in either edit — both document that a tracking issue now
  exists for a previously-untracked gap, per the ticket's actual (minimal, detection-only) scope.

### Fixed (2026-08-27 — #318 audit-trail: stale SecurityMode/security-default claims)
- Swept `aether/docs/**` and top-level `*.md` for claims about the app-http `security_mode`
  default that predate #290 ("secure by default") and #573 (management-API deny-by-default).
  Verified current behavior against source: `ConfigLoader.populateAppHttpConfig` defaults
  `security_mode` to `API_KEY` when omitted (`aether-default.toml`, the shipped Layer-1 config,
  sets no explicit `security_mode`), and `BootstrapAdminKeyRegistrar` auto-generates one ADMIN
  key on first leadership when none was provisioned, printing it once. Fixed 3 docs that
  understated this (stated or implied `NONE`/no-auth as the default): `reference/cli.md`
  (Security Modes table + example), `reference/management-api.md` (Security Modes table +
  per-mode config sections, including a stale "auto-upgrade only when keys present" claim that
  no longer matches the unconditional `explicitMode.or(API_KEY)` default), and
  `slice-developers/getting-started.md` ("Securing Your Endpoints"). Added a narrow
  design-intent-vs-current-behavior note to `specs/rbac-spec.md` §7 (a 2026-02-23 DRAFT Tier-1
  proposal predating both fixes, whose "no security configured → Public" framing for the
  management API is no longer accurate) rather than rewriting the spec — flagged for #318 to
  decide whether to correct in place or point at SECURITY.md. Confirmed by source read that the
  Ember/Forge in-JVM harness factories (`AppHttpConfig.appHttpConfig(int, ...)` etc., used by
  `ForgeServer`) are a separate, hardcoded `NONE` default unrelated to the node/`ConfigLoader`
  path — `getting-started.md`'s existing description of that as current dev-harness behavior
  was already accurate and left unchanged, per instruction not to characterize it as final or
  transitional. `SECURITY.md` was already correct (checked in an earlier pass this session).

### Changed (2026-08-27 — #317: Status field on architecture docs)
- Added `**Status:** Current` (no fabricated last-reviewed date) to the 16 header-less docs in
  `aether/docs/architecture/` (`00-overview.md` through `15-resource-and-isolation-model.md`).
  `resilience-operability-principles.md`, the 17th file in that directory, already carried a
  specific dated status (`Adopted (design-stream, 2026-07-07)` with `Feeds:`/`Sources:`
  provenance) and was left untouched rather than downgraded to the generic placeholder.
  Reformatting the ~47 specs that already have some form of status header in an inconsistent
  format is deliberately deferred to #318's cross-check audit, so each doc is only touched once
  (format + verification together) instead of twice. "Current" is a pre-audit placeholder with
  a documented upgrade path: #318 should promote it to "Current — verified against <release>"
  once each doc is individually checked against the shipping codebase. Closing note on #317,
  cross-referenced on #318: https://github.com/pragmaticalabs/pragmatica/issues/317#issuecomment-5435597489

### Fixed (2026-08-27 — #315 follow-up: "NOT IN RC1" banner wording)
- The 5 designed-only specs under `aether/docs/specs/future/` (`hierarchical-storage-spec.md`,
  `cloud-provider-digitalocean.md`, `declarative-http-client-spec.md`, `control-plane-delegation-spec.md`,
  `fluid-migration-spec.md`) plus `future/README.md` had their "NOT IN RC1 — design only" banners
  reworded to "Design only — not implemented in the 1.0.0 line." The RC1-specific wording predated
  the current rc3 line and risked misreading — a reader could take "not in RC1" as already resolved
  by rc2/rc3 rather than as a standing statement that none of these 5 are implemented in the 1.0.0
  release line at all. No implementation-status claim changed, only which version label backs it.
  Historical narrative in `CHANGELOG.md`, `MAILBOX.md`, and `.internal/documentation-overhaul-plan-2026-06-11.md`
  left as-is (dated records of what was decided/done at the time).

### Fixed (2026-08-27 — #315 follow-up: archive index hygiene)
- `aether/docs/archive/README.md` had 3 dead links (`mcp-integration.md`, `ai-integration.md`,
  `kv-schema-simplified.md` — all three deleted from the tree back on 2026-02-16) and 7 files present
  in the directory but missing from the index (`aether-high-level-overview.md`, `canary-blue-green-spec.md`,
  `clusterdeploymentmanager-implementation-guide.md`, `dependency-injection-summary.md`,
  `implementation-plan.md`, `nodedeploymentmanager-implementation-guide.md`, `typed-slice-api-design.md`).
  Removed the 3 dead rows and added the 7 missing ones with a status and, where a current-doc
  successor exists, a pointer to it. All 14 files in the directory are now indexed; 0 dead links remain.

### Fixed (2026-08-27 — #315 follow-up: stale `aether/docs/internal/` path references)
- Repo-wide sweep for references to the pre-#315 path `aether/docs/internal/` left dangling
  by the dot-prefix rename to `aether/docs/.internal/`. Fixed 20 references across 9 files
  (`security-subsystem-spec.md`, `deployment-recovery.md`, `membership-architecture-v2-spec.md`,
  `membership-unification-spec.md`, `cli.md`, `management-api.md`, `cluster-topology-overhaul-spec.md`,
  `worker-membership-spec.md`, plus relative-path hits in `in-memory-streams-spec.md`,
  `streaming-spec.md`, `http-media-types-spec.md`, `feature-catalog.md`) and one pre-existing,
  unrelated relative-path bug (`future/control-plane-delegation-spec.md` pointed one directory
  short even before the rename). Also closed 3 dead links to `development-priorities.md`, a
  planning doc deleted from the tree entirely on 2026-06-13 — repointed to GitHub Issues as the
  current worklog (feature-catalog.md #208). Out-of-territory hits (scripts, CI, Java
  Javadoc/comments in `aether/cli`, `aether/node`, `aether/tests`, `aether/forge`) and two open
  questions (a gitignored, per-machine root `CLAUDE.md` in the sibling clone with the same stale
  path, and `jbct/docs/` territory ownership) logged in `MAILBOX.md`.

### Added (2026-08-27 — #321: versioning-and-compatibility reference doc)
- New `aether/docs/reference/versioning-and-compatibility.md`: maps the four independent
  versioning surfaces (product release, envelope format, slice HTTP API, management HTTP API)
  with verified status for each — envelope versioning and slice API versioning (#198) are real and
  built; management API versioning (#300) is Draft and not implemented (routes still bare
  `/api/...`, verified directly against `ManagementRoute.java`); no project-wide SemVer commitment
  is published anywhere in this repository. Flags an open gap found during research, not previously
  documented anywhere user-visible: no version field or codec-evolution design exists for the
  node-to-node handshake/wire protocol, so there is no recorded runtime-owned-vs-app-owned decision
  for node-binary version skew during a rolling upgrade — a real, ticketless gap first flagged
  internally 2026-06-11 and still open as of this writing. `SECURITY.md`'s forward-pointer to this
  document updated now that it exists.

### Added (2026-08-27 — #320: repo-root CONTRIBUTING.md)
- New `CONTRIBUTING.md`: license implications of contributing by directory (BSL-1.1 under
  `aether/` and `jbct/slice-processor{,-tests}/` is not OSI-approved open source; Apache-2.0
  elsewhere), the SPDX header each path needs, verified build/test expectations sourced directly
  from `build.sh` and `.github/workflows/ci.yml` (including why `build.sh`'s mutating format/lint
  goal should be run locally rather than relying on CI's non-mutating check), fork/branch/PR
  workflow targeting `main`, a requested-but-not-enforced DCO sign-off convention (verified no
  DCO/CLA bot exists in `.github/` today), and an inline code-of-conduct section.

### Added (2026-08-27 — #319: repo-root SECURITY.md)
- New `SECURITY.md`: GitHub private-vulnerability-reporting as the disclosure channel; Aether's
  single-trust-domain threat model; verified default management-API posture (`SecurityMode.API_KEY`
  by default since #290, not `NONE` as originally assumed — the bootstrap admin key flow, printed
  once on first leadership); how to assign `authorization_role` per API key; the still-open
  `cluster_secret` Docker-Compose env-var exposure (#287 residual, verified still present); a
  checklist for recognizing an untrusted-network deployment; and an explicit note that the
  runtime/slice boundary is not a JPMS-hardened sandbox today (no `module-info.java` exists anywhere
  under `aether/`) — corrects the security-subsystem-spec's forward-looking framing against current
  code.

### Changed (2026-08-27 — #315: docs Phase 1 structural cut)
- `aether/docs/internal/` renamed to `aether/docs/.internal/` (235 files, dot-prefixed so a
  future mkdocs build excludes it by convention; contents untouched).
- `aether/docs/operator/` merged into the existing `aether/docs/operators/`; `runbooks/lifecycle-verification.md`
  moved under `operators/runbooks/`. The singular `operator/` directory no longer exists.
- `aether/docs/contributors/architecture.md` deleted — stale duplicate overview citing archived
  vision docs; all inbound links now point to `aether/docs/architecture/00-overview.md`.
- 4 dead specs archived to `aether/docs/specs/archive/` (each self-declared superseded by
  `cluster-topology-overhaul-spec.md` or its own v2): `swim-driven-topology-spec.md`,
  `membership-architecture-v2-spec.md`, `membership-unification-spec.md`, `integration-test-overhaul-spec.md`.
- 5 designed-only, zero-implementation specs moved to `aether/docs/specs/future/` with a
  "NOT IN RC1 — design only" banner added: `hierarchical-storage-spec.md`,
  `cloud-provider-digitalocean.md`, `declarative-http-client-spec.md`,
  `control-plane-delegation-spec.md`, `fluid-migration-spec.md`.
- New `aether/docs/specs/README.md`, `specs/archive/README.md`, `specs/future/README.md` indexes.
- `aether/docs/README.md` hub index regenerated from the current tree (previously ~2.5 months
  stale, missing the entire `architecture/` series and half of `reference/`/`operators/`);
  two pre-existing dead links from a March 2026 reorg (`slice-developers/development-guide.md`,
  `slice-developers/infra-services.md`, both already archived) fixed at their 4 remaining
  referring sites. Public "Internal" hub section removed — dot-prefixed dirs are engineering
  scratch, not curated navigation.

### Changed (2026-08-27 — #317: Status field added to headerless specs)
- `aether/docs/specs/pg-persistence-spec.md` gains `**Status:** Implemented` — the spec's
  parse/schema/codegen pipeline matches shipped code in `aether/pg-tools/` (pg-parser,
  pg-codegen, pg-schema, pg-maven-plugin) component-for-component.
- `aether/docs/specs/landscape-spec.md` gains `**Status:** Draft` — design landed via #462,
  no matching implementation found anywhere in this codebase.
- Full #317 (Status-header convention across all `specs/*.md` and `architecture/*.md`) still
  pending a scope ruling; see MAILBOX.md.

### Fixed (2026-08-27 — #660: Rabia sync adoption counted self twice over, deadlocking a bare-majority cold start)
- **Sync adoption now requires `clusterSize / 2` PEER responses, with self completing the majority.**
  The gate compared `clusterSize / 2 + 1` against a response count, but responses only ever arrive from
  peers (`broadcastPayload` iterates `peers`; self is never one), so it silently demanded `quorum + 1`
  LIVE nodes. A bare-majority cold start — 3 of 5, or a 3-node cluster with one node down — sat in
  `Syncing` forever: consensus never reached ACTIVE, so no `QuorumEstablished` dispatched, no leader was
  elected and no reconciler ran, while every link and every SWIM view stayed healthy. Introduced by
  `36712ba5a`, whose never-derive-a-threshold-from-connectivity fix was correct and overshot by one:
  quorum ESTABLISHMENT counts self, adoption did not. The threshold is still derived from `clusterSize`
  ALONE — never from live, connected or reachable counts.
  [verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/PostRestartSlowRejoinDeficitFillProbeTest.java]
- **The same off-by-one made the single-node threshold unsatisfiable.** `clusterSize <= 1` returned 1,
  and a one-node cluster has zero peers to produce that one response, so it could never leave `Syncing`
  either. Self alone is the majority of a 1-node cluster; the requirement is now zero.
  [mechanism: clusterSize / 2 == 0 at clusterSize 1, and self is the whole majority]
- **Self carries weight as a FLOOR, not as an adoption candidate** (`ownStateFloor`). Relaxing the
  threshold makes the responder set a minority, so the intersection property that makes adoption safe
  holds only over `{self} ∪ responders` — self must be able to REFUSE a response set that is behind it,
  or a node holding a committed phase could adopt a state that lost it. The floor is the more advanced of
  the persisted and the LIVE phase, because `persistence.save` runs at pause/reconfigure/stop/restore and
  never on commit, so the persisted snapshot lags live state without bound. When no response beats the
  floor the node activates on its own state and installs nothing, rather than calling `restoreSnapshot`
  with a staler picture while advance-only `currentPhase` hides the loss. Refusal is on `<`, not `<=`: an
  equal phase is an equal committed prefix. [mechanism: quorum intersection over {self} ∪ responders]
- **The adoption candidate stays the max over peer responses only**, which is what keeps the ratified
  §6.4 boot future-history detector alive: folding self into the candidate makes `persisted > candidate`
  unsatisfiable and retires the mixed-wipe / `down -v` detector — and burns its one-shot latch — without
  deleting a line or failing a test. [mechanism: detector compares self against the CLUSTER-reported phase]
- **D9 (`cluster-topology-overhaul-spec.md` §6.4) is SUPERSEDED IN PART, deliberately.** Detection is
  unchanged — the WARN and the `onBootFutureHistory` journal feed still fire. The OUTCOME changes: D9 as
  ratified restored anyway, regressing a node carrying future history onto the cluster's older state; the
  adoption floor now refuses that candidate, so the node HOLDS its own history and installs nothing.
  Committed state must not be discardable by sync adoption — the safety argument outranks detect-only.
  Reachable only with durable persistence configured (`resolvePersistence` defaults to in-memory). The
  spec entry carries a supersession note. Both halves are pinned.
  [verified: integrations/consensus/src/test/java/org/pragmatica/consensus/rabia/RabiaSyncAdoptionQuorumTest.java]
- **Operator consequence of that change, with its recovery action** (`aether/docs/operators/runbooks/backup-recovery.md`):
  with backups enabled, a deliberately reset cluster plus one node that kept its old backup directory no
  longer converges by silently discarding that node's history — the node activates on its own old state
  and diverges. Recovery: clear each node's `[backup] path` before restarting into a reset cluster. The
  `BOOT FUTURE-HISTORY` WARN names the condition, and it is now in the runbook's troubleshooting table.
- **Adoption refuses outright at `clusterSize < 1`.** The requirement would be `0 / 2 == 0`, so a node
  would meet its own threshold with zero responses and activate alone; the previous `clusterSize <= 1 ? 1`
  blocked that by accident. [mechanism: explicit guard in adoptionThresholdMet]
- **Silence killer: the `Syncing` retry loop logged only at TRACE**, so the deadlock produced tens of
  megabytes of log with no INFO-level indication. It now emits a periodic WARN (every 6th unsatisfied
  round, roughly every 30s at the default 5s retry) carrying the deciding arithmetic — responses
  collected, responses required, responder ids, clusterSize — and the operator consequence. Operator
  recovery: the state clears when a `clusterSize / 2` peer majority answers; a cluster stuck here needs
  more members started, not a restart of the stuck node. [mechanism: warnIfSyncStuck]
- Residual recorded as **#667** (rc4): adoption cannot tell a live-ACTIVE responder from a cold one, so
  for a single node rejoining a still-live cluster with in-memory persistence the relaxed threshold is
  weaker than the old bound. Sound for the cold-bootstrap case this fix targets; the principled closure
  is carrying the responder's engine state in `SyncResponse`, which is protocol surface and deliberately
  out of scope here. [design intent — unverified]

### Fixed (2026-08-27 — #590 follow-up: the PROVISIONED path now carries the role it was provisioned with)
- **`EmberComputeProvider.createFrom` dropped `ProvisionContext.role` and called the bare `addNode()`,
  so every node the CTM minted in-JVM — auto-heal replacement or worker-reconcile worker — came up
  advertising NO role and therefore classifying as a CORE** (`isCoreRole(role) = !"worker".equals(role)`;
  blank counts as core). `addWorkerNode()` fixed the paths a test controls; this is the path no test can
  opt out of. Every production provider translates that same field into `AETHER_ROLE` / `aether-role`,
  which the booting node re-asserts as its SWIM `LABEL_ROLE`, so propagating it is fidelity restoration,
  not new behaviour. The label is stamped VERBATIM — normalising it here would mask exactly the mislabel
  a harness exists to expose — and a blank role stamps NO label, mirroring a node booted without
  `AETHER_ROLE` (`Main.collectNodeLabels` puts the key only when the env var is present).
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/EmberAddNodeRoleLabelTest.java`]
- **The equivalence that keeps existing core provisioning unchanged is asserted, not assumed.** `core`
  and blank are both non-`worker`, so both classify as core — pinned directly against
  `MemberDescriptor.isCoreRole`, and confirmed end-to-end: the three probes that drive CTM provisioning
  (`MembershipChaosCycleTest`, `ProvisioningRecoveryAfterFailureBurstProbeTest`,
  `PostRestartSlowRejoinDeficitFillProbeTest`, 4 tests) stay green while their provisioned replacements
  now advertise `labels={role=core}` where they previously advertised `{}`.
- Guard extended to six tests, driving `ComputeProvider.provision(spec)` rather than `createFrom`
  directly so the static `ProvisionRequest.resolve` choke sits inside the assertion. Both mutations go
  red on exactly the right tests: dropping the propagation fails the two provisioned cases with
  `Saw labels={}`; stamping blank unconditionally fails the blank case with `Saw labels={role=}`.
- **Fragility recorded, not silently absorbed — #689 (rc4).** The role is a SELF-ASSERTED label, and its
  unknown case fails toward NOT acting: a node intended as a worker whose label never arrives classifies
  as core, suppresses the core-absence fence, and nothing reports it. The suppressor default is
  deliberate and unchanged; #689 asks for the leader-side WARN and operator surface for the divergence
  between intended and advertised role, both of which the leader already holds.

### Added (2026-08-27 — #590: the community fence's no-double-active ordering, MEASURED)
- **`CoreAbsenceFenceOrderingTest`** (forge, Heavy, 5 cores + 1 worker) proves the #590 ordering under
  TOTAL isolation: a black-holed worker fences ITSELF via `DrainReason.CORE_ABSENCE`, locally, with no
  consensus write reachable, and does so strictly before `community_absence`. Six runs: fence
  9673–9704ms against a 10000ms window, margin 10296–10327ms before the core would re-place — a 31ms
  spread. The invariant graduates from `[mechanism: core_absence < community_absence refused at config
  load]` to measured, **for the total-isolation case only**.
  [verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/CoreAbsenceFenceOrderingTest.java]
- **Explicitly NOT proven**: partial partition (a community reaching its own members but not the core —
  `blackhole` is per-node and total) and real-network severance. Both inherit to #367 output 1; this
  class must not be cited as full CP-contract proof.
- **`EmberCluster.addWorkerNode()`** — harness FIDELITY restoration, not a new capability. Community-tier
  mechanisms gate on `MemberDescriptor.isCoreRole(role) = !"worker".equals(role)`, where blank counts as
  CORE. Production nodes self-assert that label (`AETHER_ROLE` → `NodeInfo.LABEL_ROLE`); Ember set none,
  so every in-JVM node read as a core and the fence was structurally suppressed. `addNode()` is
  byte-identical and still advertises no label; both halves pinned by `EmberAddNodeRoleLabelTest`.
- **Four runs failed first, and none of them meant what they looked like.** Suppressed-by-design read as
  "the fence is broken" (armed=true, sinceLastPingMs=40922, remainingMs=0, fenced=false) until a
  precondition assertion showed the node self-identified as a core. Then the fence fired but the
  observer vanished with it — the drain removes its own node — so the watch now also accepts
  deregistration as evidence and polls at 20ms to catch the direct flag. **Without the precondition,
  those runs would have shipped as a confident false defect against working code.**

### Fixed (2026-08-27 — #558 sweep miss: two `NodeHealth` sites broke the branch, and one was an operator-facing lie)
- **`aether/node` stopped compiling for four commits.** The `NodeHealth` delete missed two sites in
  `ClusterTopologyRoutes` because the enumerating grep was truncated with `| head -5` and only the
  predicted downstream modules were built. Fixed in `ba1317723`; the sweep was re-run untruncated (zero
  residual references) and every downstream module compiled. Both failure modes are now repo build
  conventions.
- **`GET /api/cluster/topology` reported `"health": "HEALTHY"` for every node it had ever discovered,
  dead ones included** — it read `NodeState.health`, which nothing ever mutated. The field now reports
  what is actually known: `CONNECTED` (live transport link observed), `DISCOVERED` (known id, no live
  link), `UNKNOWN` (not in the observer's map). **This is a response-VALUE change on a management
  endpoint**, documented in `management-api.md` with the value table and a caution that `DISCOVERED` is
  not a claim of ill health. Same defect class as #678, on the surface an operator consults to decide
  whether a node is alive.
- `management-api.md`'s `coreCount` fallback reference updated to `reportedActiveNodeCount()`.


- **`TopologyManager.healthyActiveNodeCount()` → `reportedActiveNodeCount()`** (public default +
  `TopologyObserver` override + both production callers + 13 count-asserting test sites). Post-delete
  the method performs no health filtering, so the old name asserted a check that does not happen — on
  a PUBLIC interface, which is where that defect class mints the next #678.
- **No deprecation alias.** Aether is not published (#668) and the two callers in
  `ClusterTopologyManagerRecord` are the entire consumer set; pre-GA is when an API rename is free.
- **The name asserts NO filter property, deliberately, because the method is genuinely two things.**
  `TopologyObserver` returns the membership view's on-duty count in NORMAL — post-#557 that requires
  OBSERVED reachability (completed QUIC handshake or SWIM ALIVE) — and falls back to a DISCOVERY count
  in BOOTING to break the cold-start catch-22 where the snapshot only exists after consensus commits.
- Two better-reading candidates were rejected for being false in one mode each, which is precisely the
  defect this rename removes. `observedActiveNodeCount` (used briefly, then corrected) overclaims —
  there is no observation during BOOTING. `discoveredActiveNodeCount` names a SUPERSET of what NORMAL
  returns, and for a COUNT that is the more dangerous error: a caller comparing it against a configured
  size would silently over-expect. "Reported" is true in both modes; which authority and what it filters
  is the docstring's job, not the name's. Per the repo's claim discipline — between two candidate
  phrasings, choose the weaker one.
  [mechanism: TopologyObserver.reportedActiveNodeCount mode split; PresenceMembershipView.healthyOnDutyCount]


- **Deleted** `NodeHealth`, `NodeState.suspected(...)`, `NodeState.canAttemptConnection(...)`, the
  `health` / `failedAttempts` / `nextAttemptAfter` components, and the now-unfed
  `BackoffConfig.shouldDisable(...)` (zero callers). `NodeState` is now `(info, firstSeen)` with a
  `discovered(...)` factory — discovery is all that map ever recorded.
- **Driver: there is exactly ONE re-dial authority, and it was never this one.** The transport layer
  owns re-dial policy (QUIC peer-phase dedup, the in-flight CONNECTING guard, the per-attempt dial
  timeout) and SWIM owns suspicion. Wiring this backoff would have installed a SECOND authority that
  nothing exercises, and two mechanisms disagreeing about when to re-dial is worse than one. A
  vestigial gate is a standing invitation to wire it someday and create that disagreement.
- Behaviour-preserving by construction: `suspected(...)` had zero callers and the map's only mutations
  were `putIfAbsent(healthy)` / `remove`, so `health == HEALTHY` was constant-true and
  `canAttemptConnection` a constant-true gate. Removing a constant-true filter cannot change a result.
- **Counts renamed to what they actually count**, which is NOT what #558 proposed. The ticket said
  rename to `discoveredPeerCount`; after `dc24377a7` that would have been a fresh lie. The three
  methods carrying the vacuous filter each counted something different:
  `legacyHealthyActiveNodeCount` → `discoveredNodeCount` (all known nodes — a genuine discovery
  count), `swimHealthyCorePeerCount` → `knownCorePeerCount` (known peers ∩ authoritative core set),
  `legacyHealthyActivePeerCount` → `connectedPeerCount` (peers observed CONNECTED — a reachability
  count, not discovery). `TopologyManager.healthyActiveNodeCount()` keeps its name for now: public
  default, production callers, and renaming it is an API change that belongs in its own commit.
- **Stale surfaces swept, per the closing-comment convention.** `swimHealthyCorePeerCount`'s docstring
  claimed peers were filtered "HEALTHY in the live SWIM `nodeStatesById` map" — never true, and the
  name asserted it too. Corrected, along with the `NodeHealth` reference in the node-count docstring.
- **The dead filter was NOT correctness-neutral — see #678.** `ClusterTopologyManagerRecord` filtered a
  provisioned replacement's PEERS list through the same constant-true predicate (`isHealthyPeer`, now
  `isDiscoveredPeer`), while a neighbouring docstring asserted the intersection kept dead hosts out.
  It never did. Renaming makes today's behaviour explicit; fixing it needs a real liveness source and
  is a provisioning change deliberately left to #678 rather than ridden in on a naming cleanup.


- **Premise validation first**: #557's three reported defects are all already fixed — the discovery-based
  boot count by `dc24377a7`, the `syncQuorumSize` collapse by `36712ba5a` plus #660, and the
  MembershipView path by the rewire of `AetherNode.presenceMemberSupplier` to
  `MembershipFsm.coreObservedMembers(self)`, which admits a peer only on `PeerConnected` or
  `SwimHealthy`. The quorum numerator IS observed reachability.
- **`PresenceGenerationSnapshotSource`'s class docstring still claimed `coreCountedMembers()`**, which
  is not harmless: #557's own diagnosis comment concluded the path used "health assumed, not observed"
  — reasoning from the stale description rather than the wiring. Corrected, with the distinction and
  its consequence stated where the next reader meets it.
- **`PresenceGenerationSnapshotSourceQuorumCompositionTest`** closes the coverage gap between the two
  layers that were already tested: the FSM projection below and the BOOTING/legacy path above, with
  nothing exercising the seam that actually decides boot quorum. A seed-only FSM must publish NO view;
  the same source wired to `coreCountedMembers` publishes one immediately with a full quorum numerator
  and zero peers reachable, which is #557 itself — kept as a permanent discriminator so the test cannot
  pass vacuously. Mutation-checked.
  [verified: aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/generation/PresenceGenerationSnapshotSourceQuorumCompositionTest.java]
- Stated limit, not implied coverage: the test MIRRORS `AetherNode.presenceMemberSupplier` rather than
  reading it — that supplier is a local inside a 5000-line assembly method with no seam — so a rewire of
  `AetherNode` itself would leave it green. Recorded in the class rather than glossed.


- `MembershipChaosCycleTest` (forge, Heavy, 5 nodes) drives **kill → detect → decommission → heal**
  against the current SWIM + MembershipFsm stack: a hard-killed core must leave counted membership,
  auto-heal must reach the real `ComputeProvider` path, and the replacement must boot, join and be
  counted. A recording provider makes the heal leg provable — counted membership returning to five
  could otherwise be a rejoin rather than the cluster healing itself.
- **Not a revive.** `MembershipChaosSpikeTest` was deleted in `c0c4e6444` as part of the deliberate v2
  membership migration, not for flakiness; it still imports `PhiAccrualDetector`, a class that no
  longer exists, so restoring it would not compile. Its published timeline was measured against a
  detector since replaced, so those numbers are recorded as history and deliberately not asserted.
- Budgets are DERIVED from shipping constants (auto-heal startup cooldown 15s, SWIM suspicion 10s +
  NTT departure 15s, auto-heal retry 10s, provisioning timeout 60s), and the timeline is **measured**
  across three runs and recorded in the class: decommission 5.6–8.1s, heal 21.3–23.8s, leader recovery
  22.3–24.8s, exactly one provision every run. In-process numbers on one host — a regression baseline
  and order-of-magnitude check, explicitly not a production SLO.
  [verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/MembershipChaosCycleTest.java]
- The first run failed on an assertion of mine, not on the system: leader presence was sampled at the
  instant heal completed, while a re-election was legitimately in flight as the replacement joined.
  Leadership is a convergence property, so it is now awaited — and how long recovery takes became a
  reported number rather than a hidden assumption.
- Complements `MembershipBlackHoleSpikeTest` rather than duplicating it: that covers the harder
  detection case (silent but connected) and stops at terminal removal; this covers the ordinary kill
  and carries it through heal.


- `aether/tests/integration/coordination_slope.py` — samples QUIC protocol-message rate per CORE node
  (`quic_messages_sent_total` + `quic_messages_received_total`, differenced) plus `cpu.usage` /
  `heap.used`, for the re-scoped #591 worker-count sweep. It reports a **worker-count** slope with
  community count pinned at 1, never a community-count slope: group splitting is dead code (#673), so
  that axis is a property the shipping system does not have.
- `CoordinationSlopeInstrumentTest` (forge, 3 nodes) validates the sampler against a LIVE cluster —
  the endpoint contract on every core, counter cumulativeness, and the sampler itself end to end.
  The remote sweep is expensive and infrequent, so a sampler that silently reported zeros would not
  surface until the numbers were already in the book.
- **That validation immediately earned its keep, catching two defects in the instrument:**
  `GET /api/metrics` returns a CLUSTER-WIDE load map (every node answers for every node) although the
  route is declared LOCAL — the sampler had assumed per-node and could not disambiguate; and the
  per-core sampling was SEQUENTIAL, so with three cores and a 60s window each core was measured over a
  different minute and the "slope" summed rates that never coexisted. Cores are now differenced over
  one shared window, and `--node-ids` is required so worker entries can never be folded into the core
  mean. [verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/CoordinationSlopeInstrumentTest.java]
- Missing counters raise rather than returning zero, a backwards-going counter voids the sample, and
  backpressure/write-failure deltas are reported as saturation guards — a slope measured while those
  climb is measuring congestion, not coordination cost.


- **`./forge.sh`** — the first local gate that actually RUNS a multi-node cluster. `./forge.sh`
  (smoke), `ci` (everything except `@Tag("Heavy")`, exactly what CI runs), `full`, or a single class
  name. Until now nothing local executed forge: `build.sh` compiles the tests and says so in its own
  banner, and `mvn test` runs surefire while forge tests are failsafe ITs.
- **A `Smoke` tag** on `ClusterFormationTest`, `SliceInvocationTest` and `StreamOwnershipDriverFenceTest`
  — the ticket's formation + deployment/invocation + one-stream-path set. Measured, not estimated:
  **97s wall for 15 tests** (formation 25.9s, stream ownership 36.2s, invocation 31.6s).
- **Per-test JUnit timeouts** (`junit-platform.properties`: 10m per test method, 8m per lifecycle
  method) so a hung forge test is reported BY NAME instead of consuming the whole job anonymously.
  Verified by temporarily dropping the lifecycle budget to 3s, which produces
  `[ERROR] ClusterFormationTest » Timeout setUp() timed out after 3 seconds` — class and method both
  named. The same proof aimed at the testable-method budget changed nothing, because forge classes
  spend their time in `@BeforeAll` standing a cluster up rather than in `@Test` bodies: a config file
  that is present and parsed can still be inert against the case you care about.
  The lifecycle budget is 8m rather than a tighter number because the tests' own awaits are the
  intended failure mechanism and this backstop must never fire first — the longest internal guard in
  the non-Heavy set is 240s, and at 5m a slow-but-succeeding formation on a loaded runner could have
  tripped it and manufactured the flakiness it exists to diagnose.
  [verified: aether/forge/forge-tests/src/test/resources/junit-platform.properties]
  Deliberately not the ticket's suggested `forkedProcessTimeoutInSeconds`: that is already set to 1800
  in the pom and has failed to reap a hung fork at least three times. Honest limit — a JUnit timeout
  interrupts the test thread, so a hang in a non-interruptible wait may not unblock; the value is the
  named failure in the report, not a guaranteed JVM exit. `forkedProcessTimeoutInSeconds` stays as the
  outer backstop. [mechanism: JUnit timeouts interrupt the test thread]
- `forge.sh` uses `verify`, not `integration-test`: failsafe enforces failures only at `verify`, and
  `integration-test` prints `BUILD SUCCESS` over failing tests. Its `-pl aether/forge/forge-tests`
  scope is hard-coded and documented as non-negotiable — that scoping is what keeps `HetznerCloudIT`
  (which provisions a real paid server when `HCLOUD_TOKEN` is set) out of the reactor.
- The script clears `target/failsafe-reports` before running. Without that, its own summary read
  every previous run's XML: a 3-class smoke run reported 50 tests from 12 files, most left by an
  unrelated probe run. A gate that reports another run's results is the same defect as a positive
  control that ignores its own trigger — it does not miss problems, it reports confident nonsense.
- The pre-push expectation is now written where it is seen rather than remembered: `build.sh`'s
  closing banner leads with "NOTHING ABOVE RAN A CLUSTER", and `CONTRIBUTING.md` — which previously
  implied `build.sh` was sufficient before a PR — carries the requirement and the module list.

### Fixed (2026-08-27 — #509 probe: the positive control never posted a valid scale request)
- `PostRestartSlowRejoinDeficitFillProbeTest.postScale` sent `{"coreCount": N}`, a field
  `ManagementApiResponses.ScaleRequest` has never had. The server rejected it with HTTP 500
  (`Type mismatch: expected int, got unknown ... ["count"]`), so the configured core count was never
  raised, the reconciler correctly reported `NO_DEFICIT`, and the control asserted zero provisions.
  Now sends the documented `{"source":"","role":"core","count":N,"expectedVersion":V}` — the shape
  `ScaleRequestContractTest` pins and `ClusterScaleCommand` sends.
- **The control no longer swallows a failed trigger.** `postScale` rendered the response into a log
  line and discarded the status, so a rejected request produced a confident "the deficit-fill path or
  the recorder is INERT in this cluster" verdict — a positive control that ignores its own trigger
  failing does not just miss defects, it manufactures a diagnosis of the wrong subsystem. Any non-2xx
  now fails the scenario immediately with the status and body.
- Note for anyone running this module: `mvn -pl aether/forge/forge-tests integration-test` reports
  `BUILD SUCCESS` even with failing tests — failsafe enforces only at `verify`, which cannot be run
  while `HCLOUD_TOKEN` is set. The failsafe XML is the only trustworthy verdict.


- **`QuorumLossDetector` gains `stop()`** (terminal latch checked at every dispatch point + both
  futures cancelled), called from `AetherNode.stop()` beside the #590 core-absence stop. The defect:
  the detector had no stop, its timers live on the process-wide `SharedScheduler`, and
  `presenceSampler.stop()` freezes its member count below threshold — so in a shared-JVM host
  (forge/Ember) a stopped node's armed detector fired ~75s after the ORIGINAL boot and
  `EmberCluster.handleSelfDrain`'s id-keyed registry lookup stopped the node's NEXT incarnation.
  Third instance of the SharedScheduler-no-stop class (#499 backfill, #590 core-absence).
  Production exposure LOW (`halt(2)` kills ghosts with the process) [design intent — unverified];
  harness exposure HIGH (false-red generator). Live-gate rerun is BLOCKED on #660 (pre-existing
  Rabia sync deadlock, surfaced by this batch's gate run); #642 stays open until that gate fires.
- **SharedScheduler stop-hook audit** (45 sites): 8 node-scoped recurring tasks had no reachable
  cancel path on node stop and kept acting for stopped nodes — CDM reconcile timer (via the
  designed `deactivate()` path), governor announcer, retention enforcer (a DESTRUCTIVE sweep),
  spokesman ping loop, consumer runtime (ordering-bound: now closed INSIDE the #488 window, before
  the partition manager it reads through), replication batcher (via `StreamPartitionManager.close()`),
  API-key sweep, adaptive sampler. Each individually reviewed for over-cancellation; all verdicted sound.
- **Cold-boot convergence window is now anchored at `start()`**, not assembly: a node started >75s
  after creation previously booted with ZERO quorum-loss suppression (the window had already
  expired). The start()-re-stamp wiring is covered by the forge gate only; the predicate seam is
  unit-pinned.
- pg-parser: repo corpus walk consolidated into one shared `SqlCorpus` helper (`isRegularFile` at
  the mechanism — the #598 fix had covered one caller and its sibling broke the next full local
  build on directories named `*.sql`); `ZCstDumpTest` (a hand-run CST-diff instrument that asserts
  nothing) now runs only when `-Dcstdump.out` is passed, per its own documented invocation.

### Fixed (2026-08-27 — #575: `[operations.auto_heal] enabled` no longer parses into a silent no-op)
- **A bootstrap-config key that changed nothing now fails validation instead of shipping.**
  `[operations.auto_heal] enabled = false` (and its `[operations] auto_heal = false` shortcut —
  both parse to the same `AutoHealSpec.enabled`) parsed, validated, and diffed cleanly, then never
  reached the runtime: the code that actually gates provisioning reads a separately-hand-maintained
  `AutoHealConfig` (`environment-integration`) that has no `enabled` field at all. An operator who
  set this to stop replacement provisioning during an incident got silent no-op, not the suppression
  they asked for. `ClusterBootstrapConfigValidator` now rejects `enabled = false` outright (`PF-25`),
  mirroring `checkIngressProviderSupport` (`PF-23`, #574) — reject a declared knob loudly rather than
  parse it and do nothing. `enabled = true` is left alone: it matches the runtime's actual always-on
  behavior, so it does not assert anything false, even though it is equally inert. The `cluster init`
  advanced-config scaffold no longer emits the misleading `enabled = true` line either
  [verified: `ClusterBootstrapConfigValidatorTest.ClusterLevel.validate_autoHealDisabled_returnsPf25`].
  Recovery action for an operator who wants to disable auto-heal: use the real, already-wired
  runtime toggle instead — `aether cluster topology auto-heal disable` (#603) — a different
  mechanism (an imperative, per-leader-term switch) from this bootstrap-time declarative key.
- **Known, deferred gap (not fixed by this ticket):** the dead wiring is broader than the `enabled`
  field. `Main.resolveAutoHeal` only ever applies `#298`'s `max_nodes` cap onto `AutoHealConfig`;
  every other `[operations.auto_heal]` field (`retry_interval`, `startup_cooldown`,
  `stale_observation_ttl`, `quic_miss_promotion_threshold`, `provisioning_timeout`,
  `provision_stability_window`, `decommissioned_retention`, `swim_hints_ttl`) is parsed into
  `AutoHealSpec` and then discarded — the whole section falls through to `AutoHealConfig.DEFAULT`
  except for that one field. Collapsing the two duplicated types (`AutoHealSpec` vs `AutoHealConfig`,
  which also disagree on `decommissionedRetention`'s default: 24h vs 60s) requires touching
  `Main.java` / `AetherNodeConfig.java`, both outside this stream's territory — tracked as a
  follow-up structural ticket candidate, not addressed here. Docs (`reference/bootstrap-config.md`,
  `reference/timeout-configuration.md`) still describe these fields as operator-tunable and need a
  correction pass; also out of this stream's territory (`aether/docs/**`).

### Fixed (2026-08-27 — #576: dead stream/consumer TOML keys now rejected at blueprint validation)
- **A blueprint's `[streams.X]` and `[streams.X.consumers.Y]` config diffed cleanly against what the
  operator asked for, then most of it never reached the runtime.** `StreamResourceValidator` now
  rejects the keys that are structurally inert instead of accepting them:
  - `encryption-key-id` — every stream is written through one shared segment sink with no encryptor
    ever wired to it; the 5-arg encryptor-accepting overload has no production caller. Real fix is
    tracked as `#253` (`BlockEncryptor` has no production key source) — a different stream's
    territory, not addressed here.
  - `compression` (`lz4`/`zstd`) — the same shared sink hardcodes `CompressionCodec.NONE`; segments
    are always written uncompressed regardless of this key.
  - `batch-size`, `processing`, `on-failure`, `checkpoint-interval`, `max-retries`, `dead-letter`,
    `read-preference` under `[streams.X.consumers.Y]` — `StreamConfigParser#parseConsumers` (the
    parser that reads these) has no production caller anywhere in the repo; every declarative
    consumer runs through `StreamConsumerManager`'s 1-arg `ConsumerConfig` construction, which is
    permanently pinned at defaults.
  Only **non-default** values trip the new `inert-stream-config-key` / `inert-consumer-config-key`
  rules — a key that happens to already equal the hardcoded default does not assert anything false,
  even though it is equally inert, so it is left alone
  [verified: `StreamResourceValidatorTest.InertConfigRejection`, 7 new cases covering all affected
  keys plus an explicit-defaults positive control].
  Recovery action for an operator hitting one of these rejections: remove the key (it was never
  doing anything) or wait for the runtime wiring fix to land before reintroducing it.
- **`auto-offset-reset`'s parsed *default* was itself dishonest, separately from the explicit-value
  case above.** The parser defaulted an omitted key to `"latest"`, but a never-committed consumer has
  always started at offset 0 (earliest) per the settled `#478` ruling, permanently — not a gap to be
  closed later. Blanket-rejecting the field's near-universal default would have been disruptive to
  every existing blueprint for no operator benefit, so the parser default is corrected to `"earliest"`
  instead (zero runtime behavior change — the field has zero behavioral readers; only
  `KVStoreSerializer` round-trips the string)
  [verified: `StreamConfigParserTest.ResourcesParsing.autoOffsetResetDefaultsToEarliest`].
  An explicit non-`"earliest"` value is now unambiguous and rejected under `inert-stream-config-key`
  alongside `encryption-key-id`/`compression`.
- **Known, deferred gap (not fixed by this ticket):** rejecting the dead keys is a validation-time
  fix, not the structural one. A full wiring fix — making these keys actually take effect — spans
  `aether/slice-api` (`ConsumerConfig`/`StreamConfig` plumbing), `aether/aether-stream`
  (`StorageSegmentSink`'s single shared, unencrypted sink), and most of `aether/node`
  (`AetherNode`/`StreamConsumerManager`'s hardcoded construction sites) — all outside this stream's
  territory, tracked as a follow-up structural ticket candidate. A second, independent TOML parsing
  path for the same `[streams.X]` shape exists at `NodeDeploymentState.java` (`aether-deployment`,
  via a generic `ConfigService.config(section, StreamConfig.class)` binder) — in-territory but out of
  this fix's scope, and likely part of the real root cause (two divergent parsers for one config
  shape) rather than something this validation-time guard addresses.

### Fixed (2026-08-27 — #603: `auto-heal disable` now actually gates provisioning)
- **The operator kill switch had exactly one reader.** `aether cluster topology auto-heal disable`
  flipped `ClusterTopologyManager.isAutoHealEnabled()` and the status route reported it disabled —
  but `LeaderReconciler.provisioningAllowed` never read the flag, so a real member departure was
  auto-healed regardless. `autoHealEnabled` is now read once per reconcile pass and threaded through
  the gate, the decision log, and the `#336` snapshot, so all three agree even if the operator
  flips the flag mid-pass. Checked first in the four-condition gate, ahead of the formation
  latches, as the operator's explicit override. **Suppresses provisioning for the current leader
  term only** — the flag is an in-memory field on the current leader, not a committed value, so it
  does not survive a leader failover [mechanism: field lives on `LeaderReconciler`, no persistence
  or replication path]. Recovery action for an operator who needs the suppression to hold across a
  failover: none yet — re-disable on the new leader after failover, or track the gap as its own
  ticket. `LeaderReconcilerTest` pins the evaluation order directly: with both auto-heal disabled
  and the debounce window still open, `suppressionReason()` reports `AUTO_HEAL_DISABLED`, not
  `WITHIN_DEBOUNCE` — asserted before the debounce window is advanced, the only point where the two
  reasons actually diverge [verified: `LeaderReconcilerTest.autoHealDisabled_suppressesProvisioning_evenPastDebounceWithGenuineDeparture`]

### Fixed (2026-08-27 — #571 partial: dead `completeDrain` emit removed; stale `DHTNotification` deleted)
- **`ClusterDeploymentState.completeDrain` no longer emits to a permanently-noop sink.** It called
  `ctx.healthSignalSink().emit(new HealthSignal.DrainCompleted(...))`, but `HealthSignalSink`'s only
  wiring point (`AetherNode.healthSinkRef`) has been bound to `HealthSignalSink.noop()` unconditionally
  since the membership-v2 migration deleted the two intended consumers, `HealthReconciler` and
  `LifecycleWriter` — the log message this method printed ("...emitting DrainCompleted signal to
  LifecycleWriter") named a class that no longer exists in the codebase. Removed the emit and
  corrected the log line; this is a self-contained no-op removal (the call went nowhere before and
  after) [mechanism: `HealthSignalSink.noop()` has no side effect by construction]. This is the one
  producer call site the ticket's own line citations correctly identified; the sink's five other
  producer sites live in `AetherNode.java`/`ManageableNode.java` (stream-A territory), `aether-metrics`,
  and `integrations/consensus`/`integrations/swim` (stale doc comments only) — tracked separately,
  not fixed by this entry. See `MAILBOX.md` for the cross-stream coordination note.
- **`ClusterDeploymentManagerTest$DrainCompletionTests.completeDrain_emitsDrainCompletedSignal` rewritten
  as `completeDrain_writesNoKvCommand`.** The old test pinned the now-removed `HealthSignal.DrainCompleted`
  emit. Before deleting it outright, a dedicated investigation confirmed the property it stood in for —
  "drain completion is observable via the leader's spec §8 path" — is genuinely dead, not a coverage gap:
  the new membership-v2 drain procedure (`DrainProcedure.java`, `membership.ntt`) runs on the *victim*
  node with "No KV / consensus dependency" (its own doc, lines 54-55), while `completeDrain` runs on the
  *leader* — a structural mismatch meaning the old in-JVM signal could never have carried this information
  even before its consumers were deleted; slice migration is explicitly out of that spec's scope. The
  rewritten test instead pins the surviving property: `completeDrain` issues no KV command (it never did,
  and now visibly doesn't route through the dead sink either) [verified:
  `ClusterDeploymentManagerTest$DrainCompletionTests.completeDrain_writesNoKvCommand`].
- **Deleted `DHTNotification.java`** (`aether/aether-invoke`) — a `@Codec`-annotated sealed protocol
  message (`Put`/`Removed`) with zero senders and zero receivers repo-wide, confirmed independently by
  two separate greps of `.java`/`.md`/`.toml`/`.yml` sources: its only references were its own
  declaration, two tag pins in `SystemTags.java` (`640`/`641`, now retired — never renumbered or reused
  per that file's own rule), and this changelog's now-stale claim below. Not a placeholder: no
  TODO/planned marker in the file or its git history. `StreamType.DHT` is unaffected — it has live
  readers elsewhere (`DHTRelayMessage`, `integrations/dht/DHTMessage`) — and `ProtocolMessage` is not
  sealed, so no switch loses exhaustiveness from this deletion.
- **Correction to a prior entry:** the "DHT notification broadcasting" bullet under `### Changed`
  earlier in this file (originally dated before the 1.0.0-rc3 unreleased section existed) describes
  `DHTNotification` as an active, in-use broadcast mechanism. That was true when written; it is not
  true today — the mechanism above supersedes it. Left the original entry unedited (historical record)
  rather than rewritten.

### Fixed (2026-08-27 — #578: cluster-config apply no longer silently no-ops or partially mutates)
- **A rejected apply plan is now guaranteed zero side effects.** `ClusterConfigApplier.apply`
  previously actuated actions as it walked them — a diff mixing a valid scale with an unsupported
  action wrote the live desired-count change and only then failed, leaving the cluster mutated
  while the stored config still held the old value. The applier now classifies the WHOLE plan
  first (one exhaustive switch, no `default`, so an eleventh `DiffAction` variant fails to compile
  rather than silently falling through) and rejects up front on the first failing action before
  anything is actuated [mechanism: `firstRejection`/`scalesOf` split the classified list before any
  `topologyManager.setDesiredCount` call runs]. An accepted plan's scale writes still fold
  sequentially, matching an operator's top-to-bottom reading of the plan; a write that fails
  partway through the fold leaves the earlier writes landed with no compensation — documented as a
  known boundary, not built, since no caller has yet produced a plan where that ordering matters
  [design intent — unverified].
- **`ManagementServer`'s no-topology-manager fallback now fails loudly instead of silently
  succeeding.** The fallback (currently dead code — no live route wires a node with an absent
  `ClusterTopologyManager`) previously accepted any apply as a no-op. Renamed
  `ClusterConfigApplier.unused()` to the enum-singleton `NoTopologyManager`, returning a new typed
  `ClusterConfigError.ClusterTopologyManagerUnavailable` (503, operator recovery action: retry
  against a node with cluster topology management active) so the same silent-success defect this
  ticket closes on the live path cannot resurface here if the fallback ever becomes reachable
  [verified: `ClusterConfigApplierTest.NoTopologyManagerFallback`].
- **Known, deferred inconsistency (not fixed by this batch):** an `ImmutableFieldChange` action
  answers 409 CONFLICT from this applier but 400 BAD_REQUEST from the live HTTP route
  (`ClusterConfigRoutes.executeDiff` intercepts `hasImmutableChanges()` before the applier runs, via
  `ClusterConfigError.ValidationFailed`, which does not override the interface's default
  `httpStatus()`). The applier's 409 is unreachable from the live route today. Fixing it means
  changing `ValidationFailed`'s status propagation, which affects every other validator wrapping
  it — tracked here, out of scope for #578.
- Fixed a `.field()` bug at two pre-existing call sites (`ClusterConfigRoutes.java`,
  `ApplyOrchestrator.java`) where an `ImmutableFieldChange` rejection's field name was read lazily
  only inside the branch that has one, found during review of this batch.

### Added (2026-08-27 — #642/#509 test infra)
- `EmberCluster.start(heldBackNodeIds)` + `startHeldBackNodes()`: deterministic slow-rejoiner seam —
  held nodes are created into every peer's configured topology but not started, producing the #509
  "stable-id members merely slow to rejoin" shape without racing a real restart.
- `PostRestartSlowRejoinDeficitFillProbeTest` (Heavy): full-restart-with-2-of-5-held probe with a
  recording ComputeProvider, zero-provision assertion through a derived hold window, scale-up
  positive control (via `POST /api/cluster/scale` — `setClusterSize()` alone is a vacuous control),
  and fail-fast on any started node dying. Run 1 found #642; run 2 (ghosts fixed) surfaced #660.
  Goes green only after #660's fix; #509 closes on that green per its on-ticket ruling.

### Added
- Core: **typed-error construction** (`core/docs/typed-error-construction.md`) — the API half. `Causes.forOneValue/forTwoValues/forThreeValues` gain typed rungs: a message-only rung (`Fn1<C, String>` causeFactory) and a data-retaining rung whose causeFactory receives the values plus the formatted message in constructor order, so a data-carrying cause record's canonical constructor reference IS the factory (`record InvalidEmail(String raw, String message)` + `forOneValue("Invalid email: %s", InvalidEmail::new)`). Three is the ceiling by decision — zero corpus call sites exist at arity two and three. All rungs (existing single-arg ones included) now pin `Locale.ROOT` so numeric conversions render identically across JVMs. Two defaults-only mixins land nested in `Cause`: `Cause.Terminal` (isTerminal → true, the implementing IS the classification) and `Cause.Wrapped` (an `origin` component supplies `source()`; the component cannot be named `source` — the record accessor's return type would clash — and `Option.option` is deliberate, since `Option.some(null)` would wrap a null without complaint). One rendering fact worth knowing, pinned by test: `%s` renders a `Cause` argument through `toString()`, not `message()` — interfaces cannot default `toString()` — so a wrap template that wants the origin's message embedded formats `origin.message()` in a hand-rolled factory line
- Core: **full-PECS variance on every cause-factory parameter** — `Result.filter`/`mapError`, `Promise.filter` (both overloads)/`mapError`/`failAsync`, and all six `Verify.ensure`/`ensureOption` causeProviders: producer position takes `? extends Cause`, value inputs take `? super T`. A fully-typed factory field now drops into every composition site with no widening and no `::apply` adaptation, and a factory generalised over a supertype serves narrower sites. Binary-compatible (erasure unchanged); source-compatible for callers, verified by a reactor-wide compile; with `Promise` sealed (#635) no external implementor can exist, so the claim holds unconditionally. Pre-GA is the window where this widening is free

### Changed
- DX: **the Forge debug workflow is documented, and every `run-forge.sh` honors `FORGE_JVM_OPTS`** (#608, the issue's own "cheapest half first"). Forge is one plain JVM, so standard JDWP remote attach has always worked — nobody was told. `forge-guide.md` gains a Debugging section with the exact flags (attach-when-ready and suspend-on-startup variants), the IDE attach recipe, and the two facts worth knowing before blaming the runtime: breakpoints in slice code bind normally (JDWP is classloader-agnostic — keep the slice source project open for frame mapping), and a hit breakpoint freezes all five simulated nodes with it, so expect SWIM suspicion noise after long pauses. All six example `run-forge.sh` scripts pass `FORGE_JVM_OPTS` through (deliberately unquoted — several flags must word-split). The issue's other half — watch mode without the mvn-install round trip — is a real feature and stays open on #608
- Docs: **the two boundaries named precisely** (#614). Everywhere slice isolation is *described* (overview, feature-catalog row 5, slice-container, slice-loading, resource-and-isolation model), the claim now carries its scope: classloaders isolate **dependency versions**, the **cluster** isolates failures — co-located slices share one JVM, the fault boundary is the node. **Slice-to-node pinning is documented as explicitly not supported** (a `known-limitations.md` scope row + section): the blueprint has no placement key and `PlacementPolicy`'s four tiers are the whole slice-placement vocabulary, so sole occupancy is achieved by tier construction, not per-node constraint — and `15-resource-and-isolation-model.md`'s claim that placement hints express slice co-location is corrected (they are provisioning-time node-shape controls in `ProvisionSpec`, catalog row 187). The overview's Rabia section now reconciles leaderless consensus with the coordination leader instead of leaving the contradiction to the reader: the leader lease is a value IN the ordered store, so re-election is a write rather than a protocol — which is where the ~2ms leader-replacement figure comes from, and what Raft cannot do by construction
- Consensus: **QUIC transport errors migrated to the typed-error construction idiom** — the pilot migration the lint spec's rollout plan calls for (`jbct/docs/typed-error-lint-spec.md` §5.3). `QuicTransportError`'s eight data-carrying records gain a trailing `message` component and a declared `FACTORY`; the four variants wrapping an underlying failure (`ConnectionCloseFailed`, `BindFailed`, `ConnectFailed`, `StreamCreationFailed`) now implement `Cause.Wrapped` with a `Cause origin` component, so the wrapped failure survives into `source()` instead of being flattened into message text (rendering is unchanged — both the old string concatenation and the new `%s` template go through the same `Causes.fromThrowable(...)` value's `toString()`). `General`'s field renamed `text` → `message` to the prescribed fixed-text shape — caught by the pack, not by eye. `IdentityMismatch` hand-rolls its factory: its message renders `NodeId.id()`, not `NodeId.toString()`, and a `%s` template cannot express that — custom value rendering is a second legitimate reason to hand-roll alongside the spec's above-the-ceiling case, worth its sentence in the companion spec. `TlsContextCreationFailed` deleted — zero references repo-wide (pre-GA, no compat hold). All 11 construction sites across the four QUIC files now construct through the factories. Calibration evidence per §5.3: pre-migration the pack reports 10 findings on the file (1×CAUSE-01, 9×CAUSE-02); a deliberately broken intermediate fires the two track-B rules (CAUSE-04 on a template/arity mismatch, CAUSE-08 on a stray `new` bypassing a declared factory); the final state is pack-silent — 0 CAUSE findings across all 15 QUIC files. integrations/consensus: 709 tests green, including the reconnect test that pins `IdentityMismatch`'s components

### Changed
- Core: **`Promise` is now sealed** (`permits PromiseImpl`), matching `Result`'s `permits Success, Failure`. Verified before sealing: exactly one implementor exists (`PromiseImpl`, same file), no test doubles implement `Promise`, and no anonymous `new Promise<>()` anywhere in the repo — so nothing breaks. The driver is the typed-error-construction work (`core/docs/typed-error-construction.md`): its variance pass changes the generic signatures of `filter`/`mapError`/`failAsync`, which is source-compatible for callers but would break any external implementor overriding those defaults; with the interface sealed, "source-compatible" holds unconditionally. Pre-GA is the window where sealing costs nothing — an installed base could later make it a breaking change

### Verified (2026-08-26 — #596 CLOSED: the live entity gate, on the product's own routing)
- **02w-entity-crash rerun with the per-node endpoint rotation REMOVED** (the ticket's own
  instruction: the harness's owner-finding sweep masked the product's missing forwarding, so the
  acceptance run had to drop it). `entity_post_any` now treats the FIRST REACHABLE endpoint's
  answer as authoritative — transport failure moves on (the suite kills nodes; a dead port is the
  harness's problem), a wrong answer from a live node FAILS. Result on a 5-node remote cluster:
  **40/40 pre-kill creates acked via product forwarding** (the pre-#596 pinned-endpoint shape
  scored 4/40), every acked value read back exactly through one endpoint (the read-forward's live
  exercise), **37/40 acked ACROSS a SIGKILL** (the 3 are honest failover-gap refusals — a forward
  aimed at the dying owner fails typed), **77/77 acked survived exact-valued** (0 missing, 0
  corrupted, 0 unreachable), terminal convergence instant, suite 54s, and **zero `NotCurrentOwner`
  anywhere in the run** — the refusal the ticket was filed about never fired. guarantees.md entity
  tags upgraded from design-intent to `[verified]` accordingly.

### Added (2026-08-26 — #596 read half: BOUNDED_STALE entity reads forward from non-hosting nodes)
- **A `BOUNDED_STALE` entity read on a node with no local log now forwards to the committed owner
  instead of refusing.** The write half (command-shaped mutations + owner-forwarding) landed
  earlier; the read half completes the surface. The decision is REPLICA-AWARE, not owner-aware: a
  node that HOLDS the partition — owner or replica — serves locally (the fold's ready/caught-up
  gates bound staleness in offsets, which is the consistency level's whole contract); only a node
  with NO ring forwards, using the ticket's own primitive (`holdsPartition` = ring presence, never
  a replica descriptor). Unwired transport or uncommitted ownership keeps the typed local refusal
  (`PartitionNotHeld`) — never an invented hop. Validate-the-ticket note: the "returns EMPTY, reads
  as ABSENT" defect had already been upgraded to that loud refusal by the hosting-set arc; this
  change turns the refusal into a route. Wire: `EntityGetForward`/`EntityGetForwardResponse`
  (SystemTags 1666/1667) with the mutation trio's budget discipline (arrived-expired reads are
  refused, not served to nobody) and an EXPLICIT `present` flag — absence is never inferred from
  state-byte length, because a zero-length-encoding edge silently reading as ABSENT is the
  ticket's original defect. The service's correlation protocol is genericized over the answer type
  (one implementation, two pending maps) so the read and write halves cannot drift.
  Decision matrix + protocol `[verified: EntityOwnerForwardTest 28/28 incl. 7 bounded-stale/read
  pins; EntityForwardServiceTest get protocol 5 new pins incl. budget refusal]`; live multi-node
  LB-fronted path `[design intent — unverified]` until the cloud entity gate (the ticket's
  acceptance bar) — #596 stays open for that.
- **Review-hardened (2 MAJOR, both real).** (1) Holding is re-checked at SERVE time, not only at
  routing time: the fold memoizes rebuild success forever, and a ring released AFTER the rebuild
  leaves a frozen fold whose catch-up gate is vacuous (an empty ring reports headOffset −1) — a
  read served from it, locally or via a forwarded hop during ownership-reconcile lag, had NO
  staleness bound at all. `ready()` now refuses a non-held partition typed, armed by a
  non-holding-receiver test that also pins loop safety (the receiver never re-enters the forwarding
  decision). (2) Decoding a forwarded answer was a bare `map(this::decode)` — a codec-miss throw on
  the response-dispatch thread left the caller's promise UNRESOLVED, a hang instead of a typed
  failure; all three forward-decode sites (including the two pre-existing write-path ones) now go
  through the lifted decode. Plus: the unwired-refusal test pins the CAUSE TEXT rather than bare
  isFailure; a dedicated pin proves getForwarded serves WITHOUT write admission (load-bearing per
  the ForwardTarget contract); the read-your-writes caveat of a replica-served BOUNDED_STALE read
  is stated in guarantees.md.

### Fixed (#606 — examples teach what actually runs)
- **Banking persists for real** (owner decision): `AccountService` no longer injects a `SqlConnector` it never touches while storing state in ConcurrentHashMaps — it delegates to a new `@PgSql AccountPersistence` whose every statement is compile-time-validated against `schema/V001__create_tables.sql`. Credit/debit are single conditional `UPDATE … WHERE … RETURNING` statements, so insufficient-funds is decided by the row match, atomically, not by a read-then-write; rejected updates map to the existing typed errors (`NotFound`/`InsufficientFunds`/`CurrencyMismatch`). One honest boundary recorded at the declaration instead of hidden: the processor rejects data-modifying CTEs and exposes no transaction surface, so `openAccount` is two statements and a crash between them orphans an account row
- **Transfer compensation no longer discards its own Promise.** `compensateDebit` fired `accounts.credit(...)` and recorded `COMPENSATED` unconditionally — compensation failure was swallowed and the status lied. Now the whole failure path composes through `fold` (Promise's error-path primitive — `onFailure` is an unawaited side effect and cannot gate a chain; `orElse` would drop the original cause and turn a successful compensation into a successful transfer): the original failure still propagates after the compensating credit completes, `COMPENSATED` is recorded only on success, and a new `TransferStatus.COMPENSATION_FAILED` carries both causes in `TransferSummary.failureDetail`. Mutation-proven both ways: recording COMPENSATED unconditionally turns 2 tests red; restoring the original fire-and-forget turns 3 red
- **The same bug found and fixed in ecommerce** while meeting the issue's "no example discards a Promise/Result" acceptance: `PlaceOrder`'s `releaseStockOnFailure` dropped the release Promise identically; now composed via `fold` with `OrderError.StockReleaseFailed(paymentFailure, releaseFailure)` naming both causes — mutation-checked (restoring the drop turns 2 tests red)
- **Every slice example now opens with a teach-header** stating what it demonstrates and what it deliberately does not. Examples reactor: 25 → 60 tests (14 account, 15 transfer, 6 place-order compensation among the new), `jbct:check` clean

### Fixed (#613 — a slice can bundle its own `javax.*` third-party artifacts)
- **`SliceClassLoader` no longer forces the whole `javax.` namespace parent-first.** `javax.inject`,
  `javax.servlet`, `javax.annotation` are ordinary third-party artifacts, and the blanket prefix
  meant a slice could not bundle its own copy — before the fix, a bundled `javax.inject` ended in
  `ClassNotFoundException`, which is precisely the per-slice version independence the child-first
  loader exists to provide. The issue's suggested explicit package list was deliberately not used:
  it has sharp edges (`javax.annotation` is third-party while `javax.annotation.processing` is JDK;
  `javax.transaction` vs `javax.transaction.xa`) and drifts across JDK releases. Instead the
  predicate is the definition itself — a `javax.*` class is parent-first iff the **platform
  classloader resolves it** — so JDK-shipped namespaces (`javax.xml`, `javax.crypto`, `javax.net`,
  `javax.sql`, `javax.management`, ...) keep exactly their old routing and a slice-bundled shadow
  of them is still ignored, closing the classic xml-apis split-namespace `ClassCastException`
  before it can open. `java.` / `jdk.` / `sun.` are untouched. Three probe-class tests pin it
  (compiled at test time, the shadows minted via `--patch-module` since vanilla javac refuses the
  split package): a bundled `javax.inject.Named` resolves to the slice loader; a bundled
  `javax.xml.parsers.DocumentBuilderFactory` shadow is ignored in favor of the platform class; a
  bundled `java.lang.String` shadow is ignored in favor of bootstrap `String` — that last pin
  shadows an EXISTING class deliberately, because a novel `java.lang` probe dies in the JDK's own
  `defineClass` defense on correct code and mutant alike (`super.loadClass` is
  parent-first-then-self), distinguishing nothing; measured, not assumed

### Fixed (#649, #646 — pg validation resolves names by statement structure; upserts stop hard-failing and silent skips become real checks)
- **#649, the build-blocker:** `INSERT … ON CONFLICT DO UPDATE` hard-failed validation ("Column 'excluded' not found", "Column 'reservations' not found in table 'reservations'") on the ticketing corpus's four monotonic version guards, with no semantics-preserving workaround. The traced mechanism was double mis-routing, not missing-feature-meets-new-code: `validateRoot`'s keyword-presence fallback (UpdateKW+SetKW anywhere, no UpdateStmt — true of every DO UPDATE) ran `validateUpdate` over the whole INSERT, and there `findAll("ColId").getFirst()` picked the wrong node because peglib 0.7.3 lexes `version` as `Token VersionKW` — the recorded "never dispatch on keyword kinds" hazard, in the validator's extractor. Pre-0.7.3 the same wrong path passed by accident (the SET target was still a ColId), which is exactly why the corpus was green until the Aug-24 toolchain. Fix: the fallback is gone; statement dispatch is structural; the DO UPDATE clause gets a real scope — target relation self-referencable by name, alias honored, and `EXCLUDED` registered as a pseudo-relation carrying the target's columns (scoped, not whitelisted: `EXCLUDED.nonexistent_col` errors); SET targets and column lists are read positionally (`CstExtractor.extractColumnList` no longer keys on the `ColId` rule name)
- **#646:** `RETURNING` lists and UPDATE/DELETE `WHERE`/`USING`/`FROM` column refs now validate against the statement's target scope. Previously `selectOutputColumnNames` did `findAll("SelectCore")` over the whole statement demanding exactly one — so a subquery-free `UPDATE … RETURNING` was silently skipped (the "working" cases were never validated), one WHERE-subquery made RETURNING validate against the subquery's projection (the original three spurious warnings), and two subqueries skipped again. Ticketing's canary is now a test: a bogus RETURNING column on the expireHolds shape errors where it was silent. Subqueries still validate their own scopes
- **Latent defects the newly-real validation surfaced, all fixed in the same pass:** `WITH … AS (…) UPDATE` reported "Table not found: id"; schema-qualified `public.reservations` reported "Table or alias not found: public"; and the test-persistence blueprint's `INSERT INTO kv_store (key, value)` was validated on neither column (its own upsert reproduced the #649 error verbatim). One pre-existing defect found and deliberately NOT bundled: correlated subqueries cannot see the outer scope — filed as #651
- Evidence: pg-tools 832 → **858 tests green** (+26; QueryValidatorTest now 50 across 8 nested classes), independently re-run; three mutation checks each turn their tests red (drop EXCLUDED registration → 4, stub validateReturning → 2, revert to whole-tree SelectCore discovery → 4); the four #649 upsert shapes and the #646 A/B pair are in the parser corpus so the next parser bump cannot silently regress them

### Fixed (2026-08-26 — #628: a failed 02-chaos baseline restore is no longer structurally invisible)
- **Intra-suite restore gate + honest transport sensors (owner scope call: full package).** All
  seven 02-chaos test files downgraded a failed `restore_cluster_baseline` to a warning, and
  `run_suite` had no gate between test FILES — the only restore gate lived between SUITES — so a
  broken cluster failed every downstream scenario on its own subject: the 2026-08-21
  `rawReady=1 EXCL-caller` shape and the 2026-08-22 `got 0 ()` shape are both downstream of one
  warned-away restore. Now: the cleanups call `restore_cluster_baseline_or_flag` (FAIL log + a
  marker to the suite runner; still exit-trap-safe), and `run_suite` on the marker captures node
  logs IMMEDIATELY (the 2026-08-22 evidence window was destroyed before the once-per-suite capture
  ran — the new capture lands in `failure-logs/<suite>-restore-failed/`) and quarantines the
  remaining test files with the same semantics the between-suites gate applies. Sensors:
  `running_core_containers` was rc-unchecked over `remote_exec`, so an SSH/daemon failure filtered
  to "0 running containers" — verbatim the reported `got 0 ()`; it is now bounded
  (`remote_exec_bounded`: the REMOTE `timeout` bounds a hung `docker ps`, which SSH ConnectTimeout
  cannot — it guards setup only) and reports `UNREACHABLE(rc=N)` with rc=1, and the Pick_3
  precondition distinguishes transport-failure from genuinely-empty (one semantic restore attempt
  before failing, for standalone runs — parallel to the run_suite gate). `wait_for` exports
  `WAIT_FOR_REMAINING` so transport-touching predicates can bound themselves (its deadline is only
  checked between iterations — one hung predicate overran 4596s against a 480s budget; with the
  seven full-budget failed restores this accounts for most of the 3× duration blow-up, alongside
  the pre-`5ef0f822c` missing connect cap). `[mechanism-armed: stubbed failing restore writes the
  marker and returns 0, standalone path warns and returns 0; harness lint 49/49 baseline, contract
  tests green]` `[design intent — unverified on the live path until the next remote 02-chaos run:
  the gate branch only fires when a real restore fails]`

### Fixed (2026-08-26 — #598: parallel cluster-A suites no longer race for the cluster-global `database` datasource)
- **test-persistence gets its own datasource name — and its own physical database.** Cluster-A
  suites run in parallel, and both url-shortener (suite 06) and test-persistence (suites 06/08/10)
  declared migrations under the default datasource name `database`; the #566 single-migrator gate
  409'd whichever published second, and the loser's tests failed four steps later with empty
  deployment IDs (owner direction 1, chosen 2026-08-26; direction 3 — abort on refused publish —
  was already in-tree since `9b88911cd`, pre-dating the ticket's evidence run). The blueprint now
  declares `database.testpersistence` via a blueprint-private `@TestPersistenceDb` qualifier
  (pg-codegen honors custom `@ResourceQualifier(type = PgSqlConnector.class)` annotations by
  design), migrations move to `schema/testpersistence/`, and the resources section points at a
  SEPARATE physical database (`forge_testpersistence`) — required, not cosmetic: the schema
  history/owner tables are fixed-name-per-physical-database, so a shared physical DB would re-create
  the same collision one layer down (exactly the case `aether_schema_owner` exists to refuse).
  Environments: compose-A mounts `pg-init/` (fresh `pgdata` every deploy — `deploy_docker` drops the
  volume — so init always runs; a `--skip-deploy` run against a pre-change cluster needs one
  redeploy), the remote branch ships the init dir alongside the compose file, and the cloud A-TOMLs
  gain a `[database.testpersistence]` node_config section (the connector resolves config by EXACT
  section name — the flat `[database]` override does not reach named sections) with
  `ensure_cloud_pg_database` creating the PG-VM database idempotently after the firewall opens.
  Discovery hardening in the same change: all four schema-suite scripts discovered "the tracked
  datasource" as `head -1` of the CLUSTER-GLOBAL status list — with two tracked datasources that
  grabs whichever blueprint published first, so suite 10's retry/baseline operations could target
  url-shortener's datasource mid-suite-06; all four now select the `testpersistence` row.
  `[verified: remote concurrent 06+10 run 2026-08-26 — 06-deployment 5/5 (blue-green start/promote/
  complete/rollback, canary, rolling — the ticket's exact failing assertions), 10-database 3/3,
  ZERO 409/already-migrated in the full log, every discovery resolved database.testpersistence,
  forge_testpersistence confirmed present on the remote PG by direct inspection; blueprint jar
  content-verified (database.testpersistence + schema/testpersistence/)]`
- **`--suites` entries that match nothing now abort the run.** Found by the proof run itself:
  `--suites 6,10` silently ran ONLY suite 10 and exited 0 (suites select by zero-padded prefix),
  so the half-coverage read as a full green run — the silent-truncation shape. A selector typo is
  a broken run, not a smaller one: `validate_selected_suites` fails loudly, naming the unmatched
  entries and the available prefixes. `[verified: armed both ways against the real suites dir —
  `6,10` rejected naming `6`, `06,10` accepted]`
- **Post-push CI catch: the pg-init file is a `.sh`, not a `.sql`.** `CorpusParseTest` feeds EVERY
  `.sql` in the repo through the MIGRATION grammar, and `CREATE DATABASE` / `GRANT ... ON DATABASE`
  are admin DDL outside its domain — the corpus gate red-flagged the init file on `30a91eb85`, so
  it now uses the standard docker-entrypoint shell form (`psql -c`), keeping the corpus premise
  intact repo-wide. Same catch surfaced latent format debt: the test-persistence module sits
  OUTSIDE the root reactor, so no gate ever ran jbct on it — 5 files (2 from this change, 3
  pre-existing) formatted, module check now clean. And a latent corpus-walk defect: `sqlFiles`
  matched `.sql` by NAME-SUFFIX alone, so the DIRECTORIES named `java.sql` in local JRE dist
  output turned the walk into an IOException on any machine with a prior dist build —
  `Files::isRegularFile` filter added, armed against the live dist output. `[verified:
  CorpusParseTest green locally WITH aether/dist/output present; jbct:check clean on the module]`

### Fixed (2026-08-25 — the boot codec guard's first real catch: the cluster forward-apply pair had NO codec)
- **`ForwardApplyRequest`/`ForwardApplyResponse` were routed wire types with no registered codec.**
  CI forge-tests went red on the first node boot after the #634 boot guard landed (`6c5ed495e`) —
  the guard refused assembly naming exactly these two types. A true positive, not an over-strict
  guard: `ForwardingClusterNode` really sends them (`network.send`, command forwarding to a core
  node for consensus application), their `ProtocolMessage` siblings (the Rabia family) all carry
  `@Codec`, and this pair simply never got the annotation — every forwarded command would have
  silently vanished at the transport, the exact #492 class the guard exists to catch. Fix, in two
  halves each demanded by its own guard: `@Codec` on both records (the cluster module already runs
  the codec processor; generated `ForwardCodecs` verified by content) + aggregation into
  `NodeCodecs`; then hand-assigned system tags 1664/1665 in `SystemTags` — the tag-space discipline
  rejected the hash-fallback tags by name ("System types with no hand-assigned tag"), exactly as
  designed after the codec-tag-collision hazard. Detection-gap note: the
  branch was boot-refused from `6c5ed495e` until this fix and no local gate saw it — `./build.sh`
  only BUILDS forge tests and the module test gates don't run them; forge CI was the first thing
  to actually boot an assembly. `[verified: ClusterFormationTest (forge) — previously ERRORED at
  assembly in 0.05s, now forms the cluster]`

### Added/Fixed (2026-08-25 — #634-7 remainder: WAL fsync-failure injection + crash-mid-compaction, closing the ticket's test-gap list)
- **The WAL fail-stops on a failed fsync (found by writing the owed injection test).** Before this,
  a failed group-commit `force` failed only the covered appends — the next append RETRIED the fsync
  itself. After one fsync failure the OS may drop the covered dirty pages while clearing the error,
  so the retried force can falsely report durability; acking on it leaves a silent mid-file hole,
  and recovery's contiguous scan then discards every ACKED record past the hole. Same defect class
  the ticket's item 1 closed at the manager layer ("a later success after a mid-chain failure would
  leave a hole"), one layer down. `PartitionWal` now records the failure once (loud ERROR) and
  refuses every later append with typed `WalError.FailStopped` — no write, no force — while reads
  still serve; reopen (node restart) is the recovery action and trims to the valid prefix, losing
  nothing acked. A compaction I/O failure deliberately does NOT fail-stop: reclamation failure is
  not a durability failure. Also fixed: a close-time fsync failure no longer leaks the channel (the
  old `force→flatMap→close` chain skipped `close()` on force failure). `[verified: PartitionWalTest
  FsyncFailure 5/5 — no ack over a failed fsync, force attempted exactly ONCE across a pipelined
  group, refusal with the channel deliberately RESTORED so a retry would have succeeded, reopen
  recovers acked records, compaction failure loud with the live file intact]`
- **Crash-mid-compaction pinned across every window of the temp+rename dance.** Each test
  constructs the exact post-crash disk state a SIGKILL would leave (a live JVM can't be killed
  inside `truncate`): a complete VALID decoy temp is ignored by recovery; a torn temp is ignored; a
  stale temp from a crashed run is overwritten and consumed by the next compaction; and survivors
  are durable immediately after the rename WITHOUT the instance's `close()` — proven by an
  independent reader on the same file — because the temp is `force(true)`'d before the rename. The
  rename now passes `ATOMIC_MOVE` (enforcing what the doc already claimed), and the class doc
  records why no directory fsync is issued, honestly tagged `[mechanism: the superset argument
  covers the undone-rename case; the no-neither-file case rests on ordered metadata journaling, not
  POSIX]`. `[verified: PartitionWalTest CrashMidCompaction 4/4]` `guarantees.md` §4 `stream.append`
  now states the fail-stop failure mode with the operator recovery action.
- **Review-hardened (2 MAJOR + 1 MAJOR surface, 4 MINOR, 1 NIT — all fixed).** (1) Compaction could
  UN-FREEZE the fail-stop: `installCompacted` republishes `syncedSeq = writtenSeq`, so a
  threshold-crossing truncate after a fsync failure would let an in-flight append ack over bytes
  the failed fsync may have dropped — truncate is now refused while fail-stopped (entry check plus
  an in-lock check in `compact()`, race-free because fail-stop is only recorded under the same
  lock). (2) The single-force-attempt test passed without ever reaching the in-lock guard it named
  (the refusal usually lands at the append entry check) — rebuilt with a GATED injected channel
  that parks the first append inside `force` while holding the sync lock, making the second
  append's entry-check pass deterministic and its refusal attributable ONLY to the in-lock guard.
  (3) Fail-stop had no operator surface (one ERROR log — log-scraping): now `WalStats.failStopped`
  → `wal.failStopped` on `GET /api/storage/retention` + CLI passthrough + docs; the dashboard slot
  inherits the retention view's recorded dormant-slot decision on #494. Also: fail-stopped `close()`
  skips the close-time force (the forbidden retry — pinned by force-call count); a failed
  post-compaction reopen fail-stops instead of leaving a zombie with a closed channel; refusal
  asserts on `Files.size` (disk), not the accounting; the unacked-record resurrection on reopen is
  pinned with `containsExactly` and documented as at-least-once territory; `syncFailure` is
  `Option<Cause>`, not a null sentinel.

### Added/Fixed (2026-08-25 — #634 structural follow-ups: the silences that hid the #492 class are closed)
- **Boot refuses a routed wire type with no codec (the #492-class killer).** Twice a generated codec
  registry existed but was never aggregated into `NodeCodecs`, and every message of the orphaned
  types silently vanished at the transport — runs 2–5 burned on exactly this with zero log lines.
  `AetherNode.verifyRoutedTypesEncodable` now runs before the router is wired: every ROUTED
  `Message.Wired` type must have a codec or the node refuses to start, naming ALL missing types and
  the probable cause (an unaggregated `*CodecsNode` registry). `Message.Local` types are structurally
  exempt — the sealed hierarchy is the discriminator, so no exemption list can rot. Recorded limit:
  the guard sees types this node ROUTES; a wired type only ever SENT is covered by the loud-encode
  net below. `[verified: VerifyRoutedTypesEncodableTest 5/5 — missing type named with the
  aggregation hint, Local-vs-Wired discrimination armed, multi-miss accumulation]`
- **Encode failures are LOUD.** There was no catch anywhere — an encode throw escaped the send path,
  killing the caller's promise chain unresolved (synchronous sends) or silently cancelling periodic
  broadcast tasks. Both transport encode sites now produce a typed `WriteOutcome.EncodeFailed` plus
  one ERROR naming the message class; the two outcome-consuming call sites
  (`DistributedDHTClient`, `EntityForwardService`) fail fast on it, while fire-and-forget
  send/broadcast paths are LOG-ONLY by design — there is no caller to tell (and the worker-side DHT
  network's default `sendOutcome` reports `Sent` unconditionally: a pre-existing blindness, now
  recorded at the default). The
  adjacent same-class silence — the wired router override bypassing `dispatchOne`'s try/catch, so a
  HANDLER throw was equally unlogged — gets the same treatment: one handler's throw is logged and no
  longer kills the dispatch of the rest. `[verified: QuicClusterNetworkEncodeFailureTest 2/2 (typed
  outcome naming the class, registered-type arming); RabiaNodeRouterDispatchTest 3/3 (thrower
  provably invoked, remaining handlers still run, no propagation)]`
- **The entity-forward wire carries the budget (stage-2 propagation, mirroring the HTTP forward
  pair).** The three request records gained `remainingMillis`; the sender stamps its remaining
  budget, and the OWNER refuses an arrived-expired command with the typed `ForwardBudgetExhausted`
  before touching the entity — applying a non-idempotent write whose ack nobody collects is the
  zombie-dispatch amplification 02w measured. Wire note: same-version clusters only, rc-internal
  (positional codec). `[verified: EntityForwardServiceTest 14/14 — arrived-expired refusal without
  touching the entity (armed by the NO_BUDGET counterpart), wire stamp bounded/unbounded]`
- **Invoke-layer waits are capped by the ambient budget — caller-side live, receiver-side a ready
  mechanism.** `InvocationHandler`'s dispatch timeout reads the budget at its synchronous arm site
  (inert today, see the tag below); `SliceInvoker`'s two correlation waits CANNOT read there — the
  arm sits behind encode/endpoint continuations on threads where the ScopedValue is unbound, and the
  first cut read `Deadline.current()` there: the new pin measured 60,015ms elapsed under a 300ms
  budget, the cap silently inert. The budget is therefore captured ONCE at each chain entry on the
  caller's thread (a parameter through the request/response chain; a `FailoverContext` component on
  the retry chain). A call under a client deadline gets at most what remains; with no ambient budget
  the configured value is unchanged. Wire propagation on `InvokeRequest` stays the recorded next
  step (`TimeoutsConfig` docs). Caller-side cap `[verified: InvocationDeadlineCapTest — bounded
  327ms vs the 60s ceiling, armed by a 2s still-waiting unbounded counterpart]`. Receiver-side cap
  (`InvocationHandler`): `[design intent — unverified]` on the LIVE path — the mechanism is pinned
  (a bound budget caps at 304ms in-process), but the only production caller is the inbound network
  dispatch and `InvokeRequest` carries no budget yet, so the read is always unbounded until the wire
  step lands (review catch: the first wording claimed both halves verified). Batch gate: build.sh
  clean, 3,933 tests / 0 failures across ten modules.
- **The "1:4254 generation-counter anomaly" is not one.** The counter is a LEADERSHIP-TENURE TICK —
  bumped once per `pingInterval` (1s default) while leader — so 1:4254 means rabiaTerm 1 with ~71
  minutes of uninterrupted leadership: the signature of a STABLE cluster. The per-interval semantics
  were written down nowhere and the value was investigated as an anomaly once; they are now
  documented at the increment site and on the generation surface. Deliberately no per-bump log.
  `[mechanism: counter increments only in bumpGenerationIfLeader, scheduled at pingInterval]`

### Added (2026-08-25 — #634-3+4: the tri-floor retention operator surface; WAL joins the storage subsystem)
- **`GET /api/storage/retention` + `aether storage retention` — the tri-floor view and the joint
  invariant (#634-4, rescoped into #634-3 per the ticket ruling: the operator surface is the only
  honest home of a checker that must see all three floors).** Per `(stream, partition)`: the WAL's
  live counters (size, replayable window, truncation watermark, per-group-commit fsync
  count/mean/max), the in-memory ring tail, the durable sealed bound, the earliest retained segment,
  and the entity checkpoint floor — joined into `coveredFrom` (earliest offset reachable from ANY
  local source) and the invariant verdict: an entity partition with a committed checkpoint is
  VIOLATED when no local source reaches back to `checkpoint + 1`, which is precisely the condition a
  future fold refuses on; the surface says so before that refusal is the first symptom. A 5-minute
  `RetentionInvariantWatch` WARN-logs and raises a `retention-invariant` critical alert once per
  newly-violated partition (re-alerting after recovery+relapse) — the existing alert path evaluates
  only while a dashboard client is connected, which is exactly the visibility gap the watch closes.
  Review hardening (4 MAJORs caught and fixed): the alert severity literal was lowercase and the
  validator is case-sensitive — the whole periodic half was INERT until the shared
  `RetentionRoutes.ALERT_SEVERITY` constant + a real-validator pin closed it; an EMPTY materialized
  ring reported tail `0`, permanently masking the restarted-empty case — now `-1`, and
  nothing-local-under-a-checkpoint is itself violated; raises are debounced to two consecutive
  violated ticks (the tri-floor join is a non-atomic cut); the invariant is documented as the
  NECESSARY half of reachability (min-of-starts, holes not detected — reclamation is oldest-first).
  `[verified: RetentionRoutesTest — coveredFrom source-preference incl. the WAL
  (truncatedUpto, lastOffset] window, violation + armed non-violation incl. restarted-empty,
  segment-only rows, entity-vs-bare-name discrimination, debounce once/transient/relapse/clean
  pins; severity accepted by a real AlertManager]` End-to-end alert delivery in a live cluster
  remains `[design intent — unverified]`. Operator recovery for a violated partition: backfill the
  missing range from a replica that still holds it, or accept the documented loss and re-baseline
  the checkpoint (docs carry the full action).
- **The stream WAL is now part of the storage subsystem's config, capacity and observability
  (#634-3).** `[storage.streams] wal_path` is a first-class TOML key (absent/empty = the exact
  pre-existing derivation `<artifacts disk_path sibling>/stream-segments/<nodeId>/wal`; explicit
  values still get the mandatory per-node suffix) `[verified: ConfigLoaderTest wal_path triple]`.
  `GET /api/storage` reports the `streams` instance's live WAL bytes as a peer field (the WAL is a
  sibling of the segment store, not a tier — the instance previously under-reported real disk by the
  entire WAL), carried through the cluster rollup (`StorageStatusValue` gained `walBytes`; the
  serializer arm — previously untested entirely, a gate finding — now has a full-field round-trip
  pin, and the BINARY consensus codec for it gained its first round-trip pin too, a review finding:
  the positional `@Codec` layout means the `walBytes` addition is a same-version-cluster wire change
  — rc-internal, no mixed-version rolling upgrade across this boundary pre-GA, same policy as the
  entity registration key change). Fsync latency is measured once per GROUP COMMIT (one nanoTime
  pair per batch, not per append) `[verified: PartitionWalTest.Stats 3 pins; StreamPartitionManagerWalSnapshotTest 2 pins
  incl. the no-WAL path]`.
- **`GET /api/entity/keyspaces` + `aether entity keyspaces` — the hosting view (owner-ruled fold-in
  of the 02w hosting-set observability).** Per keyspace: the sorted hosting node set (an upper bound
  on the candidate set — the leader intersects it with live members; owners are always drawn from it
  and nowhere else), the max partition count, and the
  rolling-redeploy disagreement flag — assembled from replicated KV, so any caught-up node answers
  identically. The 02w defect this surfaces was diagnosed from typed write refusals; now it is one
  GET. The view is a pure projection over `EntityOwnershipReconciler.scanRegistrations` — the
  single authority on the merge semantics (review catch: the first version re-implemented the
  merge with no equivalence guarantee). `[verified: EntityCheckpointRoutesTest projection pins]`
- Docs: full management-api.md + cli.md sections including the recovery actions; dashboard
  dormant-slot decisions recorded per the #494 template for both new endpoints. Two pre-existing doc
  defects fixed in passing (mis-titled entity-checkpoints section; `/api/entity/checkpoints` missing
  from the route table) and one corrected (the CLI checkpoints doc showed a fabricated table
  rendering — the command prints pretty JSON).
  Gate evidence for the whole batch: `./build.sh` clean; 3,368 tests / 0 failures across
  aether-config, aether-stream, slice, node, cli — RetentionRoutesTest 15,
  StorageStatusValueCodecTest 6 (both codec halves of the family), StreamPartitionManagerWalSnapshotTest 3,
  PartitionWalTest.Stats 3, ConfigLoaderTest wal_path triple, KVStoreSerializerTest 66;
  ManagementRouteCoverageTest confirms both new routes have handlers. A test-registry detour en route
  (the codec test first errored on every list-bearing value) documented a latent trap: value-codec
  tests must layer the framework parent registry the way production does, or the first List component
  fails with "No codec registered" — the requirement now lives in the test with the measurement.

### Verification (2026-08-24 — 02w run7, post hosting-set fix: THE SUITE IS FULLY GREEN, first ever)
- **14/14 assertions across all 10 phases, 0 failures — every number the hosting-set defect suppressed
  is now at its ceiling.** Ownership converged across all partitions in **31s** (run6: FAILED at 989s
  against a 480s budget); **40/40 pre-kill creates ACKED** (run6: 22/40 refused by non-hosting
  owners); the SIGKILL landed with **40 concurrent acks recorded during the kill window** (run6: 3 —
  the fast create path is what lets the concurrent creator land anything); **all 80 ACKED entities
  survived the crash with their exact values — 0 missing, 0 corrupted, 0 unreachable** (run6: 21).
  Failover settled in 2s; the checkpoint driver reports alive on exactly the 3 HOSTING nodes
  (`instances = 3` — the fix's shape visible in operations); post-crash liveness green; auto-heal
  restored all 5 cores to terminal convergence. Suite phase time 57s.
  `[verified: 02w-entity-crash run7 — evidence in aether/tests/integration/failure-logs/02w-run7-green/]`
  This closes the durability arc: #634-1 fsync-before-ack, #596 write-half forwarding, the deadline
  budget, the codec registration, and hosting-set ownership are all live-validated in one run.

### Fixed (2026-08-24 — entity arc ownership is minted over the HOSTING set: the last 02w defect)
- **The leader's entity-ownership reconcile minted `(entity:<keyspace>, partition)` owners over ALL
  cluster members; with `instances = 3` on five nodes, arcs owned by non-hosting nodes refused every
  write** (run6's one red test: `no entity registered for keyspace orders` from nodes 4/5, 22/40
  creates). Root cause was structural: the keyspace registration was a single cluster-wide record
  carrying only `partitionCount`, so the hosting set was unknowable at the decision site. Fixed by
  making the registration PER-NODE (`EntityKeyspaceRegistrationKey` is now `(keyspace, node)`; the
  set of committed records IS the hosting set) and giving entity arcs their own
  `StreamPartitionOwnershipWriter` whose HrwOwner places over registered hosts ∩ the reconciled
  member snapshot — same leader gate, epoch discipline and record family as streams; only the
  candidate set differs. Empty candidate set leaves the committed record untouched (no self-promote;
  writes refuse with the honest transient cause until a host returns). Failover re-placement stays
  within the hosting set by construction. The logic moved to a new testable
  `EntityOwnershipReconciler` (aether/node).
  `[verified: DurableEntityForgeTest 12/12 forming 5 nodes with instances=3 — incl. the new
  ownership_isMintedOnlyOverNodesHostingTheEntitySlice pin and state-survives-owner-loss, 82s;
  EntityOwnershipReconcilerTest 15/15, mutation-proven (removing the hosting-set intersection —
  the literal defect — reds 3 tests); touched modules 1879/0]` The cloud 02w re-run — the final gate
  per the in-JVM-first sequencing rule — ran the same day and closed the suite fully green (see the
  run7 verification block above).
- **Review catch (MAJOR): the reconciler was not the SOLE writer of `entity:*` ownership.** Entity
  logs are real streams since I3, so the stream-side replica reconcile walks them too — and its
  ownership driver, placing over the whole member view, would have re-placed entity arcs onto
  non-hosting nodes on every catalog/membership edge (entity deploys included — `createStream` fires
  a config Put that reconciles immediately), fighting the entity reconcile record-for-record with up
  to one 5s tick of refused writes per flip. The forge suite structurally cannot observe that window
  (it converges before asserting). Fixed by excluding entity arcs from the stream ownership driver
  (`driveStreamOwnership` filters through `EntityOwnershipReconciler.withoutEntityArcs`; log REPLICA
  placement is untouched) — the entity reconciler is now the exclusive authority, which is what makes
  the "by construction" claim above true. `[verified: withoutEntityArcs unit pin — entity arcs
  dropped, stream arcs kept including one whose bare name equals the keyspace]`
- **Review catch (MAJOR): a keyspace containing `/` was unvalidated while two parsers rely on its
  absence** (the entity DHT-key grammar and the new registration identity — a `/` would silently
  fence writes against the floor arc and shred snapshot restores into a phantom keyspace). Now
  refused at bind time by `durableEntityConfig` with the typed `InvalidKeyspace`, the one entry
  point every keyspace passes through.
  `[verified: DurableEntityConfigTest slash-refusal + named-cause pins]`
- **Registrations now have a full lifecycle.** The reconcile tick makes each node's committed
  records equal its locally-declared set in BOTH directions: asserting declared keyspaces (as
  before) and PRUNING committed-but-undeclared self-records. `EntityKeyspaceRegistrar.retract` +
  a close hook on the provisioned entity (run via the factory's `close` override when the keyspace's
  last local consumer slice stops) retract the declaration, unregister the forward target
  (`EntityForwardRegistry.unregister` — an arriving forward for an unloaded keyspace now gets the
  typed refusal instead of reaching into a dead classloader) and unhook the checkpoint driver. The
  entity deliberately is NOT `AutoCloseable` (review catch): a public close would let slice code
  unhook a live keyspace, so the unload seam is package-private and reachable only through the
  factory.
  The prune leg also heals what retract can never see: a node that died and restarted WITHOUT the
  slice sheds its stale record, so a live node can no longer stay a placement candidate for a
  keyspace it stopped hosting. Residual (recorded in `EntityKeyspaceRegistrar` docs): a node that
  dies and NEVER returns leaves a permanently dead record — excluded from every placement decision
  by the liveness intersection, reaped only by hand until a reaper exists.
  `[verified: DurableEntityFactoryTest Unload 2 pins (close retracts + unhooks, exactly once);
  EntityForwardServiceTest unregister pins; registrationDelta both-directions pins incl.
  leaves-other-nodes'-records-alone]`
- Wire/snapshot note: the `EntityKeyspaceRegistrationKey` payload (SystemTags 1107) and its
  text-snapshot identity changed shape (`<keyspace>` → `<keyspace>/<nodeId>`). Same-version
  clusters only; rc3 is unreleased, no migration. Hosting-set observability (which nodes registered
  a keyspace) is folded into the #634-3/4 operator surface per owner ruling this session; committed
  arc owners were already visible via `GET /api/ownership/stream` and `aether cluster ownership
  stream` (entity arcs ride the stream family under the `entity:` namespace).

### Verification (2026-08-24 — 02w run6, post codec-fix: THE DURABILITY VERDICT, first ever)
- **All 21 ACKED entities survived a SIGKILL with their exact values — 0 lost, 0 corrupted,
  0 unreachable, full population checked within budget.** 18 pre-kill + 3 acked DURING the kill
  window (the first run fast enough for the concurrent creator to land anything). Failover settled
  in 1s; post-crash liveness green; checkpoint driver clean. This live-validates the
  fsync-before-ack chain including #634-1's replica path.
  `[verified: 02w run6 — Every_ACKED_entity_survives_the_crash over 21 acked keys under SIGKILL]`
- **Suite wall clock: 552s** — runs 2–4 were killed past 5.6h, run5 took 4,230s. 9 of 10 tests
  pass; the one failure (ownership convergence) is now PRECISELY diagnosed by the typed refusal
  the same session added: `no entity registered for keyspace orders` from nodes 4 and 5 — the
  blueprint places `instances = 3`, and **the leader's ownership reconcile mints entity
  partition-arc owners across ALL cluster nodes instead of the entity-hosting set**, so partitions
  owned by non-hosting nodes refuse every write (22/40 creates). Forge cannot see this (formation
  size = instance count). Next work item: mint entity arc ownership over the keyspace's hosting
  nodes, and keep re-placement within them on failover.

### Fixed
- **The #596 entity owner-forward wire pair was NEVER REGISTERED in the node codec — every entity
  forward in every run silently vanished at the transport.** The annotation processor generated
  `EntityforwardCodecsNode`; the hand-maintained `NodeCodecs` aggregation never included it (the
  #492 defect class — "generated codecs lived only in the orphaned registry" — second occurrence),
  and the transport swallowed the encode throw, so the sender saw nothing and burned its full
  correlation timeout. ONE line explains every entity integration symptom across runs 2–5:
  8,977s creates (30s constant per doomed leg pre-budget), run5's ~64s creates and "unreachable"
  keys (the 10s budget cut the per-leg burn — the budget was NEVER unbounded; the 30.1s gap
  quantization was 3 × 10.03s doomed legs), convergence timeouts on healthy clusters, and the
  forge suite's 299s setUp hang. Fixed by aggregating the registry — upon which the SystemTags
  pinning guard immediately demanded hand-assigned tags (1660–1663), exactly as designed. Also
  fixed while the (never-functional) wire was free to change: the forward response now carries
  `failureType`, and the entity reconstructs the owner's TYPED refusal
  (`EntityAlreadyExists`/`EntityNotFound`) — a string-flattened duplicate-create read as an
  unexplained failure to every matcher keyed on the type, which would have undercounted 02w's
  acked creates. `DurableEntityForgeTest` updated from the pre-#596 contract (non-owner refuses
  `NotCurrentOwner`) to the forwarding contract (the owner's verdict reaches every caller) — the
  suite is failsafe-excluded from CI and had never run since forwarding landed.
  `[verified: DurableEntityForgeTest 11/11 incl. state-survives-owner-loss, 100/100 forwards
  complete within budget, 0 timeouts, convergence in seconds vs 299s hang; NodeCodecsRoutedTypesTest
  pins the four codecs; EntityOwnerForwardTest 21/21 incl. typed-reconstruction pins; node 892/0;
  durable-entity 155/0]` Follow-ups recorded: boot-time routed-type⇒codec verification (the
  structural fix for the #492 class), and the transport's silent swallow of encode failures.

### Verification (2026-08-24 — 02w run5, first run with complete evidence)
- **The 02w wall-clock disease is fixed: 4,230s (~70 min) end-to-end vs run4's 5.6h+ killed.**
  Deadline budget live-validated server-side (stage 1+2): suite legs bounded, cluster B formed in
  14s, failover settled in 2s, post-crash liveness create+read passed. Capture-before-heal proven:
  the SIGKILLed node's full log survived its `docker rm` for the first time
  (`streamed-aether-b-node-5.log`), along with the auto-heal replacement's.
- **Durability verdict: UNMEASURABLE this run — and for the first time that is what it says,
  instead of a false loss claim.** 14/40 creates acked inside the 900s create budget (~64s/create);
  pre-kill readback green (14/14 exact values); post-kill readback exhausted its 900s budget at
  11/14 keys with 2 UNREACHABLE (no node answered — quarantined, NOT counted as loss). Among the
  measurable: 0 lost, 0 corrupted. `[design intent — unverified]` remains the standing durability
  claim; the run adds NO evidence of loss.
- **The remaining defect is localized and quantified:** entity operations from most nodes
  chronically burn their full 10s budget before one leg lands (~64s/create healthy, ~82s/read
  post-kill; ownership convergence 989s vs 480s budget on a HEALTHY cluster). One pathology
  explains all three red measurements. Root-cause investigation on the complete run5 logs is the
  next work item; suite generation counter hit 1:4254 (churn to explain).

### Fixed
- **02w's verdict could not distinguish data loss from an unreachable cluster — now it must.**
  `read_amount` is a three-way protocol: `found` (value), ABSENT (positive `"outcome":"absent"`
  from a node HOLDING the key's arc — non-holders answer `PartitionNotHeld`, never absent), and
  UNREACHABLE (no positive answer — fails the run as "verdict unmeasurable", never as loss; run2's
  "1/2 lost" was rendered over exactly this conflation). Create and both readback loops carry
  wall-clock phase budgets (`CREATE_BUDGET`/`READBACK_BUDGET`, 900s) — run4 ran a 20,295s readback
  against nothing; budget exhaustion caps the population (creates) or reports UNMEASURABLE
  (readback), never a fabricated verdict. Remote `_api_call` gains a 5s connect cap; the
  failure-log dir is cleared per run (run3's diagnosis nearly used run2's stale capture); and
  capture-before-heal log streamers on the remote host (scripts/log-streamer.sh, self-healing
  5s re-scan picks up auto-heal replacements) make node logs survive `docker rm` — auto-heal
  destroyed the dying node's evidence in runs 2 and 4. The streamer's own pkill uses the
  self-excluding `-[f]` pattern: the bare pattern matched the remote shell running the start
  chain and killed it (run5 hit this live; the recorded "pgrep matches your own waiter" class).
  `[verified: 02w run5 — budgets fired and reported honestly, streamed logs captured for all
  nodes including the killed one, UNREACHABLE quarantined from the loss count]`

### Added
- **Per-request deadline budget shared across layers (`Deadline`, core) — the fix for the 02w
  wall-clock disease.** Client-visible operations had no deadline shared across layers, so each
  layer's timeout multiplied the one above: 5s forward hops re-driven over an unbounded receiver
  dispatch, over a hardcoded 30s entity owner-forward wait, over 5s+retry remote stream reads —
  run4 measured creates at ~97s each against a 30s client, and every abandoned hop left a receiver
  computing an answer nobody collects. Now the app HTTP server mints a budget
  (`timeouts.forwarding.request_budget`, default 10s; management forwards mint
  `management_request_budget`, 10s) carried as an ambient `Deadline` (ScopedValue; TimeSpan API)
  and consumed as `min(own timeout, remaining)` by: forwarder hops (`remaining/attempts-left`,
  counting an outer task-group retry loop's attempts, typed stop under a 200ms floor; remaining
  stamped on `HttpForwardRequest` so BOTH receivers — app and management — REFUSE a request whose
  sender already gave up at a 50ms floor and re-bind the budget for local dispatch); entity
  owner-forwards (refuse-below-floor before the send — a doomed send only widens the
  unknown-outcome window on a non-idempotent write; timeout cause now states the owner MAY have
  applied the command); and remote stream read/publish ack waits including the shared
  forward-publish retry ladder, which captures the budget once and re-binds it around every
  attempt (retries run on scheduler threads where the ScopedValue is gone) and stops retrying
  when the backoff would outlive the budget. The `ContextPropagation` snapshot now carries the
  deadline, so every propagated async hop keeps it. Unbudgeted callers (background work, direct
  construction) keep every previous default — an unbound scope reads as unbounded. The
  durable-entity API captures the caller's budget and re-binds it across the
  `PerKeySerialExecutor` thread hop. NOT yet budgeted (recorded follow-ups): the
  invocation-layer east-west timeouts (needs budget on `InvokeRequest`) and the entity forward
  wire message (owner-side apply runs unbudgeted). `[verified: core DeadlineTest (fake-clock);
  HttpForwarderDeadlineTest — exhausted budget = typed fail with zero sends, wire stamped,
  hop wait capped; AppHttpServerForwardBudgetTest — expired wire budget refused with router
  never invoked, healthy budget re-minted for dispatch; EntityForwardServiceTest — refuse-before-
  send at zero AND below-floor + wait capped vs 60s config; EntityOwnerForwardTest — budget
  observed across the executor hop; StreamForwardClientTest — read/publish waits capped vs 60s
  config; StreamForwardRetryDeadlineTest — every retry attempt observes the budget, sub-backoff
  budget stops the ladder]`
  Handover 2026-08-24 correction recorded: the burn was NOT `InvocationTimeouts` re-driving the
  forwarder ×3 (those retry knobs are parsed but wired to nothing) — the re-driver is the layer
  ABOVE the node: harness sweeps × forwarder hops × the 30s `ENTITY_FORWARD_TIMEOUT`, now all
  consuming one budget.

### Fixed
- **`Retry` now stops on a terminal-classified `Cause` (core), and the QUIC removed-peer verdict is one.**
  Measured on 02w (2026-08-23): 4,160 scheduled retries of `stream dropped: peer is REMOVED (terminal)` —
  the classification lived in message text no policy reads. `Cause.isTerminal()` (default false, so every
  unclassified cause keeps bounded-retry behaviour), `Causes.terminal(...)`, and `Retry` fails immediately
  on a terminal cause with a distinct log line. The prior fix had made the removed-peer failure FAST; this
  makes it FINAL. `[verified: core RetryTest 13/0 incl. 2 new pins, mutation (guard removed) killed;
  consensus 15/0 with the seam pinned — the removed-peer cause asserts isTerminal]`
- **#634 item 1 — replicated stream records are fsynced BEFORE the replica acks.** `appendRecovered` never
  called `durablyLog` (one call site: the owner's publish path), so the "replicated" half of
  `minSyncReplicas` was RAM-until-seal: correlated power loss inside the unsealed window lost acked entity
  writes at ANY replication factor. Replicated/backfilled records now enter the same per-partition WAL the
  owner uses, CHAINED per partition (PartitionWal runs each append on a per-call async supplier, so
  unchained calls race FILE order — and file order is load-bearing: recovery derives `lastOffset` from the
  last record, truncation assumes monotonic offsets; the chain costs what the owner's own `durablyLog`
  already pays per record). `ReplicationReceiveHandler` awaits the new `syncReplicated` barrier before
  acking; a failed fsync WITHHOLDS the ack, so the owner's barrier degrades honestly instead of counting a
  copy that does not exist. A mid-chain failure deliberately poisons the chain — a later success would
  leave a WAL hole the ring does not have. Wall-less deployments resolve the barrier immediately: their ack
  means exactly what it meant before. `[verified: StreamPartitionManagerWalTest — independent-reader fsync
  proof for appendRecovered+syncReplicated, wall-less immediate resolve; ReplicationReceiveHandlerTest —
  ack held until the barrier resolves, ack withheld on sync failure; aether-stream 15/0 in the touched
  suites]`
- **#634 item 2 — an unwritable WAL dir now REFUSES BOOT instead of one WARN and fsync-free acks.** The
  degrade silently converted "durable entity" into "in-memory entity". Opt-in for explicitly best-effort
  deployments: `-Daether.allowNonDurableStreams=true` / `AETHER_ALLOW_NON_DURABLE_STREAMS=true` keeps the
  previous WARN-degrade byte-identical. Enforced in `Main`'s `verify* -> abortBoot` chain (the same idiom
  as the cluster-name and dev-mode gates — JBCT forbids the throw-based abort first attempted, and the
  lint pushed the gate to where boot policy belongs); a node constructed DIRECTLY (Forge, tests, embedded)
  never passes Main and keeps the degrade-with-WARN, which is exactly the explicitly-best-effort population.
  The decision is an extracted, tested seam (`AetherNode.decideWalAvailability` /
  `AetherNode.verifyWalBootable`) — the boot guard is the part most likely to be "simplified" into a
  silent-degrade regression. `[verified: WalAvailabilityGateTest 3/0 — refusal names the escape hatch]`
- **#634 item 6 — three documentation overclaims corrected:** AHSE feature-catalog row 207 Complete →
  Partial (noOp demotion/GC, unreachable RemoteTier, no compression/encryption on the engine write path,
  absent §10 metrics, ack-at-last-tier until #349 DD-8-1); the CHANGELOG's "KV-Store backed MetadataStore"
  claim (never built — `InMemoryMetadataStore` is the only implementation); two stale
  `EvictionListener.NOOP` gap notes (the segment sink IS wired via `StorageSegmentSink`).
- **Durable-entity structural review (#596 follow-up): four fixes from one read of spec vs implementation
  against live evidence.**
  **S1 — a fold was rebuilt once and then FROZEN.** `EntityFold.ready()` memoizes a successful rebuild
  forever, and `fold.apply` had exactly two callers — both on the owner's own append path — so nothing ever
  fed REPLICATED records into a replica's fold. `BOUNDED_STALE` on a replica therefore served a
  rebuild-time snapshot (unbounded staleness under a bounded name), and a replica later PROMOTED kept the
  frozen view — mutating on top of stale state and silently dropping every record replicated after its
  rebuild: lost updates on the failover path. Every access now catches the fold up to the log's current
  head before serving (one runner per partition; joiners wait and re-check, since interleaved appliers
  could write a key's older state over its newer one; offsets the append path already applied are
  accounted, never re-applied). A fold whose watermark fell behind retention clears its rebuild memo and
  the next access rebuilds from the checkpoint — the only bridge over a truncated range.
  `[verified: 4 new EntityFoldFreshnessTest pins — replica staleness, promotion-mutates-replicated-state,
  watermark accounting under interleave, truncation→re-rebuild; 3 mutations (hook reverted, account-only,
  memo-clear removed), all killed]`
  `[design intent — unverified]` that S1 was the mechanism behind a live durability failure: the 02w
  durability verdict over a full population is still pending, and the frozen-fold consequence is
  established by code reading and unit pins, not yet by a cluster reproduction.
  **S6 — a forward the transport refused burned the full 30s correlation timeout in silence.** The send
  seam was fire-and-forget; 02w measured the cost (40 creates in 8977s — ~75s each — against 12–15ms for a
  create whose partition was ready). The service now sends via `sendOutcome` and a refusal
  (`ConnectionDead` / `NoPeerState` / `BackpressureRefused`) fails the caller immediately with a typed
  cause naming the refusal; the timeout remains as the backstop for sent-but-unanswered.
  `[verified: 5 new EntityForwardServiceTest cases; mutation (outcome ignored) killed by the named test]`
  **S4 — `ensureLog` was idempotent AND shape-blind.** `tolerateAlreadyExists` accepted a redeploy
  declaring a different `partition_count` against an existing stream — the arc then re-hashes keys onto
  partitions whose history lives elsewhere, and they read back as absent with nothing saying why. The
  declared shape must now match the existing stream's `(partitions, replicas, minSync)` or provisioning
  refuses, naming both shapes. `[verified: 2 new StreamEntityLogSubstrateTest pins; mutation killed]`
  **S2 — `ReplicationBarrierUnmet` advised "retrying is safe", which is FALSE for update.** The write is
  fsync-durable locally, locally served, and replayed on recovery — a retried `update` re-applies the
  mutator on state that already includes it. The cause now states the per-operation recovery: a retried
  create/delete self-identifies via `EntityAlreadyExists`/`EntityNotFound`; an update must read back and
  decide from observed state. This is a third outcome (durable-but-under-replicated) distinct from
  success and failure, and its docs now say so.
  **Spec reconciled (S5):** `BOUNDED_STALE`'s bound is the backing stream partition's REPLICATION lag
  (entity state has lived on stream partitions since I3, not in consensus-applied KV), earned by the S1
  catch-up; a node outside the replica set refuses rather than answering "absent". The
  `replication_factor` comment claiming the field is "ignored once the log path is wired" said the
  opposite of what landed.
  **Deliberately NOT in this batch:** the spec's `(key, n)` idempotency counter (S3). Its only stated
  consumer is slice side-effect code in the workflow/saga increments (I5/I6), and the part that would fix
  caller-retry double-apply needs an API-level idempotency token — a public-surface decision recorded
  here as OPEN, not silently dropped.
- **Entity `create` and `delete` now forward to the committed owner (#596).** The owner-forwarding that
  landed with the `Mutator` primitive covered `update` ALONE — `forwardTarget` had exactly one call site —
  so `create` and `delete` on a non-owner were still refused outright. That is the operation #596 was filed
  on: its evidence was creates failing, 4 of 40 acked. Both now take the same hop, with the same guarantees
  the update path already had: the owner re-runs its OWN admission on arrival (so a hop to a deposed owner
  is refused exactly as a local write would be), the command runs inside the owner's per-key serialization,
  and a failed forward surfaces as a failure and is never applied locally — that fallback is the split-brain
  the ownership fence exists to prevent.
  `EntityCreateForward` and `EntityDeleteForward` are NEW variants of the sealed `@Codec` message rather
  than a flag on the shipped `EntityUpdateForward`: adding a component changes an existing message's encoded
  shape, whereas a new permitted subclass gets its own tag and leaves the wire untouched. They share
  `EntityUpdateForwardResponse` (empty `state` for delete, which has no post-state) — deliberately NOT
  renamed to match, because a `@Codec` type's name IS its tag identity and re-deriving it for a cosmetic
  gain is not worth a wire change.
  **Also fixed, because the new paths would otherwise have copied it:** the landed `forwardUpdate` called
  `serializer.encode(...)` inline inside a `flatMap`. `Serializer` THROWS on a codec miss and `doUpdate` runs
  INSIDE the per-key tail, so an escaping throw left the caller's promise unresolved and wedged that key's
  serialization tail for good — the exact hazard `readState`/`commit` in the same file lift their encodes to
  avoid. All three send paths now lift, and the receiving side lifts its decodes too, so an undecodable
  payload answers the sender with a typed failure instead of making it wait out the 30s timeout.
  `[verified: unit + mutation — durable-entity 147/0, aether/node 874/0, `EntityOwnerForwardTest` 17/17;
  6 mutations, 6 kills, no survivors (create/delete no longer forward, receiver skips admission, delete
  answers a state, failed forward falls back locally, unwired no longer inert)]`
  `[design intent — unverified]` on the live path: no forwarded create or delete has crossed a real network.
  `02w-entity-crash` is the suite that would prove it and it CANNOT yet — it fails earlier, on entity
  ownership never converging across partitions (2147s against a 480s budget), which is a separate defect.
  **Two tests were found passing for the wrong reason** while pinning this, both by mutation rather than by
  reading. `applyForwarded_refuses_whenTheReceiverIsNotTheOwner` — pre-existing, from the landed write half —
  asserted only `isFailure()`; on a fresh substrate, removing the ownership fence ENTIRELY still fails, with
  `keyNotFound`, so it would have stayed green with the fence deleted. The `refusesAsBefore` trio had the
  same shape: an unwired transport fails with `FORWARD_UNWIRED`, which is indistinguishable from an admission
  refusal through a bare `isFailure()`. All six now assert the failure's CAUSE.
- **A never-written stream pinned every replica in `SYNCING` forever (#631).** Three gates each exclude the
  GENESIS case — a stream just created and never written — and together they made it unrecoverable. The owner
  is legitimately at watermark `-1` and self-promotes; replicas pull from it, receive an EMPTY response, and
  #445 routes that into the no-source path because an empty owner read is never trustworthy. `waitThenPromote`
  then suppressed the cold-start promote on the mere EXISTENCE of a committed owner record — always present for
  a freshly-created stream. Observed in `02y-stream-crash` as all 4 partitions of the suite's OWN fresh
  blueprint pinned for the entire 240s deploy window, so the livelock IS the deploy timeout; `03-scaling`
  showed 1436 pins on `entity:orders`. The deferral is now decided on the owner's probed TAIL rather than on
  its record's existence: owner ahead → defer (the real #445 case), owner unreachable → defer (an unknown tail
  must not be read as an empty one), owner not ahead → fall through to the probe contest. **No wire-format
  change** — `CatchupResponse` is tag-pinned at 99 and rolling upgrade is Phase-1 only, so the owner is asked
  for its tail instead of a record component being added.
  Two further defects surfaced while pinning it, neither addressed by the tail check alone. **The contest's
  single-winner ELECTION parked every non-owner**: the owner is a registered replica sitting at the same `-1`
  and wins the lowest-NodeId tie-break from a code path it never contests, so the designated winner never
  participates and every other replica stays SYNCING. The election is now dropped when self sits exactly AT the
  owner's authoritative tail; the contest's SAFETY checks — every peer reachable, none ahead — are untouched
  and govern identically in both modes. **And the unreachable-owner fallback was attached to the whole promise
  chain** rather than to the probe's own `Result`, so a deliberate defer and a declined contest were both
  re-reported as an unreachable owner — a fabricated operator signal on the exact surface a pinned partition is
  diagnosed from. The issue's filed mechanism (a missing owner-liveness check) and its suggested membership
  filter are both obsolete: `AetherNode.committedOwnerStillAlive` already filters the committed-owner source,
  empty-view caveat included.
  Also removed `backfill_emptyOwner_boundElapsed_committedOwnerPresent_staysSyncing_notColdStartPromoted`,
  which asserted the exact rule this reverses AND was vacuous — it set the clock past the bound before the
  first call, but `firstNoSourceMs` is armed lazily on that same call, so `waited == 0` and it returned at the
  within-bound guard without ever reaching the branch it named. It passed identically before and after the
  change, in both directions.
  `[verified: unit + mutation — aether-stream 680/0; 5 new `GenesisEmptyStreamPromotion` cases, each killed by
  a distinct mutation (election restored, defer-on-equal, `orElse` scope, owner-ahead defer removed,
  unreachable treated as empty) — 5 mutations, 5 kills, no survivors]`
  `[verified: aether/tests/integration --env remote, 2026-08-23, 5-node cluster B with failure injection —
  02y-stream-crash 1p/0f in 94s (was 0p/1f, blueprint never ACTIVE, deploy timing out at 240s); its
  multi-partition blueprint now deploys in 8s and the suite completes SIGKILL-of-the-owner-under-concurrent-
  publish, ACKED-event survival and offset contiguity. 03-scaling 3p/0f in 248s — exactly its recorded
  baseline, against 4204s and a 100.00% error rate on Scale_down_7_-_5_under_load when broken]`
  Two limits on that evidence, stated rather than glossed. The per-node pin COUNTS (212 on `02y`, 1436 on
  `03-scaling`) were NOT re-measured: node logs are captured only on suite FAILURE, so a green run produces
  none — the evidence here is behavioural (the livelock WAS the 240s deploy timeout; a deploy completing in 8s
  cannot be livelocked), not a direct pin count. And `03-scaling`'s mechanism was never confirmed to be this
  one — its owner log was lost to an SSH reset — so its recovery is CONSISTENT with this root cause without
  proving it alone. The election is deliberately KEPT on the failover path: after a real owner eviction the
  recomputed HRW owner self-promotes on the owner-immediate path and replicas catch up from it, so the contest
  is a fallback there rather than the mechanism.
- pg-parser: **nested block comments now parse correctly** (#619, upstream `siy/java-peglib#45`). PostgreSQL nests `/* ... */` per the SQL standard; the grammar now declares `%nest '/*' '*/'`, new in peglib **0.7.3**, which lexes the pair with a depth-counting scanner instead of a DFA path. There was no grammar-only alternative: nested comments are not a regular language so no DFA can match them, and the recursive spelling is refused by peglib's analyzer as `grammar.whitespace-cycle` because `BlockComment` is reachable from `%whitespace`. **The `BlockComment` alternative is removed rather than kept alongside `%nest`** — measured, not assumed: keeping it and dropping it give identical results on a single-level comment, a nested comment, a nested comment whose span contains a statement-splitting `;`, and an unterminated block (byte-identical error text, since an unterminated block falls through to the DFA either way). Two lex paths for one construct could only drift. The corpus statement excluded while the gap was open is restored to `dml-select.sql`, where `CorpusParseTest`'s per-line statement count now pins that a `;` inside a nested comment does not split the statement. `NestedBlockCommentGapTest`, which deliberately asserted the broken behaviour so that closing the gap would turn it red, is replaced by `NestedBlockCommentTest` asserting the correct behaviour — the load-bearing assertion counts select-list items rather than parse success, because the defect's whole danger was that it did not fail
- Correction to the previous entry describing this gap: `SELECT 1 /* a /* b */ -- */\n , 2 FROM t;` was cited as a second silent-divergence case and is **not** one. That comment is balanced (two opens, two closes), so `, 2` is legitimately outside it and two select-list items is the correct reading — which the old lexer also produced, by a different route. Only `SELECT 1 /* /* */ , 999 -- */\n FROM t;` diverges, because `, 999` sits inside the balanced span (2 items on 0.7.2, 1 on 0.7.3). Count the delimiters before deciding what the right answer is: a balanced nested comment is precisely the case where the buggy and correct lexers agree
- pg-parser: the nested-block-comment gap (#619) is **not a parse failure** — it can silently accept a different statement, and the docs said otherwise. PostgreSQL nests `/* … */` per the SQL standard; `BlockComment` closes at the first `*/` and the remainder leaks into the statement as live SQL. When that leaked text composes into something valid there is no diagnostic at all: `SELECT 1 /* /* */ , 999 -- */\n FROM t;` parses cleanly as `SELECT 1, 999 FROM t` (two select-list items) where correct nesting means `SELECT 1 FROM t` — note that `, 999` must sit INSIDE the balanced span for this to diverge; a comment that balances before the leaked text (`/* a /* b */ -- */` then `, 2`) reads the same either way — the trailing `-- */` reads as a line comment and swallows the orphaned outer `*/`. Even the loud cases mislead: `SELECT 1 /* outer /* inner */ still a comment */ AS c;` reports `expected end of input at 1:37` because the parser first ACCEPTS the truncated `SELECT 1 still` (implicit column alias) and only then chokes, pointing past the real cause and never mentioning comments. The original ticket's "fails to parse, rare in practice" held only for the inputs it happened to try; rarity is the only thing bounding the blast radius. Spec, feature catalog and the note at the rule in `postgres.peg` corrected. `NestedBlockCommentGapTest` pins the CURRENT wrong behaviour on purpose — a disabled test documents intent but never fires, whereas an assertion on today's behaviour goes red the day peglib closes the gap, which is the signal to re-add the two excluded corpus statements and revert the docs. Root cause is upstream and now tracked as **siy/java-peglib#45**: `%whitespace` alternatives are DFA-absorbed, and the recursive repair is refused by peglib's analyzer as `grammar.whitespace-cycle`, so no grammar-side fix exists. Also corrected a stale `-Pgenerate-parser` instruction (that profile was retired in `03d547e26`) in #619's repro block and in `CorpusParseTest`'s doc comment
- Examples: `examples/banking` never ran the jbct plugin at all. The root pom defaults `jbct.skip` to `true` (to avoid a reactor cycle) and every other example subtree overrides it to `false`; banking did not, so `CollectSliceDepsMojo.execute()` returned at its first line and `slice-deps.properties` was never written for any of the four modules. `DependencyVersionResolver` then fell back to package-derived coordinates, which is why the generated factory named the account slice `org.pragmatica.aether.example.banking:account` while its manifest called it `banking-account-account-service`. Banking was the only example family in this state, and the only one with slice-to-slice dependencies, so it was the only place the fallback was visible. With the override, `collect-slice-deps` writes the three real coordinates (`banking-account-account-service`, `banking-exchange-exchange-rate-service`, `banking-fraud-fraud-detection-service`, all `:1.0.0-rc3`) and the generated factory, the caller's manifest and the target's `slice.artifactId` all agree. Nothing was wrong with the resolution mechanism — note for future investigations that a build stopping before `package` ALWAYS produces an empty deps file, since `CollectSliceDepsMojo` reads slice manifests out of dependency JARs and skips any artifact whose file is not a `.jar` (a reactor sibling resolves to its `target/classes` directory), and that a manifest's `base.artifact` is the base module GAV, not the routing coordinate
- Examples: enabling the plugin on banking surfaced one build-breaking lint error that had never been checked — `TransferService.assessRisk` returned `Promise<Void>` and produced it with `Promise.success(null)` (`JBCT-RET-04`). Now `Promise<Unit>` / `Promise.unitPromise()`, which also removes the null. Banking had **zero** format drift despite never having been format-checked. 26 warnings remain (naming, zone-verb, nesting, chain length); they do not gate the build and 11 sit in the file #606 will rewrite
- Examples: slice-goal wiring made uniform across all 19 slice modules — `install-slices` and `verify-slice` added to `comprehensive-persistence`, `verify-slice` added to `step-composition`, `url-shortener` and `url-shortener-v2`. All four pass `jbct:verify-slice`, as do banking's four slice modules now that the plugin actually runs there. Non-slice `shared` modules are unaffected: they declare no jbct executions, and `verify-slice` is bound only in the slice modules themselves
- **The entity min-sync barrier counted the owner twice (#345 I3 path).** `minSyncReplicas` COUNTS the
  owner — `DurableEntityConfig.minSyncReplicas()` states it outright: "`2` means the owner plus one peer,
  i.e. `awaitReplication(..., minAcks)` blocks on ONE distinct non-self ack". But `awaitReplication`
  counts DISTINCT NON-SELF acks, and `StreamEntityLogSubstrate.awaitBarrier` passed the raw value, so a
  keyspace configured for `2` waited for TWO peers. At the default `replicationFactor = 3` that is
  satisfiable only while BOTH peers are alive and caught up — losing a single peer failed every entity
  write with `ReplicationBarrierUnmet`, which is precisely the failure replication exists to survive. At
  `replicationFactor = 2` there is only one non-self replica in existence, so no entity write could ever
  succeed. Both stream writers already subtract (`StreamWriteRouter`, `StreamForwardHandler.awaitMinSync`);
  this was the third writer on the same barrier and the only one that did not.
  Distinct from #596 (entities unreachable off the partition owner, no owner-forwarding) — same subsystem,
  independent causes. #596's own evidence rules this out as its cause: 4 of 40 creates acked, which could
  not happen if the barrier failed everything.
  `[verified: unit + mutation — new StreamEntityLogSubstrateTest, 2 cases (minSync 2→1 ack, 3→2 acks, the
  second guarding a mutant that hardcodes 1); reverting the fix turns both red. aether/node 874/0.]`
- **A segment whose age was unknown blocked size- and count-based eviction too.**
  `SegmentIndex.rebuildFromRefs` reconstructs the index from ref NAMES, and a ref name carries only
  `streams/<stream>/<partition>/<start>-<end>` — so after a restart every rebuilt segment came back with
  `maxTimestamp = 0`. `RetentionEnforcer.isSegmentExpired` returned `false` on that, and because the check
  sat BEFORE the policy call it withheld the segment from the count and size limits as well. Every segment
  sealed before a restart was therefore permanently unreclaimable and disk grew without bound across
  restarts. An unknown age is now passed as `0`, which disables only the AGE term: under `ANY` the size and
  count limits are ORed and work again; under `ALL` every limit must be exceeded, so an unknown age still
  withholds the segment — conservative in the direction that cannot delete data.
  **This does not restore age-based retention for pre-restart segments** — their age is genuinely recorded
  nowhere. That needs `maxTimestamp` persisted, which is a ref-name/metadata format change and a
  stored-format decision rather than a local fix.
  `[verified: unit + mutation — 2 new RetentionEnforcerTest cases (maxTimestamp=0 evicted under mode ANY on
  maxCount and on maxBytes); reverting the early return turns both red, and the pre-existing
  "never evicts on age alone" case still passes. aether-stream 676/0.]`

### Changed
- **A durable entity's transition is now a NAMED command, not a lambda (#596 prerequisite, unblocks
  #351/#353/#354).** `DurableEntity<K, S>` becomes `DurableEntity<K, S, C extends Mutator<S>>`, and both
  `update` and `scheduleTimer` take `C` instead of `Fn1<S, S>`. The blocker this removes is not stylistic:
  a lambda has no name, so it can be neither persisted for a durable timer's `onFire` nor forwarded to a
  partition owner. The slice JAR is already on every node, so the CODE is cluster-wide and only the DATA
  identifying which transition to run has to travel — and a record has a name where a lambda does not.
  `Mutator<S>` lives in `resource/api` (the lowest module every consumer sees) and deliberately does NOT
  extend `Fn1`: `Fn1.then`/`before` return a COMPOSED LAMBDA typed as `Fn1`, which is not a record, gets
  no generated codec, and carries no tag — inheriting them would let `a.then(b)` typecheck and produce
  something that looks like a transition and cannot cross a boundary, on exactly the paths this type
  exists to make safe. Implementors declare a SEALED hierarchy of record variants, which is also what
  keeps lambdas out: a lambda cannot implement a sealed interface, so an unpersistable transition is
  unrepresentable rather than merely discouraged.
  `[verified: durable-entity 130/0; aether/node 874/0 with no tag collision in the full assembly;
  the blueprint build REJECTED a surviving method reference (`OrderState::expired`) at compile time,
  so the guarantee is enforced by the type system rather than by review.]`
- **Slice codec generation now recurses into a sealed root's permitted subclasses.**
  `FactoryClassGenerator.addResourceTypeArgumentEntry` bailed on anything that was not a RECORD or ENUM,
  so a sealed command hierarchy landed in `requiredTypes` with NO codec generated for any variant — and
  the build still succeeded, leaving the failure for the first attempt to put a command on the wire. Each
  variant is a record and takes the existing path, and since every variant is its own registered codec
  type, the tag IS the discriminator: no new wire concept, no envelope-version question.
  `[verified: mutation — removing the recursion drops the blueprint's generated codec references from 8
  to 1 while the build stays GREEN, which is what makes the omission silent and the fix load-bearing.]`
- **A sync-quorum test raced the resync timer rather than a too-short sleep.** `RabiaEngineTest$SyncQuorum`
  failed ~80% of the time locally and twice on CI — including on a docs-only commit, which is what proved
  it was never a code regression. The cause was not timing slack: `testConfig()` retries the sync round
  every ~100ms and `RabiaEngine.doSynchronize` CLEARS `syncResponses` when a retry finds fewer than a
  quorum, so the test's first response was discarded before its second arrived and the node could never
  reach a quorum. Waiting longer could not have helped — the discarded response is gone and the test sends
  no more. The test now runs with a 60s sync-retry interval, keeping exactly one sync round in flight for
  its duration, and bounded polls replace the sleeps.
  `[verified: 12/12 passes (was 2/10); consensus module 707/0; mutating syncQuorumSize() to 1 still fails
  the safety assertion, so the test remains discriminating rather than merely quiet.]`

### Fixed
- **An elected load balancer on AWS/GCP/Azure opened no ingress and said nothing about it (#615).**
  REQ-5.1.8.2's auto-open of `app_http` for an elected LB — and the warning that requirement dictates
  verbatim — were BOTH reachable only through `BootstrapPhaseFirewall.managesIngressFor`, which requires
  Hetzner. Three gates each declined to cover the combination for individually sound reasons: PF-17
  restricts `ELECTED` only on SSH sources, PF-23 returns early when a source declares no explicit
  `allow_ingress`, and the `CREATE_FIREWALL` phase skips non-Hetzner sources entirely. The operator saw a
  clean bootstrap and a load balancer that served nothing, with no line anywhere pointing at ingress.
  Any cloud source with an elected LB whose provider has no Aether-managed ingress now gets a warning
  naming the source, the provider and the port that was NOT opened. It is emitted BEFORE the
  `applicable == 0` early return — such a cluster has zero manageable sources and takes exactly that
  path, so a warning placed after it would never fire for the only case it exists to cover.
  **A warning, not an error.** Security groups, VPC firewall rules and network security groups all deny
  inbound by default, so such a node is UNREACHABLE rather than exposed — the inverse of Hetzner, where an
  unassociated server accepts all inbound. Managing ingress yourself there is the arrangement PF-23
  explicitly directs operators to, so the config is legitimate; the defect was the silence.
  Implementing `openIngress` for those three providers remains separate feature work — their native
  mechanisms all exist, only the clients are missing.
  `[verified: unit + mutation — BootstrapPhaseFirewallTest 4 new cases, aether/cli 656/0. Four mutations,
  each killed: moving the call after the early return, deleting it, dropping the elected-LB condition, and
  dropping the provider condition. Note the first two produce identical failures — the tests pin THAT the
  warning fires, not where the call sits, so the placement is load-bearing and documented rather than
  test-enforced.]`

### Fixed
- **A `CAUGHT_UP` replica that stopped acking served stale reads forever and inflated the ring-release
  gate (§12 of the 2026-08-17 handover).** `ReplicationState.CAUGHT_UP` never downgrades — nothing moves
  a replica out of it. Under a partition the value does not go stale, it FREEZES at its last good reading
  and goes on reading as healthy indefinitely, which is the defect class the same session catalogued with
  seven instances. Two consumers acted on that raw state: `ForwardingReadRouter` selected read targets
  with it, so a replica readers could still reach but which had stopped acking kept serving stale data
  with no error; and `AetherNode.streamCatchupView` counted it, so an owner could release its partition
  ring believing enough replicas were caught up.
  A replica now additionally has to be FRESH: its `confirmedOffset` must trail the freshest peer watermark
  by no more than `[streaming] caught_up_max_lag_offsets`. Both consumers go through one method,
  `ReplicaRegistry.freshPeersFor` — a guard applied at one reader and not the other is exactly what left
  #590 live at the placement grain, so sharing the implementation makes that structural rather than a
  review question.
  **Lag, not a TTL, and the reason matters.** A watermark advances only on acks and backfill milestones;
  NOTHING refreshes it on a quiet partition. A time-based rule would therefore age out every replica of a
  write-idle stream and stop serving reads from the healthiest streams in the cluster — the trap #333
  documented in its own seam. Lag is self-correcting when quiet: if the owner has not advanced, no peer is
  behind. It also catches the case that motivated the finding, where writes continue while one replica
  stops acking.
  **Self rows are deliberately never lag-checked** — `selfCoversPartition`, `selfCaughtUp` and
  `LinearizableOwnerServe` still read the raw state, each with a comment saying why. A node never acks
  itself, so its own descriptor keeps the `SYNCING` / `-1` seed (#593) and reaches `CAUGHT_UP` through
  backfill completion rather than the ack path; measuring it against a peer watermark would report
  staleness on a perfectly healthy owner. Within `ForwardingReadRouter` one helper served both a peer and
  a self check, so guarding it wholesale would have been wrong. `PartitionBackfill.selectSource` also
  reads the raw state and is also correct: it takes the `max(confirmedOffset)` over `CAUGHT_UP` peers,
  which is the very value used as the freshness reference, so its donor has lag 0 by construction and
  routing it through the guard would select the identical node.
  Known limits of a relative measure, both pinned by tests so they are decisions rather than surprises: a
  partition with one registered peer compares it against itself and never finds it stale, and if every
  peer row freezes together their lags stay equal and none is flagged.
  **The default bound of 1024 is a guess** — not derived from a measured steady-state lag distribution.
  It is a config key (validated `>= 0`) so an operator hitting false staleness can relieve it without a
  rebuild; what would settle the value is observed peer lag under the 02y publish load.
  `[verified: unit + mutation — ReplicaRegistryTest$FreshPeersTests 9/9, aether-stream 674/0, node 872/0,
  ./build.sh green with 0 new lint. Mutation testing found a REAL HOLE on the first pass: deleting the
  CAUGHT_UP-state filter left the whole suite green, because the one test meant to pin it was passing on
  the lag arithmetic instead (a freshly registered peer seeds at -1, so it exceeded the bound anyway). A
  case with a SYNCING peer AT the reference watermark now isolates the state filter, and deleting that
  filter fails exactly that test. NOT integration-verified: no multi-node run has exercised a replica that
  stops acking while writes continue.]`

### Fixed
- **Auto-heal replacements were provisioned with no firewall association at all (#444 residual).**
  `HetznerComputeProvider.buildCreateRequest` took its firewall ids from `config.firewallIds()`, which is
  populated ONLY on the CLI bootstrap path — `ProviderResolver` threads in the ids `BootstrapPhaseFirewall`
  just created. A CTM auto-heal replacement is built from a `SourceProfile`, and that persists firewall
  **rules** but never the created firewall's **id**, so the list resolved to empty and the server was
  created unassociated. The provider's own javadoc states the consequence: a Hetzner server with no
  firewall accepts ALL inbound. The window `ProviderResolver` was written to close was shut for bootstrap
  nodes and wide open for every replacement — and the feature catalog claimed "no node is briefly
  unfirewalled" on the strength of bootstrap-only live runs.
  The association is now resolved BY LABEL at create, reusing the one-firewall-per-`(cluster, source)`
  selector the ingress path already owns. Persisting the ids at bootstrap was rejected — they go stale the
  moment a firewall is recreated out of band, and staleness in a security control is the worst failure mode
  available. Re-creating from `SourceProfile.firewallRules` was rejected as heavier per provision and as
  turning rule drift into a silent reconciliation.
  **The interesting half is the empty lookup, and a bare fail-closed would have been wrong.** "This source
  manages no ingress" and "a firewall exists but this source name did not select it" are indistinguishable
  from the source-scoped lookup alone, and they want opposite answers: the first is PF-23's explicitly
  endorsed *manage ingress via your own security groups* configuration, where every bootstrap peer is
  equally unfirewalled, so refusing would permanently disable auto-heal and buy no security; the second is
  the `ClusterTopologyManagerRecord.replacementSourceName` → `default` degradation, where the peers ARE
  firewalled and proceeding recreates the exposure. A cluster-scoped second look separates them — firewalls
  exist for this cluster but none for this source ⇒ refuse; none anywhere ⇒ create with a WARN. A lookup
  ERROR always refuses: unknown firewall state is not evidence of a safe one. That last one is earned
  structurally rather than by a guard — the failed lookup propagates through the create chain, so no
  server is built either way; the error mapping only makes the operator-facing reason say so.
  Refusing to provision is a deliberate behaviour change — today the node is created anyway. A missing
  replacement is a visible, recoverable degradation; a publicly reachable one is neither. The create-time
  log line now carries the firewall count next to the labels, so `firewalls=0` is readable at the moment
  it is decided rather than inferred later from the Hetzner console.
  The two provider-side lookups (cluster-scoped SSH keys, label-resolved firewall) are independent account
  queries and now run concurrently rather than chained, so a refusal costs one extra read-only list call
  that the sequential form would have skipped — and a lookup failure arrives as a composed error, which is
  what the error mapping now renders into an operator-facing reason.
  Both refusals are plain `Cause` records rather than wrapped exceptions: a fail-closed refusal is an
  expected outcome on this path, not an exceptional one, and `toProvisionError` re-wraps whatever reaches
  it — so the previous shape allocated and stack-filled two throwables per refusal purely to carry a
  string, and lost the structured fields on the way.
  `[verified: unit + mutation — HetznerComputeProviderTest.FirewallAssociationTests 5/5, hetzner module
  83/0. Four mutations checked: making the label lookup inert, forcing the empty-cluster branch to never
  fail, dropping the error mapping, and removing the configured-ids short-circuit. Each turns exactly the
  pinning test(s) red and leaves the controls green; none leaves all five green. NOT cloud-verified:
  end-to-end proof requires provisioning real paid servers, so the guarantee that a replacement comes up
  firewalled is asserted against the create REQUEST, not against a live server.]`

### Fixed
- **Worker-community zone grouping parsed the zone out of the NodeId instead of reading it (#592).**
  `GroupAssignment` string-split the `NodeId` at its last dash, so `node-1` grouped into a zone called
  `"node"` and a CTM-minted `…-r<clock36>` worker into everything before the suffix. That is identifier
  parsing, not zone awareness; it looked correct only because uniform naming put every node in one zone,
  which hid the defect behind the single-community case. The operator-facing `[worker] zone` knob and the
  `zone` label the Hello handshake propagates for exactly this purpose were both unread on this path.
  `computeGroups` now takes a zone resolver — kept as a seam so the assignment logic stays pure and
  directly testable — and `GroupMembershipTracker` binds it to the SWIM membership labels, which is where
  the advertised zone actually arrives.
  **The ticket described only half of it.** `AETHER_ZONE` was absent from
  `ClusterIdentityEnv.IDENTITY_VARS`, and both provisioning paths iterate that allow-list, so a
  provisioned node never received the variable and came up zoneless regardless. Fixing the grouping alone
  would have left the whole chain inert — the same unwired-gate shape as the core-absence fence. It is now
  in the allow-list, completing `AETHER_ZONE` → `NodeInfo.LABEL_ZONE` → announce → `SwimMember.labels` →
  grouping.
  A node advertising no zone falls back to `WorkerConfig.DEFAULT_ZONE` rather than to a fragment of its
  name: one honest bucket for "zone unknown" beats several confident-looking wrong ones. Since nothing
  sets `AETHER_ZONE` today, live behaviour collapses to exactly the previous single-zone case — this is a
  correctness fix that changes nothing until an operator sets a zone.
  `[verified: unit + mutation — GroupAssignmentTest 4/4, each written to FAIL against the old derivation
  (node names deliberately chosen to split into the same fragment while advertising different zones, and
  vice versa); re-deriving the zone from the name turns all four red. There was previously NO test
  coverage of this path at all. aether/node 872/0, environment-integration 59/0, ./build.sh green, 0 new
  lint. NOT integration-verified — a two-zone cluster run is #599.]`

### Fixed
- **ACTIVATING had no node-local remediation arm at all (#601).** `processStateTransition` carried a bare
  `case ACTIVATING -> {}` observer — structurally the same gap #325 closed for ROUTING — so a node whose
  activation stalled had nothing local to recover or report it. The only recourse was the leader-side
  remediator, which judges by a projection and, before `d9b37e180`, force-UNLOADed a slice that had been
  serving traffic 35 seconds earlier.
  **The gate, not the transition, is the load-bearing part.** ROUTING's arm force-progresses
  unconditionally, and its own javadoc earns that: the routes are already published and serving locally,
  so the cross-node ack is a confirmation optimisation. ACTIVATING cannot borrow that reasoning — the
  chain loads the slice at `activateSliceWithTimeout` and registers it for invocation only at the NEXT
  step, so a chain stalled in between leaves the slice loaded but unable to answer a call. Forcing ACTIVE
  there would manufacture a phantom-ACTIVE that the cluster routes traffic to, which is strictly worse
  than a slice stuck activating — and the same run exhibited that state elsewhere
  (`KV claims ACTIVE … not loaded locally`).
  So the arm forces ACTIVATING → ACTIVE only on positive proof of serving:
  `invocationHandler().localSlice(artifact)`, present exactly when the bridge is registered. That held in
  the observed incident — the chain had run past registration and published endpoints, and the node was
  serving `publish` calls when the leader unloaded it.
  When the slice is NOT serving the arm deliberately does nothing beyond a loud warning: failing it there
  would preempt the activation chain's own longer timeout, which may still legitimately complete. The
  chain's timeout and the (now KV-confirming) cluster remediator remain the backstops.
  `[verified: unit + mutation — NodeDeploymentStateSeedEpochAckTest$ActivationRemediation 3/3 (serving
  forces, not-serving must NOT force, already-left is a no-op); removing the serving gate turns exactly
  the phantom-ACTIVE test red. aether-deployment 837/0, aether/node 868/0, ./build.sh green, 0 new lint.
  NOT integration-verified.]`

### Fixed
- **The orphan sweep force-unloaded slices cluster-wide off a projection nothing re-derives.**
  `StaleEntryCleaner.cleanupOrphanedSliceEntries` classified a slice as orphaned purely from
  `active.blueprints()` — a leader-local map rebuilt only on `Active` entry, never re-derived during a
  term — and then issued UNLOAD for every slice of that artifact. It runs on each reconcile tick, so a
  single missed `AppBlueprintPut` (or a rename path that cleared the entry and lost the re-put) would
  unload healthy slices for the leader's entire term, under the reassuring log line
  `"orphaned slice entries (no matching blueprint)"`.
  It now confirms against the committed `SliceTargetValue` before destroying anything. The VERSION is
  part of the check: a target that has moved on means this artifact is superseded and genuinely is an
  orphan, so supersession still cleans up. Fail-safe as elsewhere — an absent or unreadable target falls
  through to the previous behaviour, so this can only spare a slice the cluster still targets.
  Also added the `coreMembershipResolved()` gate that the class javadoc already claimed **all** cleanups
  had; the three siblings gated, this one did not.
  Third instance of one defect class found by a single audit — see the community-placement and
  stuck-slice-remediator entries. In each, a local projection or self-reported value was consumed as
  observed truth by code that then acted destructively.
  `[verified: unit + mutation — ClusterDeploymentStateActiveTest$OrphanSweepStaleProjection pins both
  directions (a committed target spares the slice; a genuinely absent one still cleans up).]`

### Fixed
- **Rabia sync quorum was derived from CONNECTIVITY, so it collapsed to one at the partition edge (#557,
  second defect).** `RabiaEngine.syncQuorumSize()` computed `min(connectedNodeCount(), clusterSize) / 2 + 1`,
  which evaluates to **1** at connectivity 0 or 1 — so a node reaching exactly one peer would
  `restoreState` from a SINGLE response, adopting another node's consensus state wholesale precisely when
  it is least likely to be talking to the majority side of a partition. The old docstring justified this
  as "adapts to actual connectivity", which is the defect stated as its own rationale: connectivity is
  what a partition manipulates, so a safety threshold derived from it collapses exactly when needed. It
  is now a majority of the CLUSTER (`clusterSize <= 1` yields 1, since a single-node cluster has no peer
  to adopt from).
  **Direction is one-way — strictly stricter, so it cannot admit a sync that was previously refused.** The
  cost is liveness, deliberately: a node that cannot reach a cluster majority now stays inactive instead
  of syncing from a minority. Refusing to adopt state is the recoverable failure; adopting the wrong
  state is not.
  The test fallout was itself the evidence: exactly two tests broke, both in a stall-detector fixture that
  builds a **5-node** cluster and feeds **2** sync responses — a minority, which only ever activated
  because the old gate had collapsed. The fixture now supplies a genuine majority; no assertion was
  changed. The main 3-node fixture, where 2 responses IS a majority, stayed green throughout.
  `[verified: unit + mutation — RabiaEngineTest$SyncQuorum pins both directions (one response must not
  activate, two must, so it cannot pass against an engine that never activates); restoring the old
  formula turns exactly that test red and leaves the other 31 green. integrations/consensus 707/0.]`
- **Community placement read the community's own frozen member list (#590, at the placement grain).**
  `CommunityLivenessView` — built as #590's fix — had exactly ONE consumer in the codebase, the
  community-state FSM. `CommunityPlacementPlanner` never called it, and went on reading
  `announcement.members()` and `memberCount()` raw: the community's claim about ITSELF, which under
  partition cannot be rewritten, so it does not expire — it FREEZES. The core therefore kept weighting a
  cut-off community at its full size and naming nodes it could not reach in `WorkerSliceDirectiveValue`s.
  This is #590's stated consequence at a grain its own ACTIVE/DEGRADED gate cannot catch, because a
  community can sit comfortably above the viability floor while having lost members.
  Both placement axes now filter through the liveness view — WHICH nodes (`placeableMembers`) and HOW MANY
  instances (`liveMemberCount`) — and the filter itself moved onto `CommunityLivenessView.liveMembers` so
  the two cannot drift apart. Fail-safe throughout: `isAbsent` reports only POSITIVELY observed absence
  and is `false` when the collector is unwired, so an unwired deployment places exactly as before; and a
  community that publishes no member list keeps its declared count rather than being re-weighted to 1 on
  no evidence.
  `[verified: unit + mutation — CommunityPlacementPlannerTest 3 new tests covering partitioned members,
  a wholly-absent community, and the unwired default; aether-deployment 832/0, aether/node 868/0.]`

### Verification (2026-08-16)

`02y-stream-crash` and `02w-entity-crash`, `--env remote` on cluster B: **2 suites, 2 passed, 0 failed.**
This is the run that backs the integration-verified claims on the three stream/deploy fixes below. Same
scenario that produced the data loss — 5 nodes, `min-sync-replicas=2`, `docker kill` of the node owning
partitions 0 AND 2 with 40 publishes in flight:

| | before the fixes | after |
|---|---|---|
| deploy to all-instances ACTIVE | timed out at 240s | **3s** |
| ACKED events surviving the crash | 39 of 80 (41 lost) | **80 of 80, 0 missing** |
| non-empty partitions post-crash | 2 of 4 | 4 of 4 |
| 02y suite | FAIL, 327s | **PASS, 85s** |

Not a vacuous pass: the suite's non-vacuity gate confirms 80 ACKED events were actually checked (that
gate exists because an earlier run reported "0 acked, 0 missing" as success).

The three fixes compose — any one alone leaves 02y red. The min-sync barrier makes the ack honest, slot
preemption makes the guarantee *achievable* (the replica can now reach in-sync instead of starving for
4½ minutes), and the remediator fix stops a healthy slice being destroyed while it converges.

**Scope of the claim:** one run. Multi-node with failure injection, which is the feature-catalog bar for
*Integration-verified* — but a single run does not establish the absence of a race.

### Fixed
- **The leader force-unloaded a healthy, serving slice on the strength of a stale in-memory view.**
  `StuckTransitionalRemediator` judged a slice by `Active.sliceStates()` — a leader-local PROJECTION — and
  destroyed it without ever re-reading the authority it mirrors, the committed `NodeArtifactValue` in the
  KV-Store. When the projection missed a transition the slice looked stuck forever, because the map it was
  judged by was the same map that had failed to advance.
  Measured 2026-08-16 (`02y-stream-crash`, remote cluster B): node-2's activation chain SUCCEEDED and the
  slice was serving traffic — node-2's own log records
  `test-stream-multipart-stream-slice/publish depth=0 duration=25.591363ms` at 23:15:15 — yet the leader
  still read ACTIVATING and force-UNLOADed it 35s later at 23:15:50, failing the deploy gate.
  **The activation chain's 120s guard was never the problem, contrary to first reading.** `Promise.timeout`
  arms a one-shot `fail()` against the SAME promise instance and returns `this`; `resolve()` is CAS-guarded,
  so it is a deadline on that instance and a no-op once resolved. It did not misfire — the chain had already
  succeeded, so there was nothing to fire at.
  The remediator now re-reads the committed state before acting. **Fail-safe direction:** remediation is
  skipped ONLY when the KV positively reports a SETTLED (non-transitional) state; an absent key, an
  unreadable value, or a KV that agrees the slice is still transitional all fall through to the previous
  behaviour. So a genuinely stuck slice is still recovered, and the change can only ever spare a slice the
  cluster has already committed as settled.
  **Reachable on an ordinary deploy** — node-4's SIGKILL landed at 23:15:55.833, five seconds AFTER
  remediation, with consensus healthy and quorum intact. The chaos did not cause it.
  This is the same shape as #593's `SYNCING` seed, #508's status field and #590's frozen `memberCount`: a
  local or self-reported value read as observed truth. Not addressed here: the remediator's threshold is
  `3 × 90s = 270s` against the deploy gate's 240s, so it cannot rescue a deploy in time even when correct;
  and ACTIVATING still has no node-local remediation arm of its own.
  `[verified: unit + mutation — ClusterDeploymentStateActiveTest$StaleViewProtection 2/2; bypassing the KV
  confirmation turns the stale-view test red and leaves the still-transitional control green.
  aether-deployment 829/0, aether/node 868/0, ./build.sh green with 0 new lint. integration-verified — see **Verification (2026-08-16)** below.]`

### Fixed
- **A stalled backfill held its reshuffle slot forever, starving the partitions queued behind it.** A slot
  was released only when the partition stopped being a not-caught-up REPLICA — and `PartitionBackfill`
  retries forever once its bounded wait elapses with a committed owner present (the #445 distrust gate), so
  the release condition was exactly the condition that would never become true. Slot tenure was unbounded.
  Measured 2026-08-16 (`02y-stream-crash`, remote cluster B): `entity:orders[4]` and `[6]` held BOTH of a
  node's two slots continuously for 4m55s with **zero** releases in the entire log, while
  `multipart-events[0]` and `[2]` — the partitions that node was the designated replica for — sat queued
  behind them, never became in-sync, and were lost outright when their owner was SIGKILLed. 56 pacing
  defers were logged (29 on partition 0, 27 on partition 2) across ~28 redrive attempts each.
  A slot held past `RESHUFFLE_SLOT_MAX_TICKS` is now preempted: the backfill KEEPS RUNNING and keeps
  retrying, it simply stops counting against the concurrency bound. Preemption is gated on a non-empty
  queue, so a preempted worker is SWAPPED for a waiting one rather than multiplying concurrency — with
  nothing queued the pacing bound is untouched.
  **Trade, deliberate:** this bounds TENURE rather than detecting the stall, so a legitimately slow but
  progressing backfill can also be preempted. Harmless — it continues — and it fixes starvation from any
  cause rather than only the retry-forever one.
  Permit accounting is the subtle part and is pinned by its own test: slot acquisition is idempotent VIA
  `inFlightMaterializations` membership, so a preempted ref that re-entered the acquire path would take a
  SECOND permit while only one is ever released, draining the pool. A preempted-set preserves that
  idempotence.
  `[verified: unit + mutation — StreamReshuffleLifecycleTest$StalledSlotPreemption 3/3; removing the
  preempt step from the reconcile tick turns exactly the starvation and permit-accounting tests red and
  leaves the empty-queue control green. aether-config 333/0, aether-stream 665/0, aether/node 868/0,
  ./build.sh green with 0 new lint. integration-verified — see **Verification (2026-08-16)** below.]`

### Added
- **`[streaming] reshuffle_concurrency` is now a real config key.** It bounds how many partitions one node
  holds in materialize+backfill at once. It was a hard-coded `static final int` with no binding of any
  kind, while the paced-materialization error message named `reshuffle_concurrency` as though it were a
  setting — an operator whose backfills were starving went looking for a knob that did not exist, and had
  no way to raise the bound. Parsed by `ConfigLoader`, rejected below 1 by `ConfigValidator` (0 would stall
  every replica backfill permanently) so it joins the same collected report as every other config error,
  and wired in `AetherNode` BEFORE any materialization since it replaces the permit pool wholesale. The
  paced error now reports the limit actually in force rather than a compile-time constant. The `[streaming]`
  section was entirely undocumented and is now in `configuration.md` with all four of its keys.
  `[verified: aether-config 333/0; ./build.sh green, 0 new lint]`

### Fixed
- **Forwarded stream publishes bypassed `min-sync-replicas`, losing ACKED events on a single node kill.**
  Both writers route on LOCAL RING PRESENCE rather than ownership: `DefaultStreamPublisher.publishEventual`
  and `StreamWriteRouter.publish` each await replication only on the arm where the node already holds the
  partition ring, and otherwise forward to the HRW owner. On the owner the forward landed in
  `StreamForwardHandler.onPublishForward` → `StreamPartitionManager.publishForwarded` → `publishLocal` and
  was acked with **no `awaitReplication` anywhere** — so every forwarded publish acked on the owner's local
  fsync alone, silently running at min-sync 1 however the stream was configured.
  Found by `02y-stream-crash` on remote cluster B (5 nodes, 4 partitions, min-sync-replicas=2): 80/80
  publishes ACKED, then a `docker kill` of the node owning partitions 0 and 2 lost **both partition logs
  whole** — 41 acked events. A replacement node's later cold backfill pulled p0=2/p1=22/p2=1/p3=19 events,
  the surplus being exactly the 5 post-crash liveness writes, proving p0 and p2 held none of the original 80
  anywhere in the cluster. The reads were accurate rather than a probe artifact: survivors answered
  `500 Stream partition is not owned by this node`, not a false empty list.
  The barrier now sits on the OWNER, in `onPublishForward`, because that is where the ack for a forwarded
  publish is produced — one gate covering both writer paths. `awaitReplication` is bounded (immediate
  `NOT_ENOUGH_REPLICAS` when targets < minAcks, else a 5s pending-ack timeout), so gating there cannot leak.
  **This makes the ack honest, not the cluster healthy:** where a replica is starved by
  `reshuffle_concurrency` pacing and never reaches in-sync, such publishes now FAIL instead of falsely
  acking — the same run shows 56 pacing defers (29 on partition 0, 27 on partition 2) with the designated
  replica stuck `SYNCING` for ~4.5 minutes. That starvation is a separate, still-open defect.
  `[verified: unit + mutation — StreamForwardHandlerTest$MinSyncBarrierTests; reverting the production
  change turns onPublishForward_minSyncTwoWithNoInSyncReplica_doesNotAck red and leaves the min-sync≤1
  control green. The tests wire a REAL ReplicationManager over an empty ReplicaRegistry deliberately: the
  NOOP manager's awaitReplication returns success unconditionally, so a test on the default bare
  streamPartitionManager would pass with or without the barrier. aether-stream 662/0, aether/node 868/0,
  ./build.sh green with 0 new lint. integration-verified — see **Verification (2026-08-16)** below.]`

### Changed
- **Durable-entity error surface renamed to entity-centric names (#432).** `DurableEntityError` →
  `EntityError` (symmetric with the sibling `StreamError`), `DurableEntityProvisioningError` →
  `EntityProvisioningError`, `KeyNotFound` → `EntityNotFound`, `KeyAlreadyExists` →
  `EntityAlreadyExists`, `StaleOwner` → `StaleOwnerEpoch`, and `TimerNotFound` gained the
  `TimerToken` so a caller holding several timers can tell WHICH was not found.
  The spec had pinned one set of names and the code shipped another; v0.4.0 closed that by amending
  the spec, and this closes it the other way where the shipped name was the weaker one — spec and
  code now agree, and the book teaches the same surface.
  **The line drawn on what NOT to rename:** the same name for the same CONCEPT across subsystems is a
  feature — `StreamError.NotCurrentOwner` and `EntityError.NotCurrentOwner` say the same thing about a
  partition owner. The same name for DIFFERENT concepts is the defect, and `KeyNotFound` was three
  unrelated things (JWKS keys in `SecurityError`, config keys in `ConfigError`, entity keys). So
  `NotCurrentOwner`, `StaleEpochRead`, `OwnershipNotYetCommitted`, `LinearizableUnavailable`,
  `StorageFailed` and `TimerNotSupported` are unchanged.
  Note the record's simple name IS the wire value (the fixture slice reports
  `cause.getClass().getSimpleName()`), so this changed strings asserted by `DurableEntityForgeTest`
  and the `02w-entity-crash` suite; both moved with it. A Java-only rename would have left two
  green-looking tests asserting a string nothing emits.
  `[verified: ./build.sh green, lint 49/0 new; renamed across 34 files with zero old names remaining]`

### Changed
- **Codec tag space split into a manually-assigned system range and a hashed user range.** Tags are
  VLQ-encoded, so the tag VALUE decides its wire cost (`0..127` = 1 byte, `128..16383` = 2,
  `16384..2097151` = 3), and that is now spent deliberately: **system `0..16383`** for framework and
  Aether protocol types — enumerable, framework-owned, hand-assigned, never renumbered and never
  reused — and **user `16384..2097151`** for slice-generated codecs, hash-derived because they grow
  without bound as applications add types.
  The old scheme put everything in ONE 16256-slot space, which is birthday-bound at roughly 127
  types: with ~100 codec types the collision probability was already ~27%, and it hit for real —
  `AetherValue.EntityCheckpointValue` and `HealthHintWire` both hashed to tag **7612**, poisoning
  `NodeCodecs` static init and erroring 48 unrelated tests, invisibly to the owning module's own
  build. That exact pair is verified to collide under the old derivation and not under the new one.
  Also replaced `String.hashCode()` with FNV-1a: our FQCNs share long prefixes
  (`org.pragmatica.aether.resource.entity.Entity…`) and `hashCode` clusters badly on precisely that
  shape, wasting a space that was already too small.
  **Collisions are now structurally impossible except within a single slice's registry.** The two
  ranges are disjoint, so a slice type can never collide with a system type; and `slice.codec(parent)`
  gives every slice its own registry layered over the shared system parent
  (`DependencyResolver#resolveBridge`), so two slices never see each other's types — including two
  slices in the same blueprint. What remains is one slice's own list, which can include an injected
  slice's request/response types.
  User tags are held in a map rather than a flat array — an array spanning the wide range would be
  ~16MB per slice, and every slice builds its own registry. System tags keep the flat-array index,
  since they carry the cluster's own protocol traffic and are the hot path.
  `[verified: ./build.sh green, lint 49/0 new; the historical 7612 collision reproduced under the old
  derivation and resolved under the new]`
- **System codec tags are hand-assigned rather than hashed — codec tag space Phase 2.** Phase 1 split
  the space; this fills the system half. All **280** framework and Aether protocol types now carry an
  explicit tag in one registry, `org.pragmatica.serialization.SystemTags`, which
  `SliceCodec.deterministicTag` consults before falling through to the hash (unchanged, now named
  `hashedTag`). Generated codecs already called `deterministicTag` at class-init, so pinning is a
  one-line edit to one file: no regenerated code, no churn across the `@Codec` annotations, and **no
  envelope-version bump** (envelope stays 1000).
  **A tag is a wire contract and hashing gave it none of the stability that requires** — it moved with
  a class rename, a package move, or a change of hash function. Two nodes disagreeing about what a tag
  means is undiagnosable corruption rather than a clean failure.
  **Wire cost.** Every one of these types previously sat in the 3-byte user range. 89 of them —
  consensus rounds, SWIM gossip, DHT lookups, KV commands, stream replication and the value objects
  nested inside all of them — now occupy `21..109` and cost **one** byte; the remaining 191 occupy
  `128..1659` and cost two. `110..127` is held free so a future hot type can still be promoted into
  one byte, and `2112..16383` is reserved. `[mechanism: tags are VLQ-encoded, so a tag ≤ 127 is one
  byte and ≤ 16383 is two]`
  **The obligation is enforced, not merely documented.** `SliceCodec.systemCodec()` refuses to build a
  system registry containing a type that fell through to the hash and names it, so the system set
  never has to be rediscovered by hand — grep cannot answer that question (it returns 134 `@Codec`
  annotations against 76 registered types and mixes in test artifacts). `SystemTags` rejects a
  duplicate name AND a duplicate tag at class-init. A rename now leaves its entry unmatched and fails
  the build, which is the intended behaviour: whether the wire identity travels with the name is a
  human decision, not a hash's.
  `[verified: aether/node/src/test/java/org/pragmatica/aether/node/SystemCodecPinningTest.java —
  builds both production system registries the way AetherNode does and asserts every registered type
  is hand-assigned, plus the one-byte-window property;
  integrations/serialization/api/src/test/java/org/pragmatica/serialization/SystemTagsTest.java 6/0;
  852/0 aether/node, 301/0 slice-processor, 40/0 serialization-api; ./build.sh green, 0 new lint]`
  `[design intent — unverified: cross-node interop on the new system tags. Phase 1's live 5-node smoke
  run exercised consensus, membership, KV and one slice; streams, entities and the DHT have the
  densest codec hierarchies and are covered by suites 02y/02w, not yet re-run on Phase 2]`
- **Slice codec tag collisions are reported at compile time instead of at slice load.** The
  slice-processor now derives the tag for every type in a slice's generated codec list and fails the
  build naming both types when two coincide. The registry already rejected this at load
  (`SliceCodec#validateAndSetTag`), which is correct but late — the developer learned it from a
  deploy. The collision domain is one slice's registry, not a blueprint's: each slice layers directly
  over the shared system parent. The processor carries its own copy of the derivation
  (`CodecTagSpace`) because consumers put only `slice-processor` on `annotationProcessorPaths`, so
  reaching `SliceCodec` would drag `serialization-api` and Netty onto every application's
  annotation-processor path; both copies pin the same probe value so a divergence fails a build rather
  than silently degrading the check. `[verified:
  jbct/slice-processor/src/test/java/org/pragmatica/jbct/slice/generator/CodecTagSpaceTest.java 3/0 +
  SliceCodecTest#hashedTag_pinnedProbeValue; ./build.sh compiles every slice in the repo — examples,
  test blueprints and e2e slices — with no collision reported]`

### Added
- **SIGKILL crash-durability gate for durable entities — #345 I3.** New `02w-entity-crash` suite: a
  partition owner is hard-killed (`docker kill`, no graceful hooks) with entity creates in flight, and
  every ACKED entity must read back with its EXACT written value. **Result: 56 acked, 0 missing, 0
  corrupted; 40/40 pre-kill creates ACKED and 16/40 acked across the kill window.**
  `[verified: aether/tests/integration/suites/02w-entity-crash — 1 passed, 0 failed on a 5-node remote
  Docker cluster]` This is the gate Forge structurally cannot provide: every in-JVM stop routes through
  `AetherNode.stop()` → `close()`, which closes the WAL cleanly, so graceful and hard stop are
  durability-EQUIVALENT in-JVM and the crash-mid-fsync boundary is unreachable there (established for
  streams in #431/#508).
  Two non-vacuity gates, both added after they caught real hollow passes: assertions are gated on a
  CONFIRMED kill (an earlier run passed "all 4 ACKED entities survived the crash" when the node-pick
  had fail-fasted and **no kill ever happened**), and on a non-empty ACK set (#508's original shape
  reported "0 acked, 0 missing" as PASS).
  The suite addresses partition OWNERS directly rather than the LB-fronted app endpoint, because entity
  operations are not owner-forwarded (#596); that workaround should be removed when forwarding lands, so
  the suite exercises the product's routing rather than the harness's.
- **Per-node durable-entity checkpoint observability — #345 I3.** `GET /api/entity/checkpoints` and
  `aether entity checkpoints` report, per keyspace this node folds, the number of successful checkpoint
  `writes`, `failures`, and the last offset `checkpointedThrough` for each partition. The surface exists
  because a checkpoint driver that silently stopped had NO other symptom: writes still ack, reads still
  serve, failover still works, and the only consequence — an entity log that is never reclaimed, because
  the retention floor refuses to reclaim anything at or above a partition's committed checkpoint —
  surfaces hours later as disk growth with nothing pointing at the cause. Before this the driver logged
  only FAILURES, so a driver that never ran and a driver that ran perfectly produced identical output;
  `writes` is the positive signal that separates them. Assembled on request from counters the tick already
  maintains, so there is no hot-path cost. The route is LOCAL rather than delegate-routed: each node
  checkpoints only the partitions IT folds, so a delegate's answer would describe a different node's work.
  A partition this node never folded is ABSENT from `checkpointedThrough` rather than reported as `0`
  ("nothing to say about it" and "checkpointed through offset 0" are different claims). Dashboard is an
  explicit DORMANT slot: summing `writes` across nodes answers no operator question — revisit if a
  cluster-wide "stalled checkpointing" alert is wanted, which IS aggregatable.
  `[verified: aether/tests/integration/suites/02w-entity-crash]` — read live as a liveness sensor on a
  5-node remote Docker cluster: `node-1=222w/0f node-2=44w/0f node-4=143w/0f`. Writes must be summed
  CLUSTER-WIDE, because a node hosting the keyspace while folding no partition correctly reports zero;
  a per-node assertion would fail on a healthy cluster.
- **Durable entity state on a fenced, replicated log — #345 I3.** `@DurableEntity` state now survives the
  loss of the node that owned it. Each keyspace becomes a real stream named `entity:<keyspace>` (the same
  coordinate the write fence, linearizable reads and ownership records already key on, so I1's narrow-C
  records needed no migration); a write appends to it through the fenced, fsync-durable, replicated path,
  and the in-memory state is a FOLD of that log which any node can rebuild.
  `[verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/DurableEntityForgeTest.java]`
  — 11/11 on a live 5-node Ember cluster: after the owning node is killed, surviving nodes serve the exact
  written value. The test it replaces asserted the opposite (*"one graceful stop destroyed it
  permanently"*), so the assertion is discriminating by construction.
- **`replication_factor` is honoured rather than refused** (#345 I3). `minSyncReplicas` is DERIVED as
  `min(2, replicationFactor)` rather than configured, so the runtime cannot silently serve a weaker
  guarantee under a stronger name: at `1` a write is durable on the owner alone (survives restart, not node
  loss); at `2`+ a peer holds it before the write acks. Default is 3.
  `[verified: aether/resource/durable-entity/src/test/java/org/pragmatica/aether/resource/entity/DurableEntityConfigTest.java]`

### Fixed
- **A worker community did not dissolve when it lost the core, and the core did not notice it had lost
  the community (#590).** The CP contract at the community tier was unimplemented, not merely
  unvalidated — and it was unimplemented on BOTH sides, which the ticket did not record.
  **Community side:** `writeDissolved()` had exactly two callers, both gated on the community shrinking
  to zero members. That is a membership-shrink mechanism, not a partition response. SWIM is
  intra-community gossip, so a community cut off from the core still sees all of its own members alive,
  the emptiness condition never fires, and it keeps serving — the "rogue autonomous community" the
  contract exists to make impossible. **Core side:** the per-community FSM's "observed live membership"
  read `GovernorAnnouncementValue.memberCount`, a field the community writes about ITSELF. Under
  partition the governor cannot rewrite it, so it does not expire — it FREEZES at its last healthy
  value. The core therefore kept the community `ACTIVE` and kept placing work on nodes it could not
  reach. `GovernorAnnouncementKey` is never removed by anything, and
  `SpokesmanPingLoop.currentReports()` — the one genuinely receipt-based signal the core collected —
  had zero consumers. Both sides blind, in the same way and for the same reason: a status field was
  trusted in place of an observation.
  **One mechanism, both directions.** The leader already broadcasts `ClusterSyncPing` cluster-wide and
  every live node answers; that exchange now carries liveness both ways, with no new wire type. A node
  that has seen no *term-accepted* ping for `timeouts.cluster.core_absence` (default 10s) dissolves
  LOCALLY through `DrainProcedure.initiate(CORE_ABSENCE)` — no consensus write, which is the whole
  point, since announcing dissolve normally means writing `GovernorAnnouncementKey` through the core.
  Pings failing term fencing do not refresh liveness, so a partitioned-away former leader cannot hold a
  community open. Detection is PER NODE and **WORKER-ONLY**, mirroring `QuorumLossDetector`: no
  intra-community coordination, no dependence on the governor surviving, and a partitioned SUBSET
  fences exactly itself. An arm-after-first-ping latch means a node that has never heard the core is
  treated as cold-starting, not isolated — without it every community would dissolve during formation.
  **The worker-only restriction is load-bearing and was learned the hard way.** This first shipped
  wired on EVERY node, which suite 02y caught. Ping dispatch is leader-only
  (`ClusterSyncState`: "a non-leader tick is a no-op") and a broadcast never reaches its own sender, so
  on the core tier the signal is structurally absent twice over: a node that wins an election receives
  no pings ever again, and every survivor of a dead leader receives none until re-election. Ungated,
  both drained after `core_absence`. The fence is now gated by a fail-safe suppressor sampled at FIRING
  time — only a node positively known NOT to be a core member may fire it, an unresolved core view
  reads as suppress, and an unwired gate leaves the detector inert. Core liveness was always
  `QuorumLossDetector`'s job.
  On the core side, `ClusterSyncCollector.sinceLastPongNanos` feeds a `CommunityLivenessView` that the
  FSM consults instead of the self-report; absent members are SUBTRACTED from the reported count rather
  than recounted from `members()`, because the two are independent fields and the common
  `governorAnnouncementValue(governorId, memberCount)` factory leaves `members` empty — recounting
  would have read zero live members for every healthy community.
  **`core_absence` must be strictly less than `community_absence`** (default 20s) and the config load
  REFUSES an inverted or equal pair rather than clamping it. That inequality is the no-double-active
  guarantee: the community stops serving before the core hands its slices to anyone else.
  Observability per the observability-first rule: `coreAbsence` (`armed` / `fenced` /
  `sinceLastPingMs` / `remainingMs` / `thresholdMs`) on `GET /api/cluster/membership`, deliberately on
  that LOCAL endpoint beside the core tier's own quorum-loss fence — a node losing the core is precisely
  the one a leader-forwarded read cannot reach during the incident it describes.
  `[verified: aether/node/.../CoreAbsenceDetectorTest 13/13;
  aether/aether-deployment/.../ClusterDeploymentStateCommunityFsmTest$CoreObservedAbsence 6/6;
  aether/aether-config/.../ClusterTimeoutsAbsenceOrderingTest 5/5. Mutation-checked both ways: making
  the FSM ignore observed absence turns exactly the 2 new-behaviour cases red with all 4 regression
  guards still green, and removing the cold-start latch turns exactly
  `evaluate_noPingEverReceived_neverFences` red with the other 12 green]`
  `[design intent — unverified: the ORDERING under a real partition. Forge is single-JVM and cannot
  sever the cluster network, so "the community stopped serving before the core re-placed its work" is
  believed, not demonstrated, pending a docker/cloud partition run (#367). With an empty `members` list
  only TOTAL isolation of a community is detected, not a partial one — a limit of the announcement
  shape, not of the read side.]`
  **Doc correction this forced:** `known-limitations.md` described dissolve-on-core-isolation as
  awaiting *proof*, implying a built mechanism waiting on a test run. There was no mechanism to prove.
  That page is the designated single source other docs reference, so the wording had propagated as
  "wired".
- **`WorkerCodecs.workerCodecs()` threw on every call.** `SwimConfig` carries `TimeSpan` fields, so
  `SwimCodecs.REQUIRED_TYPES` demands a `TimeSpan` codec; `NodeCodecs` registers one manually and this
  registry never did, so the startup checklist rejected it unconditionally
  (`Required codecs are not registered: org.pragmatica.lang.io.TimeSpan`). It went unnoticed because
  the registry has **no production caller** — it exists for the worker-community tier and nothing
  constructs it yet, so no test and no node ever ran the code. Found by the Phase 2 pinning test,
  which builds both system registries precisely because `WorkerCodecs` carries four sub-registries
  `NodeCodecs` does not (`MutationCodecsNode`, `BootstrapCodecsNode`, `HeartbeatCodecsNode`,
  `NetworkCodecsNode`) — without it those types would have gone unpinned with nothing to say so. The
  registered codec is byte-identical to `NodeCodecs#timeSpanCodec`, since a worker and a core exchange
  these values. `[verified:
  aether/node/.../SystemCodecPinningTest#workerCodecs_everySystemType_hasAHandAssignedTag — red before
  the fix with this exact message, green after; aether/node 852/0]`
- **Auto-heal never replaced a failed node — a static-init-order bug that reported success the whole
  time (#597).** `AutoHealConfig.DEFAULT.maxNodes()` was `null` rather than `Option.empty()`: static
  initialisers run in textual order, and `DEFAULT`'s initialiser reached the `NO_CAP` constant
  (declared 117 lines below it) while that constant was still null. Every provisioning path funnels
  through `NodeLifecycleManagerRecord.capGuardedProvision`, whose first act is `maxNodes.fold(...)` —
  so every auto-heal replacement threw NPE, and **a killed node was never replaced**.
  What made it survive is that nothing reported it: the NPE was swallowed by the scheduler's
  `runGuarded` ("task recurrence preserved"), so the circuit breaker recorded `consecutiveFailures: 0`,
  and `/api/cluster/provisioning` kept reporting `lastReason: NONE_PROVISIONING` — which means "a
  provision is PERMITTED", not "nothing is provisioning". Every gate read healthy while the deficit sat
  unfilled. Observed live at `deficit=1` for 271s in a targeted repro and for **~70 minutes** during a
  full `02w-entity-crash` run, which is what made `restore_cluster_baseline` declare cluster B
  unrecoverable. `max_nodes` is opt-in and absent by default, so the default path is the broken one.
  Distinct from #509, which is deficit-fill firing too EAGERLY; this is it never firing successfully.
  `[verified: aether/environment-integration/.../AutoHealConfigStaticInitTest — 5/5, module 59/0; the
  null was proven directly against the shipped jar before the fix, and both the broken and the fixed
  behaviour were reproduced live on a 5-node remote Docker cluster — killing a non-leader now yields a
  replacement container (`aether-b-node-01kzytd81s…`) at t=41s and the cluster returns to NO_DEFICIT,
  where the same kill previously sat at deficit=1 indefinitely]`
- **Entity state could be deleted by stream retention — #345 I3.** `RetentionEnforcer` is built once per
  node with a single age policy and never reads per-stream config, so an entity's sealed segments would
  have been deleted a fixed interval after its last write, silently destroying the state of any key not
  recently touched. It now takes a `SegmentRetentionFloor`: a segment is reclaimable only when it lies
  entirely at or below the partition's committed checkpoint. Non-entity streams report no floor and behave
  byte-identically to before.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/segment/RetentionEnforcerTest.java]`
  — mutation-checked: removing the floor turns the "expired but above the checkpoint" case red.
- **A node outside a partition's replica set reported a permanent condition as transient** (#345 I3,
  found by the Forge gate). Reads there refused with `FoldInProgress` — a "retry me" message that never
  clears, the exact failure shape I1 exists to prevent. Split into `PartitionNotHeld` (stable: ask another
  node) and `FoldInProgress` (transient: catch-up is running). `[mechanism: holdsPartition is answered from
  whether the partition manager has a ring, not inferred from a replica descriptor]`

- **Cloud/docker crash-durability test for streams — #508, the gate #345's I3 needs.** New `02y-stream-crash` suite asserting that every ACKED event survives a HARD `docker kill` (SIGKILL, no graceful hooks) of a partition owner on a **multi-partition** stream, including events acked concurrently with the kill window, with per-partition offsets contiguous and ordered. **Result: 80 acked, 0 missing; 40/40 publishes ACKED across the kill; all 4 partitions contiguous** — reproduced across four independent runs under four different cluster conditions. Why it cannot be an in-JVM test (empirical, #431): every in-JVM stop routes through `AetherNode.stop()` -> `streamPartitionManager.close()` synchronously, `killNode(graceful=false)` only shortens teardown, and `close()` is durability-neutral, so graceful and hard stop are durability-EQUIVALENT in-JVM and the crash-mid-fsync boundary is unreachable there. Why it cannot use the management API: `/api/streams/publish/{name}` hardwires partition 0 (#524), so it drives the `test-stream-multipart` app-HTTP routes instead — a keyless publish round-robins across all four partitions and the read route takes an explicit `(partition, offset)`, so each log is asserted INDEPENDENTLY rather than hidden in an aggregate. The assertion is deliberately scoped to ACKED events only: under min-sync-replicas=2 the ack IS the durability claim (`PartitionWal.append` -> `force()` before the caller is told "published"), so demanding more would assert a guarantee the system does not make. **Lives in its own suite, deliberately** — on `02-chaos`'s shared cluster the EIGHTH chaos test fails whichever test it is (#593, probable cause #594), and reordering only swaps the victim; on a fresh cluster this test runs green in 63s. Non-vacuity gates were added after a run where a failed deploy made the survival assertion report "0 acked, 0 missing" as a PASS — a durability claim over an empty set, the exact looks-green-proves-nothing shape the test exists to disprove. [verified: `02y-stream-crash` 1/0 with `02-chaos` unchanged at 7/0; eight runs total on the LAN Docker host] [design intent — unverified: the same assertions against real cloud VMs]
- **`[cluster] max_nodes` — the #298 fleet cap is now settable by an operator.** The cap shipped opt-in with no configuration path, so it could only be set from a test. It now traverses TOML -> `ConfigLoader` -> `AetherConfig.Builder` -> `ClusterConfig` -> `Main.resolveAutoHeal` -> `AutoHealConfig.withMaxNodes` -> the guard at `NodeLifecycleManager.provisionNode`. `0`/absent means unbounded, matching `coreMax`'s existing "unset" sentinel in the same record, so no existing config changes behaviour — a numeric default would silently refuse provisioning on any cluster already larger than it, which is an outage on upgrade rather than a guardrail. Cloud operators set it through the `node_config` overlay (`[source.<name>.node_config.cluster] max_nodes = 12`); hand-managed nodes set it directly under `[cluster]`. **This also closed a hop nobody had noticed: `Main` never called `.autoHeal(...)` at all**, falling through to the staged builder's `default build()`, so until now NO auto-heal setting was operator-tunable. Applied as a post-build wither because `autoHeal` sits six stages past `streaming` in the curried builder chain, and reaching it mid-chain would force `Main` to supply six unrelated stages it has no opinion about. Documented in `bootstrap-config.md` with the honest bound and the operator recovery action. [verified: `ClusterMaxNodesConfigTest` 3/3 — parsed when declared, unbounded when absent, coexists with `core_max`; mutation-checked — deleting the `ConfigLoader` parse line turns two of the three red while the unbounded-default test correctly stays green] [design intent — unverified: refusal against a live cloud account]
- **Operator fleet cap refuses provisioning before an unbounded fleet can be minted (#298).** There was no pre-flight bound of any kind on how many nodes a cluster could provision: a runaway reconciler or a bad config could mint an arbitrarily large paid fleet, and nothing refused. The ticket's own fix direction — "call `checkQuota` before bulk provisioning" — was followed to the code and **rejected as unimplementable-as-written on two independent counts**: `QuotaStatus.unknown()` sets `sufficient = true` and every one of the five providers returns exactly that, so a gate reading `sufficient` could never refuse; and `CloudProvider`, the SPI owning `checkQuota`, has **no production consumer at all** — there is no bulk provisioning path, fleets grow one node per call through `ComputeProvider.provision(spec)`. Wiring the ticket as specified would have gated a dead method on a dead path. Instead the cap sits at `NodeLifecycleManager.provisionNode`, the single chokepoint every path funnels through (auto-heal reconciler, bootstrap, CLI wave reprovision), counting the cluster's live instances scoped by the `aether-cluster` tag providers already stamp. **Opt-in with no default** — a default numeric cap would silently refuse provisioning on any existing cluster larger than the number we picked, so absence means unbounded and today's behavior is unchanged until an operator sets `maxNodes`. **A failed count refuses rather than provisions**: an unreachable provider API must not silently disable the guard, which is precisely the looks-wired-does-nothing shape this ticket belongs to. A cap configured without a cluster name cannot be scoped and is reported loudly rather than quietly ignored. Enabling surface: `AutoHealConfig.withMaxNodes(int)`; cluster identity now reaches runtime config via `AetherNodeConfig.withClusterName`, stamped by `Main` from the already-boot-gated `AETHER_CLUSTER_NAME` — the runtime `[cluster] name` that `Main.verifyClusterLabelConsistency` was written in anticipation of. No Management API surface, so the REST→CLI→docs→dashboard quad is not triggered. **Bound honesty:** the check is check-then-act against a live count, so the guarantee is "bounded by `cap` plus whatever was concurrently in flight", NOT "never exceeds `cap`" — it bounds the runaway case, which is sequential reconciler passes, not a deliberate parallel burst. Operator recovery: raise `max_nodes`, or terminate instances until under the cap; provisioning resumes on the next reconcile pass. [verified: `NodeLifecycleManagerCapTest` 5/5 — refusal at the cap, pass below it, other clusters' instances excluded from the count, unbounded when unset, and refusal when the count cannot be read; mutation-checked twice — disabling the refusal branch and dropping the cluster-tag filter each turn exactly one distinct test red] [design intent — unverified: refusal against a live cloud account under a real reconciler-driven provisioning burst]
- **Cloud bootstrap seeds the core quorum only (RFC-0017 stage 7 — the arc's payoff, #581).** `BootstrapPhaseProvision` no longer creates worker or spot VMs for cloud sources: it provisions the cores, publishes the full topology at formation, observes core-quorum formation via provider labels, and exits. The cluster provisions everything else (stage 5) with **live** core peers — eliminating the stale-seed failure mode structurally (a worker's seeds can never age out because they are rendered by a live leader at provision time), collapsing two provisioning mechanisms into one, and making large-topology deploys parallel where bootstrap was serial. `--wait` now means "core quorum formed"; worker convergence is asynchronous and observable via `GET /api/cluster/config` (desired) vs the topology/provisioning-diagnostics surfaces (actual). Stage 4's worker-seed baking became dead code on this path and was **removed rather than left as residue**. Scope: cloud sources only — SSH sources keep fixed-host registration (no cloud API for the cluster to provision through) and Docker sources keep all roles (the integration harness creates its workers at bootstrap). [verified: `BootstrapPhaseProvisionUserDataTest.cloudBootstrapRoles_areCoreOnly` + the full provision/deploy suites green after removing the baking path] [verified: `BootstrapPhaseProvisionUserDataTest.cloudBootstrapRoles_areCoreOnly` + arc-final live Hetzner run 2026-08-09 (cluster `rfc17-final`, assets `fd50dbb69`): provision created exactly 5 core VMs, 5/5 formed via label discovery, 2 workers cluster-minted post-bootstrap with `-r<clock36>` ids and correct `aether-source` labels, joined READY with live core peers — see RFC-0017 §Live validation]
- **`cluster destroy` sweeps cluster-labelled VMs the bootstrap state never recorded (RFC-0017 stage 6 / C3, #581).** Cluster-provisioned nodes — stage-5 workers, auto-heal replacements — are not in `bootstrap-state.json`, so the id-scoped cleanup could never find them: every one was a paid orphan no destroy could reach. Destroy now runs a cluster-scoped VM sweep AFTER the state-based cleanup and before the SSH-key sweep, mirroring the #481 key-sweep discipline: the selector is built from the cluster name (`aether-cluster=<name>`) — **scoped by construction, a bare listing is a stub failure in the tests** — credentials resolve handle-first (never raw `HCLOUD_TOKEN`), the inventory is printed before any delete (#572's lesson: what the sweep sees is what you can lose), and 404s for VMs the first pass already removed are tolerated so destroy stays idempotent. **Ordering is load-bearing:** cores die in the state-based pass first, killing the leader's worker reconciler — swept workers cannot be re-provisioned mid-destroy. **`PROTECTED_CLUSTERS` now lives in the CLI as well** as the reaper script: destroying `test-pg` fails loudly before any provider call is made. The RFC's optional "scale to zero first" phase was dropped as redundant — the sweep deletes every labelled VM regardless, and core-death-first already closes the re-provision race it existed to avoid. [verified: `BootstrapCleanupTest` VM-sweep section — scoped selector pinned (bare `listServers()` throws in the stub), protected-cluster refusal before any client construction, 404 tolerance, blank-name skip; 22/22] [design intent — unverified: sweep against a live account with genuinely cluster-provisioned workers — folded into the arc-final Hetzner run, which the #574 arc's live label mechanics already de-risk]
- **The cluster provisions its own workers and spot nodes from the published topology (RFC-0017 stage 5, #581).** The leader's CTM gains a worker-topology reconcile pass: on every committed `ClusterConfigKey` change (scale, apply, restore) and on leader activation, it compares each non-core `(source, role)` entry of the desired topology against the provider's ACTUAL label inventory (`aether-cluster`/`aether-source`/`aether-role`) and converges — deficit provisions through the same circuit-gated, zone-rotating `provisionReplacement` path auto-heal uses (now genuinely role-aware: the spec's role string and instance type were **hardcoded `"core"`/`"default"`**, survivable only while nothing but core replacements flowed through), surplus terminates newest-first (reconciler-minted `-r<clock36>` ids sort after bootstrap `-<index>` ids, so cluster-provisioned workers are reaped before bootstrap-provisioned ones). Deliberately a SEPARATE, simpler loop from the hardened core `LeaderReconciler`: a worker deficit is never quorum-ambiguous, has no cold-start ambiguity, and inventory ("the VM exists"), not SWIM membership, is the honest ground truth for create/destroy — a created-but-not-yet-joined worker must not be double-provisioned. Leader-gated by the same `active` guard as the membership actuator path and serialized against overlapping passes. `ClusterConfigApplier` now routes EVERY role through the fenced `setDesiredCount` write (the `RoleScaleUnsupported` rejection existed to keep non-core scales from rewriting the core-only scalar — the typed topology removed that hazard, closing #241's worker-provisioning gap), and scale-to-zero is now accepted for worker/spot tiers end-to-end (CLI, REST validation) — drain-all is a real operation and the RFC-0017 teardown path depends on it. Workers provisioned by the cluster boot with LIVE core peers rendered by the shared user-data path — the no-stale-seeds property bootstrap-baked seeds cannot have. [verified: `ClusterTopologyManagerWorkerReconcileTest` (deficit provisions with worker role + scoped filter, convergent second pass, newest-first surplus termination, core-only topology untouched, inactive-CTM gate), `ClusterConfigApplierTest` (all roles route to their own (source, role) pair, mixed diffs land both), `ClusterConfigRoutesScaleTest` (worker scale-to-zero accepted, negatives refused) — mutation-checked: disabling the leadership gate turns the inactive-CTM test red] [design intent — unverified: live worker provisioning on real cloud VMs — the arc-final Hetzner run's fourth deferred check]
- **Cloud cores self-assemble via provider discovery; the SSH re-launch trampoline is gone for the standard topology (RFC-0017 stage 4, #581).** Bootstrap used to SSH into every cloud VM to re-launch it with a finalized `PEERS` list — the only reason cloud nodes needed inbound port 22, and the mechanism a deny-by-default firewall broke first. Now: (1) `Main.parsePeers` gains a discovery arm — after the explicit arms (`--peers=`, `CLUSTER_PEERS`, so operator lists and CTM replacements stay byte-identical) and before config synthesis — that polls `DiscoveryProvider.discoverPeers()` until the expected core count is visible, mapping instances with `aether-role=core` + a create-stamped `aether-node-id` label to seed peers on the **local** cluster port (the `aether-port` label cannot exist pre-formation; every core shares one port by composition); at the deadline a majority proceeds with a warning, below majority the node refuses to form a cluster that cannot reach consensus. (2) The composed node config now carries `[cluster] nodes` — without it a cloud VM resolves as DOCKER and expects 5 cores regardless of topology. (3) Workers and spot instances get core seeds **baked at create**: cores provision first, so worker user-data rendered afterwards carries their real addresses ("peer set arrives at birth" — no discovery, no credentials-dependency for peers, no re-launch). (4) Bootstrap readiness (C4) polls the provider API for `aether-formed=true` — a label each core merges onto itself after formation — instead of polling every node's management port, which a firewalled management port failed on HEALTHY nodes (found live on Hetzner 2026-08-05). **Gate:** engages only when exactly one source carries cores and it is a cloud source (the wizard's only output); cores spread across providers cannot find each other by label, so multi-core-source clusters keep the legacy SSH push, which remains fully test-pinned. **RFC-0017's gap table was corrected from the code during implementation:** `discoverPeers()` had zero production consumers, and `registerSelf` is inert in production (nothing populates `selfServerId`) — the C4 signal deliberately rides the IP-match self-tag that works without it. [verified: `MainDiscoveredPeersTest` (role filter, node-id requirement, local-port mapping, dedupe/sort determinism, majority gate), `BootstrapPhaseDeployFormationLabelsTest` (gate shape, label filter, gradual formation, timeout naming the shortfall, transient poll-failure resilience), `BootstrapPhaseProvisionUserDataTest` (cores boot peerless / workers carry baked core seeds), legacy path re-pinned in `BootstrapPhaseDeployCloudSshRestartTest` (34) + `BootstrapPhaseDeployHealthPollTest` (6); `./build.sh` green, 0 new lint, 1809 tests / 0 failures across aether-config/cli/node] [design intent — unverified: end-to-end self-assembly on live cloud VMs — scheduled for the arc-final Hetzner run; the label mechanics (create-stamp, merge-on-tag, label-selector listing) were proven against the real API in the #574 arc]
- **The KV applier fences lost updates on the cluster config (RFC-0018, #570).** `ClusterTopologyManagerRecord.writeDesiredCount` was an unguarded read-modify-write: two writers that both read config version N both computed N+1, and the second silently overwrote the first — no error at any layer. RFC-0017's typed topology made that materially worse: `--source eu --role core` and `--source us --role worker` are independent, simultaneously-valid edits, and the lost update destroyed one of them outright. A third arm now joins the applier's fence family (H4 leader, ownership epoch): a `Put` of a [`VersionFenced`] value is applied only when its version is the **immediate successor** of the committed one — equal (the second racer) and jumps (stale reads) are rejected; a first write passes. Deliberately NOT the epoch fence, which accepts equality by design (governor reannouncement) and is therefore a regression fence, not a lost-update fence. No new command variant, no codec tag, no wire change — the condition is a property of the value, and `ClusterConfigValue.fenceVersion()` is the existing `configVersion`. **Rejection is made visible, not swallowed:** batch merging hands every submitter the full merged result list (`RabiaEngine.commitChanges`), so the apply result cannot be attributed — instead callers re-read committed state after the apply resolves (the engine runs the local `process` first) and confirm the change they asked for landed. CTM retries from the fresh value (3 attempts, then a typed failure — so a scale racing an auto-heal now converges with BOTH edits intact); the REST scale/apply/upgrade paths return HTTP 409 `VersionConflict` instead of reporting success for a write that did nothing. Racing bootstrap seeds now resolve first-wins instead of last-overwrite. Mixed-version posture: ships ungated like both existing fences — rc-line releases do not support mixed-version co-application (the KV serializer format already diverges between rcs); the GA rolling-upgrade contract must version-gate ALL applier-semantics changes together. [verified: `KVStorePutFenceTest` (fence semantics incl. the equal-version race, notification suppression, and two-store determinism under rejections), `ClusterTopologyManagerDesiredCountCasTest` (retry from fresh value, both racing edits survive, bounded exhaustion fails loudly), `ClusterConfigKVTest` (fenceVersion/successor obligation) — mutation-checked: removing the fence arm turns the fence tests red] [design intent — unverified: behaviour against a live multi-node cluster under a real concurrent scale/auto-heal race]
- **Scaling names a source and a role instead of a cluster-wide core count (RFC-0017 C1, #581).** `POST /api/cluster/scale` now takes `(source, role, count, expectedVersion)`, and `aether cluster scale` takes `--source` / `--role` / `--count`. `--source` is optional: the server infers it when exactly one source declares the role and **refuses, naming the candidates, when several do** — "scale cores to 7" across two core-bearing sources does not say where the new nodes go, and the former scalar answered that by silently overwriting one number. A `(source, role)` the topology does not declare is refused rather than created, so a mistyped source name cannot become a real provisioning target. Quorum arithmetic still applies to `core` only and is evaluated against the resulting cluster-wide total, so scaling one core source to 1 is accepted when another carries 2. `GET /api/cluster/config` now returns `desiredTopology` (per source, per role) alongside the derived `coreCount`, and the dashboard gains a **DESIRED TOPOLOGY** panel that shows it and flags when a scale will require an explicit `--source`. [verified: `ClusterConfigRoutesScaleTest` (inference, ambiguity refusal, undeclared-target refusal, cluster-wide quorum arithmetic, non-core roles unconstrained), `ClusterConfigKVTest` (`sourcesWithRole`, `declares`)] [design intent — unverified: dashboard panel rendering, and the scale path against a live cluster]
- **Ingress firewalls are applied, not just parsed (#574).** `[source.X.firewall] allow_ingress` was parsed, validated, diffed, and scaffolded into user configs by `aether cluster init` — with zero consumers on any provisioning path. On Hetzner that failed **open**: a server created with no firewall association accepts all inbound traffic, so every layer the operator touched confirmed a protection that did not exist. A new `CREATE_FIREWALL` bootstrap phase now turns each source's rules into a standalone Hetzner firewall via `ComputeProvider.openIngress` (create-or-patch, returning the provider resource id), and threads that id into server-create so the rules are in force **before** the instance exists rather than after. `"tcp+udp"` expands to two rules on one firewall (REQ-5.1.8.1); rules not listed are never touched (patch is a union, never a replacement); cluster (8090) and management (8080) ports stay operator-managed (REQ-5.1.8.3); `load_balancer = "elected"` with no firewall block auto-opens `app_http` on TCP+UDP with the spec's warning (REQ-5.1.8.2). [verified: live Hetzner cluster, 3 bootstrap runs / 9 VMs, 2026-08-06 — one labelled firewall per source, `tcp+udp` expanded to two rules, patch confirmed a union not a replacement, no 8090/8080 opened, attachment proven at server-create by three independent observations, idempotent re-run issued zero writes, and enforcement demonstrated end-to-end (a denied port 22 timed out at 6.0s while an allowed 8070 refused in 0.06s); `destroy` removed the firewall (subsequent GET returned 404). Unit coverage: `aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapPhaseFirewallTest.java`, `aether/environment/hetzner/src/test/java/org/pragmatica/aether/environment/hetzner/HetznerComputeProviderTest.java`]
  - **Bootstrap-only.** Editing `allow_ingress` on an existing cluster is still discarded by `ClusterConfigApplier` (#578); re-bootstrap to change ingress rules.
  - `allow_ingress` on AWS/GCP/Azure is now **rejected at pre-flight (PF-23)** instead of silently ignored. Those providers' defaults deny inbound, so the gap failed closed there — operators manage ingress via their own security groups until #463.
- **Getting-started tutorial — every step executed, not asserted.** New `aether/docs/getting-started.md` (install → `jbct init` → Forge → Hetzner bootstrap), written from the 2026-07-24 cold-user dry-run transcript and adversarially re-walked post-fix: all 8 verification markers executed with evidence (pinned + unpinned installs, first-try-green scaffold build, live forge hello in ~30s, and a real 5×cpx32 Hetzner bootstrap run from the bootstrap-config reference example VERBATIM — 7/7 phases, quorum 3/5, ~€0.10). The two live-run discoveries are taught honestly in place rather than papered over: #520 (a `security_mode="NONE"` cluster rejects `artifacts push` — keys are ignored, publication needs OPERATOR/ADMIN) and #521 (`cluster destroy` can strand VMs while exiting 0 — verify in the provider console; `tools/cloud-reaper.sh` is the safety net). Includes a "Tear it down" section and cross-links with "My First Aether Slice".
- **User-facing bootstrap-config reference with a minimal working Hetzner example (#514).** New `aether/docs/reference/bootstrap-config.md` documents the `aether cluster bootstrap` TOML schema (`[cluster]`/`[cluster.core]`, `[source.<name>]` + provider fields, `[operations.*]`, `[runtime.*]`) field-verified against `ClusterBootstrapConfigParser`, plus the three cold-user traps the 2026-07-24 getting-started dry-run hit as tribal knowledge: `security_mode="NONE"` framed honestly as dev/eval with the code-verified production alternative (pre-seeded `AETHER_API_KEYS` env / `[app-http.api-keys.<key>]` node-config — API_KEY mode authenticating from boot 1, no NONE required), explicit `jar_url` pinning when cluster version and published release tag diverge, and the `databases.X` → nested `[database.name]` composition contract. Cross-linked from `cli.md` and `configuration.md`; the stale never-shipped `[cloud.aws]` design doc (`operators/infrastructure-design.md`) now carries a prominent not-implemented banner pointing at the real reference.
- **Stream test-coverage tail — the 2026-07-08 assessment's top-3 pre-GA gaps closed in-JVM (#429, #430, #431).** (1) #429: a multi-partition forge fixture (`test-stream-multipart` blueprint: partitions=4, RF=2, min-sync=2, count-retention, app-HTTP publish/read routes) + `MultiPartitionStreamTest` asserting owner distribution across nodes, per-partition contiguous ordering, and read-path equivalence (owner-local GOVERNOR vs forwarded NEAREST from outside the replica set); app-HTTP-unreachable preference arms documented, not faked. (2) #430: `StreamPublishReshuffleTest` — sustained publishing across a mid-stream owner kill, asserting the precise guarantee "every publish ACKED under min-sync-replicas=2 remains readable, uniquely offset, and per-partition-ordered across a single owner-kill reshuffle" (unacked in-flight publishes may fail — that is the contract, and the test asserts accordingly; add-node-driven migration deliberately excluded while #498 is open). (3) #431: `StreamCrashDurabilityTest` confirmed unblocked by the #499 arc (baseline 2/2 green — the S20-class owner-promotion race is gone) and hardened with failure-path per-node replica dumps + an honest scope javadoc: durability is fsync-before-ack, so graceful full-cluster restart proves the full WAL-replay contract in-JVM; a true no-hooks kill is structurally unreachable (`AetherNode.stop()` runs `streamPartitionManager.close()` on every path) and the crash-mid-fsync boundary is deferred to a cloud docker-kill test (#508). A second class, `MultiPartitionCrashDurabilityTest`, proves the distinct multi-partition surface on the #429 fixture: every fsync-acked event on EVERY partition survives full-cluster restart via **independent per-partition WAL replay** (a bug losing one partition's WAL while others recover is caught per-partition). The #431 investigation also surfaced and deterministically reproduced a reconciler defect — post-restart deficit-fill provisioning empty replacements for stable-id members merely slow to rejoin (#509). Empirical harness limits recorded: shared PER_CLASS clusters churn reliably on their 3rd formation (arm designs must stay within 2), and `jbct:check` does not scan test sources.
- **Per-node local replica-view endpoint + CLI flag (#490).** `GET /api/streams/replicas/local/{name}/{partition}` (RouteTarget.LOCAL, the membership-endpoint pattern) is answered by the RECEIVING node from its OWN `ReplicaRegistry` — never delegate-routed — so the owner-authoritative view (`servedByOwner: true`) is finally reachable over HTTP by querying the resolved owner's management port, and per-node view sweeps become possible for failover diagnosis. The delegate-routed variant structurally could not return it (probe-proven: all 5 ports answered with one identical delegate view), and the docs' previous remedy ("re-query the hrwOwner node") silently didn't work because that query was delegate-routed too — both reference docs corrected. CLI: `aether stream replicas <s> <p> --local`. Dashboard: explicit dormant-slot decision recorded on #494. Route-target + ambiguity structurally tested.
- **DHT resolve-time fallback + read-repair (#428, C2 of the #420 churn-loss class, epic #463).** A quorum-read miss on the replica set now triggers a bounded ring probe (up to 8 non-replica-set nodes, per-probe timeout, best-effort): the first present copy is returned AND read-repaired onto the current replica set via the standard quorum put, with observer events both ways (`ResolveFallbackObserver.onResolvedViaFallback` / loud `onUnresolvedAfterFallback` naming the key — never silent, per the departure-push house pattern). Full-replication mode is a natural no-op (the replica set already spans every node); its stranded-copy story lands with the #420 stage-2 durable tier, under which this whole mechanism deliberately retires from correctness-critical to cache-warmth. Additive Apache-module surface (one wither; existing construction unchanged). (integrations/dht 143/0, five targeted scenarios incl. stranded-copy repair and the probe bound.)
- **LocalStack AWS contract suite (RFC-0016 W5, epic #463).** Ten docker-gated contract tests (compute RunInstances round-trip carrying the full `ProvisionRequest` field set, describe/terminate lifecycle, ELBv2 create/attach, SecretsManager round-trip, discovery via EC2 tag filters) exercising the real AWS provider/client against LocalStack Community — the AWS regression sensor that runs before any live credential exists. Skips cleanly (countable, never errors) without a Docker daemon via a lazy availability gate — the deliberate anti-pattern to #466's eager-`@Container` trap. `AwsConfig` gained an additive `endpointOverride` (factory unchanged, zero external constructor callers). Spot is excluded (LocalStack-Pro-only; covered by unit tests + the live smoke). Known-fidelity caveats documented per surface; the suite's first daemon run is expected to surface two suspected client contract bugs it was built to catch (ELBv2 JSON-vs-Query protocol encoding, missing UserData base64).
- **Persisted cluster-config format gate (RFC-0016 W6, Q1 ruling, epic #463).** The existing top-level `config_version` field is now the document format version, exact-match-gated at the single parse boundary both readers share (the bootstrap parse and the KV-persisted re-parse leaders/CTM perform), with the Q1 named errors: absent or older → re-bootstrap; newer → restore the pre-upgrade persisted state (the binary-rollback-after-upgrade case). No migration ladder ships until a real rung exists (design in RFC-0016 §3.5); future persisted-format changes bump the required version. (aether-config 304/0, all four gate cells tested.)
- **Per-role VM images (RFC-0016 W2, Q6 ruling, epic #463).** Each `[source.<provider>.<role>] image` now provisions that role's own nodes — the bootstrap seed path stamps the role's image onto `ProvisionSpec.imageId` (tier-1 of `resolve()`), and CTM auto-heal replacements get it via the node overlay rendering the node's OWN role's image — with **no implicit cross-role fallback** (a role without `image` resolves via `[cloud.compute]` then the loud stock default). Replaces the interim core-image-stamps-all behavior; existing core-only configs (including every cloud test TOML) behave identically, verified. Mixed runtime×architecture snapshot layouts (#464) are now expressible per role; `vm-snapshot.md` updated to the per-role contract. (aether-config 300/0, cli 536/0, aether-deployment 750/0, environment-integration 54/0, hetzner 71/0.)
- **Provider-agnostic provisioning surface — every provision path funnels through a single non-overridable `resolve()` producing a complete `ProvisionRequest`; providers implement only `createFrom` (RFC-0016 W1, epic #463).** Structurally eliminates the #442/#459 plumb-per-field defect class: field precedence (spec > provider `[cloud.compute]` > explicit policy — fail-loud instance size per #442, loud stock-image fallback per #459) is decided once at the `provision(spec)` SPI boundary, which all three producer sites reach (bootstrap seed via `CloudProviderSupport`, CLI `BootstrapPhaseProvision`, and the CTM auto-heal path — the heal path previously bypassed the spec producers entirely). All five providers ported (Hetzner as the design proof; **AWS/GCP/Azure now consume `instance size` and `image` from the spec — both were silently dropped before**; Docker as the unsized special case), plus the Ember in-JVM provider and the forge fault-injector (chaos interception semantics preserved). `role = spot` maps to a SPOT market request; a provider without a spot product/arm rejects it loudly instead of silently provisioning on-demand. **AWS gains the first real spot arm** — EC2 `InstanceMarketOptions` (market type, max price when set, interruption behavior), with `InsufficientInstanceCapacity` mapped to the same retryable `CapacityUnavailable` that drives zone rotation on Hetzner and `SpotMaxPriceTooLow` terminal; GCP/Azure spot awaits client-surface support and rejects loudly. `integrations/cloud/aws` `RunInstancesRequest` extended additively (both factory signatures unchanged). Reviewed in two staged passes (both approved); per-module green: environment-integration 54/0, hetzner 71/0, aws 55/0, gcp 43/0, azure 50/0, docker 49/0, integrations-cloud-aws 42/0, aether-deployment 756/0, cli 531/0.

### Removed
- **The rest of the dead `CloudProvider` SPI, and `QuotaStatus` with it (#298 follow-up).** The interface's ingress methods were removed earlier (entry below); this removes what was left — `CloudProvider` itself, all five `*CloudProvider` implementations, `QuotaStatus`, and `HetznerCloudProviderTest`, whose only subject was dead code. Evidence: no production consumer (the sole reference outside the interface and its implementations was a comment added hours earlier while implementing #298), no `META-INF/services` registration, no reflective use, and the only construction of any implementation was inside its own test. **It is not part of the published provider SPI**: `cloud-integration-spi-spec.md` enumerates four contracts providers implement — `ComputeProvider`, `LoadBalancerProvider`, `DiscoveryProvider`, `SecretsProvider` — and `CloudProvider` is in none of them; providers register `EnvironmentIntegrationFactory`, and `EnvironmentIntegration` exposes `compute()`, never a `cloudProvider()`. So it was unreachable from the extension surface a third-party provider author sees. The interface had been **superseded in place** rather than merely unused: `supportsPreemptible()` was implemented by all five providers, cited by two spec requirements, and called by nothing — the PF-16 spot gate it documented is really `ClusterBootstrapConfigValidator.SPOT_UNSUPPORTED_REASONS`, a static map keyed on `CloudProviderName`. **This dead surface had already cost real work:** #298 was filed against `checkQuota` and specified a fix that could not function (every provider returned `QuotaStatus.unknown()`, whose `sufficient` flag is `true`, on an SPI with no bulk provisioning path to guard), which had to be disproved from the code before that ticket could be implemented. Spec references corrected in the same commit rather than left to rot: `cluster-bootstrap-spec.md` (REQ-5.1.7.2, REQ-8.2.3, the Phase-2 provisioning row, and two ingress references) and `harness-resilience-spec.md` C3a, which pointed at `HetznerCloudProvider.provision()` in a module `aether/aether-cloud` that does not exist. The `environment-integration` module description also stopped advertising a **cost** facet — that facet was `checkQuota`, and it never functioned. [verified: `./build.sh` green end to end after removal — no caller anywhere in the repo broke]
- **Dead `CloudProvider` ingress SPI.** `CloudProvider.openIngress`/`closeIngress` had no production construction site and no live caller — their only production reference was `SourceProvisioner`, which had no callers of its own. Both removed; ingress now lives on `ComputeProvider`, the SPI the provisioning path actually uses.

### Changed
- **JBCT lint corpus burn-down (#493) — internal, no behavioral change.** The 20 real named fixed-message `Cause` variants that JBCT-SEAL-02 flagged as zero-component records are converted to the per-cause `enum Foo implements X { INSTANCE; … }` idiom (type names unchanged, so `permits` clauses and `case Foo …` type-patterns stay valid; only `new Foo()` → `Foo.INSTANCE`). Same messages and HTTP statuses; four sites also shed redundant hand-rolled singleton/factory machinery the enum's `INSTANCE` supersedes. The `record unused()` sealed-interface placeholder idiom is exempted rule-side rather than churned. JBCT-RET-08 (null call-argument) burned down to zero across the corpus: `TypeMapper`'s PG-type→Java-type table is Option-ified (40 `null` → `Option.empty()`/`present`, its factory taking `Option<String>`), `OutputFormatter.printQuery` gains a private `Option<TableSpec>` core (no caller churn), and the JDK/framework-boundary nulls that cannot be Option-wrapped — `AtomicReference` sentinels, `SSLContext.init`/`KeyStore.load`, reflective static invoke, JMX listeners, Jackson view-DTO absent fields, cloud provider-request DTOs — carry justified `@SuppressWarnings("JBCT-RET-08")` (the rule itself now also exempts the distinctive `orElse`/`compareAndSet`/`getAndSet` adapters). Plus the small residue: JBCT-MUT-01 (2 parameter-reassignments → locals: `JwtSignatureVerifier`, `MavenLocalRepoLocator`) and JBCT-STY-09 (4 nested ternaries de-nested: `AetherSchemaManager`, `ConfigurableLoadRunner`, `DdlAnalyzer`, `SchemaBuilder`). Compile-verified across all touched modules.

### Fixed
- **Chaos-suite poll loop carried an SSH roundtrip per second, turning a 240s soft-signal step into 3176s.** `topology_events_since` discovers CTM-replacement mgmt ports (they publish on ephemeral host ports) via `remote_exec` — an SSH roundtrip plus `docker ps` and one `docker port` per container — and it is called from 1-second poll loops. So the roundtrip sat on a hot path at exactly the moment the host is least responsive: immediately after S19 SIGKILLs three of five nodes and auto-heal begins provisioning. Measured across the #595 confirmation runs, identical code and a clean host each time, suite duration ranged **743s to 4836s**; in the slow run `Drain-trigger log signature present on survivors` alone took **3176s against a 240s budget** — and every branch of that step is a `log_warn`, so 53 minutes bought a signal the test explicitly states is not its contract ("exit-code-2 assertion above remains the hard contract"). Three fixes: (1) the discovery is memoised behind a 30s TTL (`TOPOLOGY_PORTS_TTL`) — ephemeral ports do not change on a 1-second cadence, so nothing is lost and the roundtrip leaves the loop; (2) `wait_for_self_drain_event` now checks its deadline **after** the fetch as well as before — checking only before bounds when an iteration may START, not when the loop ends, so a single slow fetch overshot the budget by however long it took; (3) the timeout message reports **actual elapsed** via `SELF_DRAIN_WAIT_ELAPSED` instead of repeating the budget it failed to honour — it previously read "within 60s" after 3176s, the same shape as the #594 warning that asserted a benign cause it had never checked. [verified: syntax + harness lint 0 new; the cached helper resolves on a clean source] [design intent — unverified: the duration improvement itself, which needs a run on the final code — the runs that exposed this were already in flight when the fix landed]
- **Replica-set view reported a node's OWN row as permanently `SYNCING`/`-1` while it served a complete partition (#593).** `ReplicaRegistry.registerReplica` seeds every descriptor at `SYNCING` / `confirmedOffset = -1` and only `updateWatermark` advances it — driven by acks arriving FROM PEERS (`DefaultReplicationManager.handleAck`). A node never acks to itself, so its own descriptor stayed at the seed value for the lifetime of the partition. Measured on a live 5-node cluster: an owner reporting itself `SYNCING`/`-1` for over three hours while `ownerHeadOffset` was 24, a peer replica was `CAUGHT_UP` at 23, and all 24 events were readable in order via `/api/streams/read`. **The data was never wrong; the row describing it was** — a status surface that lies about a working system, which is why this reads as a durability incident to anyone (or any test) consulting it. `StreamReadRouter.replicaSnapshot` now answers THIS node's own row from local truth (`CAUGHT_UP` at the local ring head) when it holds the partition. Deliberately narrow: only the answering node's own row is substituted — the one row it has authoritative knowledge of; peer rows still come from the registry, where an ack is the honest source and a node cannot vouch for what a peer holds; and nothing is substituted when the partition is not held locally, because absence of a local ring is not evidence about anything. Each of those three properties is pinned by its own test. **Why this survived so long:** the pre-existing test fabricated the missing state by calling `updateWatermark(..., SELF, ...)` by hand — a call production never makes — so it passed continuously against the broken behaviour. Mutation-checked: disabling the substitution turns ONLY the new production-realistic test red, while that fabricating test stays green. [verified: `StreamReadRouterReplicaSnapshotTest` 6/6 incl. three new cases (own row from local truth with no self-ack, peer rows untouched, no substitution without a local ring); mutation-checked; `./build.sh` green] **NOT fixed here:** `servedByOwner` returned `false` when the same query was issued directly to the owner, in a payload whose own `isHrwOwner` was `true` for that node — so `selfNodeId` and the descriptor's `NodeId` disagree while naming the same node. That is a separate identity-comparison question, not reproducible without a live cluster in the failed state, and #593 stays open for it rather than being closed on a speculative change.
- **`restore_cluster_baseline` never actually rescaled the cluster — the harness was sending a pre-#581 scale payload (#594).** `POST /api/cluster/scale` took `(source, role, count, expectedVersion)` from RFC-0017 C1 onward, but `scale_cluster` still sent `{"coreCount":N,"expectedVersion":0}`. Every call returned **HTTP 500** (`Type mismatch: expected int, got unknown at ScaleRequest["count"]`) and was swallowed by a WARN reading *"cluster may already be at target — proceeding to wait"* — so the cleanup that is supposed to return a chaos-churned cluster to `NODE_COUNT` did nothing, on every test, in every suite, since #581 landed. Observed on all 8 tests of 9 consecutive cluster-B runs before anyone read the body. Fix is the payload (`role`/`count`; `source` deliberately omitted so the server infers it — it does so when exactly one source declares the role, which is every docker/remote cluster the harness builds). **`expectedVersion: 0` is correct and stays**: `checkVersionAsync` treats 0 as an explicit bypass sentinel (`expectedVersion != 0 && ...`), the same one `aether cluster bootstrap` uses — an earlier draft of the issue wrongly claimed this needed a version read and 409 retry. **Both warnings were also rewritten**: they asserted a benign cause ("may already be at target") that nobody had verified, which is precisely why the failure survived so long — a warning that guesses at its own cause hides more than one that prints the status. They now state that the cluster was NOT resized and carry the HTTP status and body. Note for scope: the desired count was already at target in these runs (`previousCount: 5 -> newCount: 5`), so this fix restores the mechanism rather than proving it resolves the downstream churn tracked in #593. [verified: live remote run — `Scale result: HTTP 200 {"success":true,"role":"core","previousCount":5,"newCount":5,"configVersion":2..6}` across every test, where nine prior runs produced only 500s; 02-chaos 7/0] Process note: #581 updated REST, CLI, docs and dashboard per the QUAD invariant, but the integration harness is a FIFTH consumer of the same contract and was not updated — worth folding into that invariant.
- **Cloud cost guardrails were decorative; both are now real (#298 follow-up).** Two independent defects in `aether/tests/cloud/`. (1) **`MAX_CLOUD_HOURS` did not abort** — the block labelled `# --- cost guard ---` printed `WARNING:` and fell through, so a cluster past its budget kept accruing cost AND kept being used; the limit had no effect at all. It now exits non-zero, leads with the teardown command (aborting stops this run extending the spend but does NOT reap — the cluster keeps costing until torn down, and that limitation is stated rather than implied), and treats `MAX_CLOUD_HOURS=0` as the explicit opt-out. Auto-teardown is deliberately not performed: a cluster over budget may be one someone is debugging, and destroying it from a guard is worse than the overspend it prevents. (2) **The cost estimate ignored fleet size AND instance type** — `driver_cost_estimate()` was the literal `echo "0.071"` and the summary computed `elapsed x rate`, so a 100-node run reported the cost of ONE node, understating by exactly N. `driver_cost_estimate [instance_type]` now returns a per-node-hour rate from a dated EU table, and `teardown-cloud.sh` multiplies by the fleet size read from the live label selector **before any deletion** (afterwards the selector returns 0 and the estimate silently collapses back to single-node cost — the same bug in a new place); auto-provisioned replacements are therefore counted. Unknown instance types fall back HIGH and warn on stderr, because under-reporting is the defect being fixed and a silent low default would reintroduce it. Worth recording that the old code was wrong TWICE in opposite directions — missing the `x N` factor while carrying a rate ~9x too high for the `cx22`/`cx23` class actually used — which is why the error was never obvious: a 2h 100-node `cx23` run reported EUR 0.14 against a true ~EUR 1.50. The driver contract in `lib/cloud-driver.sh` now documents the signature and the caller's obligation to multiply. [verified: `driver_cost_estimate` exercised per type incl. the unknown-type warning path, and the teardown arithmetic checked against a 2h/100-node case] [design intent — unverified: the abort path against a genuinely over-budget live cluster]
- **`[operations.auto_heal]` in bootstrap config does not reach a running node (found 2026-08-12, #298 wiring).** `enabled` / `retry_interval` / `startup_cooldown` are parsed and validated into `AutoHealSpec` -> `OperationsConfig`, but nothing renders them into the composed per-node `aether.toml`, so every node runs `AutoHealConfig.DEFAULT`. An operator setting `retry_interval = "30s"` — as the documented reference example does — changes nothing. Only `max_nodes` has a delivered path today (via `node_config.cluster`, above); the remaining three fields are still inert. Recorded in `bootstrap-config.md` beside the section rather than silently left to mislead. Not fixed here: whether the remaining fields get a `node_config` path or the section is deleted like the `CloudProvider` SPI is a scope decision, not a bug fix.
- **SWIM's cross-cluster ANNOUNCE gate was inert on every node; it is now armed, and upgrade-safe (#298 follow-up).** `SwimProtocol.handleAnnounce` has always compared the announced cluster name against the configured one — but `SwimConfig.DEFAULT` carries `""`, empty means "no gating", `fromTimeouts` inherited that default, and nothing ever set it. So the guard existed, was wired, was cited by specs, and rejected nothing. It is now fed from `AetherNodeConfig.clusterName` (stamped by `Main` from the boot-gated `AETHER_CLUSTER_NAME`). **This is the only cross-cluster ANNOUNCE isolation** — the transport's `isAnnounceAllowed` is a per-source RATE LIMITER, not an allowlist — and accepting a foreign ANNOUNCE clears tombstones and introduces the sender as an observed member. **Scope stated honestly:** `announceJoin` targets only a node's own configured seeds, so this is not protection against an arbitrary hostile sender; it catches a stale or copy-pasted seed list pointing at another cluster's addresses — the wire-level counterpart to `Main.verifyClusterLabelConsistency`'s Docker-label check. **Arming it required a fix to the comparison itself:** the original condition rejected any announced name differing from the expectation, INCLUDING an empty one, so a named node would have rejected an un-upgraded peer and broken membership through any mixed-version window. The gate now requires BOTH sides to claim a name before a mismatch is possible, which keeps it inert until every node is named and makes the transition a no-op in both directions. Empty stays "did not tell us", never "mismatch". In-process harnesses (Ember/forge) pass no cluster name and are unaffected. [verified: `SwimAnnounceClusterGateTest` 4/4 — match admitted, mismatch rejected, and both rolling-upgrade directions admitted; mutation-checked twice, each killing exactly one distinct test — reverting to the original single-sided condition turns the unnamed-sender upgrade test red (empirically confirming the hazard rather than asserting it), and disabling the gate turns the mismatch test red] [design intent — unverified: rejection observed on a live multi-cluster deployment]

- **The operator's topology never replaced the bootstrap self-seed; the config-apply route reported success for a write the fence had rejected (#581).** Three compounding defects in `ClusterConfigRoutes`: `handleApplyConfig`'s `.orElse` could not distinguish the lookup's NOT_FOUND from a failure raised inside `processApply`, so every apply refusal was silently re-run as a first-time store; the BootstrapModule self-seed carries `tomlContent=""`, which cannot parse, so every apply against a freshly formed cluster failed at exactly that point; and `storeInitialConfig` issued a bare unfenced `Put` and answered success unconditionally while the RFC-0018 successor fence rejected it silently — the precise trap `storeFencedConfig`'s own doc describes. Net effect on a live cluster: cloud bootstrap exit 0, worker topology gone with a 200, RFC-0017 stage-5 provisioning inert. The route now decides presence on the Option, replaces a blank-toml seed as a confirmed fenced successor at version+1, and funnels the initial store through the same confirmed write path. [verified: `ClusterConfigRoutesApplyTest` (10 tests incl. a fence-modeling KV store; per-defect mutation probes go red) + live Hetzner runs 2026-08-09 — pre-fix: committed config byte-unchanged after a "successful" apply; post-fix: configVersion 2 with both topology entries, twice]
- **CTM-minted VMs were invisible to the CTM's own inventory, and a reconcile trigger landing mid-pass was dropped (#581).** `ProvisionContext.forReplacement` hardcoded `sourceName="default"`, so every cluster-provisioned VM carried `aether-source=default` while the worker reconcile pass lists ACTUAL with `aether-source=<entry.sourceName()>` — `actual=0` forever: each pass minted `desired` MORE workers (measured: 6 for desired 4) and scale-down was structurally impossible (no victim ever visible). Separately, the promised "re-poke once at the end" was not implemented, so a scale committed during a pass was lost until an unrelated commit. The provision path now threads the topology entry's real source name (core auto-heal resolves it from cluster config via the same lookup that yields instance type), and a missed trigger is recorded and replayed as exactly one follow-up pass. [verified: `ClusterTopologyManagerWorkerReconcileTest` (12 tests, label-faithful fake provider — the old fake filtered on role alone, which is why the defect survived its own suite) + live Hetzner run 2026-08-09: scale 2→4 minted exactly +2, 4→1 terminated exactly the newest 3 in ~15s, 0→2 and 2→3 exact]
- **Firewall presets opened the cluster port as `tcp` — the cluster transport is QUIC, which is UDP.** Behind Hetzner's deny-by-default ingress, inbound QUIC on the cluster port was dropped and no core could ever dial a peer: two full live bootstraps failed at the formation gate with `0 of 5 cores reported formation` while discovery and SWIM (whose UDP rule existed) worked. `FirewallPresetsTest` had pinned `"tcp"` as the requirement — the exposure encoded as the spec, the same failure shape as the `0.0.0.0/0` finding before it. Standard and restrictive presets now emit `udp` for the cluster port. [verified: live Hetzner runs 2026-08-09 — `in tcp 6000` firewall: 0/5 formed twice; `udp` rule added: 5/5 formed in one pass, four consecutive formations since] [mechanism: QUIC is defined over UDP — a TCP allow rule cannot admit it]
- **`aether cluster scale` never worked, and no test could see it (#581).** The CLI posted `{"count":…,"role":…,"source":…}` while the route's DTO read a lone `coreCount` (`ManagementApiResponses.ScaleRequest`), so every scale request was **rejected at deserialization** — the decoder reports `Type mismatch: expected int, got unknown … ["count"]`, since the record's required `count` is absent from the body — and `source`/`role` were discarded on the floor. The DTOs live in `aether/node` and `aether/cli` does not depend on that module, so the wire contract was spelled twice, as a Java record on the server and a hand-built JSON string on the client, with nothing tying the spellings together. The only existing `CLUSTER_SCALE` tests assert which node the route dispatches to, which stays green either way. The request record is now `(source, role, count, expectedVersion)` and the CLI sends exactly those names. **This is one instance of a wider gap:** 18 CLI files hand-build request bodies with no compile-time tie to the server record; only moving the request DTOs into a module both sides depend on would make a drift like this a compile error. [verified: `aether/node/src/test/java/org/pragmatica/aether/api/ScaleRequestContractTest.java` + `aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/ClusterScaleCommandTest.java` + arc-final live Hetzner run 2026-08-09 — `aether cluster scale --role worker` executed against a live 5-core cluster for counts 4/1/0/2/3, each answering explicit `previousCount`/`newCount`/`configVersion` and converging on the provider within seconds]
- **CLI-side quorum validation was arithmetically wrong under per-source topology.** `aether cluster scale` checked that the requested count was odd and ≥ 3 before sending. That is a property of the CLUSTER-WIDE core total, not of one source's count: scaling one core source to 1 is legal when another source carries 2. The check moved to the server, which is the only party holding the whole topology. [verified: `ClusterConfigRoutesScaleTest.validateScale_checksClusterWideCoreTotal_notThePerSourceCount`]
- **Firewall teardown failed on the first `cluster destroy` attempt.** Hetzner server deletion is asynchronous: `deleteServer` returns before the server is detached from its firewall, so the immediately-following delete got `422 resource_in_use` and destroy reported failure for a firewall that was seconds from deletable — leaving the operator to re-run destroy by hand. The delete now retries while the servers drain, and still fails loudly if the firewall is genuinely stuck. Found on a live Hetzner run; the unit test could not have caught it, and in fact *forbade* the discovery by asserting no other client call was made. [verified: live Hetzner run 2026-08-05 (first attempt 422, retry deleted it — API returned 404 afterwards) + `BootstrapCleanupTest.cleanup_retriesFirewallDelete_whenStillAttachedFromAsyncServerDeletion`]
- **`aether cluster init` put the management API on the public internet by default (#580).** The `STANDARD` preset — the wizard's default — emitted `rule(MGMT_PORT, "tcp", "0.0.0.0/0")`, and `adminCidr` defaulted to `0.0.0.0/0` when the operator gave none, so an absent admin network silently meant *everyone*. Combined with the documented cloud example's `security_mode = "NONE"` that is unauthenticated remote control of the cluster. Harmless only while `allow_ingress` was inert; live the moment #574 made it apply. Presets now scope the management API and bootstrap SSH to the operator's admin CIDR and **omit those rules entirely when none is given**, never widening. New pre-flight **PF-24** rejects the management port on `0.0.0.0/0` together with `security_mode = "none"`. Wizard ports now derive from `PortMapping.defaultPortMapping()` instead of a second spelling that had drifted to 7100/7200. [verified: `FirewallPresetsTest`, `ClusterBootstrapConfigValidatorTest` — mutation-checked; the previous suite asserted `rulesFor_standard_allRulesUseAnyCidr`, encoding the exposure as the requirement]
- **Provisioning could create a VM no cleanup path could ever find (#579).** `clusterNameOrDefault` fell back to the literal `"unknown"`, so a server whose cluster could not be resolved was stamped `aether-cluster=unknown` — invisible to `aether cluster destroy --cluster X` and to every scoped sweep, leaving a billable orphan only an account-wide reap would catch, and account-wide reaps are what destroyed the standing `test-pg` VM (#572). Provisioning now refuses outright; the `AETHER_CLUSTER_NAME` fallback is retained for the genuine pre-bootstrap window. RFC-0017 C2 — a precondition for cluster-owned provisioning, where the label is teardown's only handle on a node. [verified: `HetznerComputeProviderTest.ClusterLabelPreconditionTests` — mutation-checked]
- **The bootstrap readiness gate blamed cloud-init for a firewalled management port.** `waitForCloudInit` never inspects cloud-init — it polls `http://<public-ip>:<management>/health/live` on every node. With a declared `allow_ingress` (deny-by-default) and REQ-5.1.8.3 keeping the management port operator-managed, the gate cannot reach healthy nodes, and reported `Cloud-init did not finish on N node(s). Investigate /var/log/cloud-init-output.log` — sending diagnosis to a host that teardown then destroys. The message now names the port actually polled and offers blocked ingress as the likeliest cause, and pre-flight warns when `allow_ingress` omits the management port. [verified: live Hetzner run 2026-08-05 — from inside the host `curl localhost:8080/health/live` returned **HTTP 200** with the aether-node JVM running, while the same URL from outside never connected; two independent runs failed identically, so this is deterministic, not a flake]
- **`cluster destroy` could never succeed after a partial failure.** Retrying re-terminated VMs the first pass had already deleted; Hetzner answered `404 not_found`, cleanup counted it as failure, and the cluster registry entry stayed `KEPT` forever. Hetzner's terminate now maps 404 to `InstanceNotFound`, and teardown treats an already-absent VM as the outcome it wanted — while a genuine termination failure still surfaces. [verified: live Hetzner run 2026-08-05 (`destroy exit=0`, `Registry entry: removed`) + `BootstrapCleanupTest.cleanup_treatsAlreadyGoneVm_asDestroyed`, mutation-checked]
- **`allow_ingress` without port 22 silently locked bootstrap out of its own nodes.** Ingress is deny-by-default and `DEPLOY_RUNTIME` deploys over SSH, so a firewall that omitted 22 provisioned three VMs correctly and then failed `SSH preflight failed: 3 host(s) unreachable after 300s` — the firewall working as designed. Pre-flight now warns (not errors: a pre-baked image may need no inbound SSH), and the reference documents it. [verified: live Hetzner run 2026-08-05 — port 22 timed out at 6s while allowed port 8070 refused in 0.06s, proving enforcement rather than a network fault]
- **Wizard-generated firewall rules were dropped at parse.** `ClusterBootstrapConfigParser` gated the whole `allow_ingress` lookup on an explicit `[source.X.firewall]` section existing, then fell through to the array-of-tables form only inside that branch. `aether cluster init` writes the bare `[[source.X.firewall.allow_ingress]]` form with no `[source.X.firewall]` header — so every wizard-generated firewall block parsed to zero rules while the file plainly contained them. A second inertness layer beneath #574: even once rules were applied, the ones the wizard produced never reached `SourceProfile`. Both spellings now parse. `aether cluster init` also no longer scaffolds `allow_ingress` for providers that cannot apply it (AWS/GCP/Azure) or source types with no cloud API (SSH), which would otherwise generate a config its own pre-flight rejects. [verified: `ClusterConfigGeneratorTest.generate_cloud_firewallRulesSurviveRoundTripToSourceProfile` — caught only because the round-trip assertion was checked against a mutation and found vacuous]
- **`cluster destroy` deleted no firewalls and reported success anyway.** `BootstrapCleanup`'s firewall arm printed `Deleting firewall rule ...`, issued no API call, and returned success — so teardown logged `Cleaned up ...` for a resource that still existed and still cost money. It is now a real id-scoped delete against the Hetzner API. Deletion is scoped by recorded resource id alone, never a label sweep — an unscoped reap is what destroyed the standing `test-pg` VM on 2026-08-03 (#572). Created firewalls also carry `aether-cluster`/`aether-source` labels so `tools/cloud-reaper.sh` can see them. [verified: `BootstrapCleanupTest.cleanup_deletesFirewall_whenCloudFirewallResourcePresent` — mutation-checked: reverting to the old stub turns it red]
- **The Management API no longer grants ADMIN to every caller when no credentials are configured (#573).** On a node where `[app-http] enabled` was unset or false — **the default** (`ConfigLoader.java:314`) — the management plane authenticated nobody and authorized everybody. The chain: `Main.java:115-116` applies `.filter(AppHttpConfig::enabled)` and, when app-HTTP is disabled, discards the ENTIRE parsed `AppHttpConfig` — including a `SecurityMode.API_KEY` posture and any configured keys — substituting the no-arg default, which is `SecurityMode.NONE`. `AetherNode` then selected a validator that did not merely skip validation but returned a context holding `Set.of(Role.ADMIN, Role.SERVICE)` for every request. The `kvStoreAwareValidator` wrapper did not rescue it: `KvStoreApiKeyValidator` consults its delegate first and returns on success, so an unconditionally-succeeding delegate **short-circuited the cluster's KV-held bootstrap admin keys entirely** — meaning #290's own bootstrap-admin-key mechanism was bypassed on exactly the nodes that needed it. No warning was logged at any point. #290 made the config default secure (`ConfigLoader.java:324` → `API_KEY`); this defeated it one layer above, before it could take effect. **Not a rename-only change:** the permissive validator is retained as `permitAllValidator` — honestly named, since "no-op" reads as harmless and an unconditional authorization grant is the opposite — and remains correct for the two callers that have already decided authentication is unwanted: Forge's local dev websockets (which pass `authRequired=false`) and `AppHttpServer`'s `SecurityMode.NONE` arm, which refuses auth-requiring routes at `AppHttpServer.java:765` **before** any validator is consulted. That asymmetry is why the app plane was safe while the management plane was not, and why the fix is scoped to the management plane rather than to the shared helper. The management plane now uses `denyUnlessPublicValidator`: public routes pass with an EMPTY context, everything else is refused with `NO_VALIDATOR_CONFIGURED`. Returning a FAILURE rather than an empty success is deliberate and load-bearing — it is what lets the KV bootstrap-admin-key path run, so a cluster with a registered admin key still authenticates normally while a cluster with no credentials anywhere refuses privileged routes instead of granting them. A startup `WARN` now names the condition. **Scope:** the shipped Docker image sets `[app-http] enabled = true` (`aether/docker/aether-node/aether.toml:16-17`) and was never exposed; this affected bare-JVM runs, hand-written `aether.toml`, and the bare-metal getting-started path. **Evidence [verified: `aether/node/src/test/java/org/pragmatica/aether/http/security/SecurityValidatorAuthorityTest.java`]:** 5 tests pinning the two validators apart — privileged and api-key routes refused, public routes passing with no roles, the fail-don't-succeed property that preserves the KV path, and `permitAll` still granting for its legitimate callers. Mutation-checked: reverting `denyUnlessPublicValidator` to the permissive grant turns 4 of the 5 red (including `Expecting empty but was: [Role[value=service], Role[value=admin]]`), with the source restored md5-identical afterwards. 818 node tests green — **nothing depended on the implicit grant**, so it was pure exposure rather than a load-bearing behaviour. `build.sh` green. **Deliberately NOT changed:** `[app-http] enabled` still governs management-plane security. Decoupling them is the real structural fix and is left as a separate, explicitly-decided change — one flag governing two planes is the underlying design defect, and correcting it alters config semantics for existing deployments. **Found by** a GA readiness sweep for present-but-inert surfaces, not by any existing ticket.
- **Two blueprints can no longer migrate the same physical database (#566).** `aether_schema_history` is a fixed, unqualified table name — one per PHYSICAL database — so the invariant is *one migrating blueprint per physical database*. The existing publish-time check keys on the datasource name derived from the JAR path (`schema/` → `database`, `schema/<n>/` → `database.<n>`), which **under**-approximates that invariant: two node config sections (`[database.a]`, `[database.b]`) can point at the same host/port/database, and both blueprints were permitted to migrate it, interleaving their version sequences in one shared history table. A new fixed single-row claim table `aether_schema_owner(blueprint_base)` — created `IF NOT EXISTS`, dialect-independent, never evolved (same posture as `aether_schema_history_meta`) — records the owning blueprint IN the database being migrated. `AetherSchemaManager.migrate`/`undo`/`baseline` claim it immediately after `bootstrap` and **before** `queryApplied`, so a database already claimed by a different blueprint is refused with a typed 409 (`SchemaError.PhysicalDatasourceOwnershipConflict`) having applied nothing. Ownership compares on `ArtifactBase` with the version stripped, matching the publish-time rule: `my-app:1.0.1` advancing over `my-app:1.0.0`'s records is the same owner, not a conflict. **The ticket's headline claim was wrong and is NOT implemented:** it reported the name-based check also *over*-rejects, on the premise that two blueprints could declare `[database]` sections pointing at different physical databases. They cannot — a blueprint's `resources.toml` is *"intentionally NOT published to KV"* (`BlueprintService`), and at migration time `DatasourceConnectionProvider` resolves the datasource name as a **node** config-section key against KV-overlay ⊕ node.toml. The derived name IS the config section, and the config section IS the physical database, so refusing a second blueprint that selects the same section is correct. The publish-time check is unchanged. **The proposed location was also unusable:** `aether_schema_history_meta` is documented "never itself evolved" and created `IF NOT EXISTS`, so a new column would reach fresh clusters and silently skip every existing one — hence a separate table. **Evidence [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/schema/AetherSchemaManagerResumeTest.java` — `OwnershipClaim`]:** 7 tests covering first claim, idempotent re-claim, re-claim by a different version of the same base, refusal across bases, refusal after a real prior migration, and the 409 mapping; 798 module tests green, `build.sh` green, 0 new lint findings. The ordering test is mutation-checked — moving the claim after `queryApplied` turns it red. **That test required a stronger assertion than "no history rows written":** the refused claim short-circuits before `executeMigration` either way, so a row-count assertion passes against the mutation and proves nothing; the invariant pinned is that a refused claim does not even *read* the history table. **Not verified [design intent — unverified]:** behaviour against a live multi-blueprint cluster — the evidence is an in-memory connector model, not integration. `undo`/`baseline` have no production call sites, so their claim paths are compile-verified only.
- **Client errors on `/api/deploy` no longer answer HTTP 500 (#569).** Deploying a blueprint the cluster does not hold returned `500 Internal Server Error` — measured live on a 5-node remote-host cluster: `{"title":"Internal Server Error","status":500,"detail":"Blueprint not found: …url-shortener:1.0.1"}`. It told the operator the cluster had broken when in fact the request named something that does not exist. `ProblemResponses.resolveStatus` tests `cause instanceof HttpStatusAware` and defaults everything else to 500; `DeploymentError`'s variants were well-typed but carried no status, and `DeployRoutes`' validation failures were bare `Causes.cause(...)` constants. The information needed to answer correctly already existed and was discarded at the HTTP boundary. `DeploymentError` now implements `HttpStatusAware` (`BlueprintNotFound`/`DeploymentNotFound` → 404; `DeploymentAlreadyExists`/`NoCurrentVersion`/`SameVersionDeployment`/`NOT_ACTIVE` → 409; `NOT_ASSIGNED` → 503, since the caller did nothing wrong and another node may serve it; `INVALID_STRATEGY_CONFIG` → 400; `ConsensusFailure` stays 500), new `DeployRouteError` carries the four request-validation failures at 400, and `CanaryStage`'s range checks carry 400. `DEPLOY_STATUS`'s not-found is now minted per request so the ProblemDetail names *which* deployment was missing, where it was previously an id-less constant. Following `SchemaRouteError` (#542), **`httpStatus()` is left abstract rather than defaulted to 500** — a default would let the next variant silently inherit the very 500 this change removes, so an omission is now a compile error. **Typing the causes was NOT sufficient, and this is the substance of the fix:** `buildParsedRequest` combined four validations with `Result.all`, which replaces the emerging cause with `Causes.composite(...)` as soon as *any* input fails — and `CompositeCause extends Cause` only, so the mixin was erased one hop before the funnel and the 500 returned. An unrecognized strategy stayed 500 with `INVALID_STRATEGY` correctly typed 400. `parseCanaryConfig` had the same erasure through `Result.allOf`. Both are now first-failure-wins chains, which forward the original cause instance untouched; accumulation bought nothing anyway, since `CompositeCause` renders one opaque message the caller never saw broken down. `CompositeCause` itself is deliberately **not** made status-aware — it lives in `core`, which is HTTP-free. **Evidence [verified: `aether/node/src/test/java/org/pragmatica/aether/api/routes/DeployRouteStatusTest.java`]:** 14 tests that drive the REAL `Route` handler and feed the emerging cause through the exact `ProblemResponses.writeProblem` call `ManagementRouter.writeError` makes — the cause-level assertion passes throughout while the wire answers 500, so route-level is the only level at which this is provable. Includes unwrapped-propagation assertions (records compare by value, so a wrapper, a re-type or a message drift all fail) and a load-bearing negative control: `ConsensusFailure` must still answer 500, without which the 404s could equally be explained by a blanket downgrade to 4xx. Mutation-checked: restoring `Result.all` turns exactly the 4 strategy tests red with `Composite:` visible in the actual values, leaving the other 10 green. 813 node + 208 aether-invoke tests green, `build.sh` green, 0 new lint findings. **Not re-probed live** — the running cluster predates the fix, and the route-level proof covers the hop where the defect lived.
- **A self-fencing node's `SELF_DRAIN_INITIATED` event now actually reaches the cluster (#565).** The ticket blamed a race — `Runtime.halt(2)` beating the emit. **That premise is wrong:** the exit runs only after tracker-drain and departure-push settle or a 30 s grace expires (observed ~20 s), so no microsecond race is credible. The event never reached the publisher at all. `AetherNode` wired the drain emitter to `ClusterEventAggregator.emit`, which applies the **owner** gate — a *different* gate from the leader gate the call site's own comment rules out ("NOT leader-gated — the draining node is the only authoritative source for 'I am self-draining'"). A self-fencing node is by definition not the owner of the cluster-events partition: reconciles are suppressed while consensus is PASSIVE, so it evaluates the **pre-partition** placement, whose partition-0 owner is typically among the nodes it just lost. Both survivors answer `false`, the event is dropped, and the suppression logs at **DEBUG** — invisible at default INFO, which is why this survived. The one event that explains why a node left was the one event guaranteed not to be sent. The wiring now uses `emitLocal`, the owner-gate-bypassing path built for exactly this class of per-node fact. **The codebase had already documented the correct behaviour in four places** — `ClusterEvent.StreamMemoryExceeded` and `ClusterEvent.DeparturePushIncomplete` both cite `SelfDrainInitiated` as their precedent for "NOT leader-gated", `emitLocal`'s own contract says it mirrors "the `SelfDrainInitiated` not-leader-gated contract", and the budget test asserts NOT_OWNER emission "(mirrors SelfDrainInitiated)". The exemplar was the only call site not following the pattern named after it. **Evidence [verified: `aether/node/src/test/java/org/pragmatica/aether/api/ClusterEventAggregatorTest.java`]:** 3 new tests pinning that `emitLocal` publishes `SelfDrainInitiated` under NOT_OWNER, that `emit` suppresses it (the trap, pinned so a "simplification" back to `emit` fails), and that an OWNER emits exactly once rather than twice; 799 node tests green, `build.sh` green, 0 new lint findings. **What these tests do NOT cover [design intent — unverified]:** the *wiring* itself. Every test drives the aggregator directly; none constructs `AetherNode`'s drain emitter, so reverting the call site to `emit` would leave the suite green. The wiring is established by inspection only. **Also unverified:** whether the event now becomes durably visible. `emitLocal` still publishes via `publishEventual`, which for a non-owner forwards to the pre-partition owner — unreachable in exactly the partition scenario that triggers a self-fence. This fix removes the unconditional drop; it does not by itself guarantee delivery, and a node-local durable record is the likely follow-up. **Structural caveat left open:** gate choice is an unenforced per-call-site convention across 33 `ClusterEvent` variants and 3 gates. Making the gate a declared property of each variant would close the class; without that, the next per-node event repeats this defect exactly.
- **Owner failover no longer duplicates a partition's entire history (#567).** A replica catching up from a peer computed its catch-up `fromOffset` from the **replica registry's self-descriptor** (`selfConfirmedOffset(replicas)`) rather than from its own ring. `SelfWatermark`'s contract already warned in as many words that this substitution is invalid — *"the registry's self-descriptor `confirmedOffset` is NOT a substitute: after a restart it is `-1` (SYNCING) even though the node may have recovered real local events"* — and after a failover it reads exactly that `-1`. So `fromOffset` became `0`, the replica re-pulled history it already held, and because `StreamPartitionRecovery.appendRecoveredEvent` assigns **sequential offsets at the ring tail** it did not overwrite the overlap: it re-appended it as brand-new offsets. Measured on a 5-node remote-host cluster (`test-stream-replica-failover`): a replica holding 25 events pulled from offset 0, landed 50 entries carrying 25 distinct markers with `offset 25 -> marker 0000`, and then self-promoted **owner at watermark 49** — a doubled log presented as authoritative. The `promote` completeness gate could not catch it, since `highestApplied = fromOffset + applied - 1` is satisfied identically by a correct empty pull at the tail and by a full re-pull from zero. The peer-source pull now derives its floor from `selfWatermark.localWatermark(...)`, the local ring head — the same authority the sibling owner-source path (`backfillFromOwner`) has always used. Both pull paths now share one notion of "what have I already landed", which is the only one that can be correct: the ring is where events land, so the next event to append is exactly ring-head + 1. **Scoped deliberately:** `selfConfirmedOffset` is still the right input for the owner re-verify comparison, which asks "is the owner ahead of my acked position", a different question from "where does my log end". **One test's proxy changed:** `backfill_caughtUpSourceExists_normalBackfill_noPromotionPathTaken` asserted the normal path never reads the self-watermark, using a throwing stub as a stand-in for "no promotion contest ran". That proxy encoded the defect — the normal path must now read the local tail — so it was replaced with an honest empty-ring watermark (`-1`, the true state in that fixture, leaving `fromOffset` at 0 as before); the probe-must-not-be-consulted assertion still carries the test's actual intent, and every behavioural assertion is unchanged. **Evidence [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/PartitionBackfillTest.java` — `PeerSourceAppendFloor`]:** 3 new tests written and confirmed **red against the unfixed tree first** (`fromOffset` expected 25, was 0; offset 25 held `event-0`; a behind-replica pulled 25 events instead of the missing 15), green after, with the 49 pre-existing tests in that class green throughout; 650 module tests green. The suite also pins that a genuinely-behind replica still pulls exactly its missing suffix, so the fix cannot degrade into a no-op. **Live-path verified [verified: `aether/tests/integration` suite `02-chaos`, remote-host 5-node cluster]:** the full suite green (41 assertions across 7 scripts, 0 failed, 0 skipped — reproduced identically on an independent second run from a fresh build) AND — because that suite passes *with* duplicates present and therefore proves nothing on its own — the partition read directly afterwards: **25 total events, 25 distinct markers, offsets contiguous 0..24, offset 25 absent** (it held `marker 0000` when the defect was live), last event `offset 24 = FLVR-FAILOVER-MARKER-0024`. Owner re-resolved node-1 → node-2 in 12 s during the run. **Two adjacent findings, neither fixed here:** `DefaultFailoverRecovery` is dead code — nothing outside its own test constructs it — and it carries the same defect plus tests that assert the wrong semantics (`fromOffset` from the *source's* watermark); and `StreamPartitionManager.appendRecovered` documents "into an empty partition" as a precondition that nothing enforces, which is what let a caller's arithmetic slip become silent data duplication rather than a rejected append.
- **A caught-up replica no longer loops forever in the cold-start promotion contest (#559).** `PartitionBackfill.applyOwnerResponse` routed EVERY empty owner catch-up response to `handleNoSource` → `waitThenPromote` → `attemptColdStartPromotion`, because an empty response has two very different meanings and nothing distinguished them: the owner is genuinely empty (the #445 failover case the distrust gate exists for), or **self already holds everything the owner holds**. A replica in the second case entered a promotion contest it had no business entering, and since `peerNodeIds` includes the owner, it tied with the owner at the same watermark and lost the deterministic lowest-NodeId tie-break — returning to SYNCING and repeating every `STREAM_BACKFILL_REDRIVE_INTERVAL` (5 s), indefinitely. Observed on real hardware: two replicas holding exactly the owner's tail declined promotion **336 times each over 28 minutes**, correctly losing every time, fully caught up throughout, with no way to say so. The issue's proposed discriminator — `response.toOffset() == fromOffset - 1` — turned out to be unusable: `ForwardCatchupTransport.toResponse` stamps `toOffset = fromOffset - 1` for *every* empty response, so the comparison is an identity that carries no information about the owner's true tail. The two cases are byte-identical on the wire and the owner's watermark has to be asked for. The empty-response path now probes the owner once and treats self as caught up iff the owner reports a REAL watermark (`>= 0`) that self is not behind. `ownerWatermark >= 0` is what preserves #445 — a fresh or re-elected owner with an empty ring reports `-1`, is still not trusted as the true tail, and still falls through to the probe-gated no-source path rather than flipping a false `CAUGHT_UP`. The probe is gated on self holding data at all (`selfConfirmed >= 0`), so the empty-owner failover path keeps its exact previous behaviour and costs no extra round-trip. Deliberately scoped to the owner-pull path: the cold-start contest in `decidePromotion` keeps its lowest-NodeId tie-break unchanged, because there the HRW-ranked node is a candidate rather than an authority — nobody has been confirmed to hold authoritative history — so matching its watermark would establish nothing. Being caught up and being the promoted source are different questions; only the second needs a tie-break. **One invariant changed:** a non-owner now probes the owner once on the empty-response path, where `backfill_selfIsNonOwner_noSource_staysSyncingUntilBound` previously asserted no probe at all. That invariant is incompatible with any fix for this issue; all of that test's behavioural assertions are unchanged and it still fails when the #445 guard is removed. **Evidence:** 3 new tests including the 3-replica incident topology where the tie-break is live; both design decisions mutation-checked red — removing the `ownerWatermark >= 0` guard fails the #445 regression *and* the pre-existing non-owner test, and disabling the tail check entirely fails exactly the two new acceptance tests while leaving every #445 and cold-start test green. 646 module tests green. **Not yet verified:** the live failure mode — this reproduces as a red `02-chaos` suite (`expected 'CAUGHT_UP', got 'SYNCING'`) and only that suite passing on real multi-node hardware demonstrates the fix end-to-end.
- **Boot-time quorum is derived from reachability, not from configuration (#557, #558).** The entry below fixed the `BOOTING` fallback; this fixes the root, which sits one layer earlier and made that fallback unreachable in production. `AetherNode` seeds the membership FSM from `config.topology().coreNodes()` at *wiring* time, and `MembershipFsm.seedMember` promotes each id by dispatching `UpHysteresisMet` **directly**, bypassing the healthy-streak hysteresis that normally gates promotion on observation. Every configured core is therefore a strict `MEMBER` before a packet moves. That single fact produced four symptoms: (1) `PresenceGenerationSnapshotSource` latched its one-way quorum gate on its first call, so `TopologyObserver` took the `MembershipView` branch, flipped `BOOTING → NORMAL` and declared quorum with zero connections — measured on a 5-node remote-host cluster as `BOOTING -> NORMAL` and `Quorum established` in the *same millisecond*, 160 ms before the first QUIC Hello completed; (2) the `BOOTING` connectivity fallback fixed below runs only while the membership view is absent, which the seed made false before `TopologyObserver` had even started — so on any cluster configured with at least a quorum of cores it was dead code, and its tests pass because they configure partial topology; (3) `/api/status` (`cluster.quorate`), `/api/health` and `/health/ready` all derive from `StatusRoutes.quorumStatus`, whose two paths were both seed-derived, so readiness reported quorum held from configuration alone; (4) `QuorumLossDetector`'s arm-after-first-quorum latch — documented as "has this cluster ever been quorate", existing so a node booting into a still-forming cluster never self-fences — armed on the configured set during construction, spending its cold-start guard before formation began. New `MembershipFsm.coreObservedMembers(self)` and `strictCoreObservedMemberCount(self)` narrow the counting projections to members carrying **latched** first-hand reachability evidence (a completed QUIC handshake or a SWIM ALIVE observation), plus self, which is reachable by definition and never observes itself. The latch is one-way on purpose: this gates formation, not liveness, so a transient `SUSPECT` or link flap must not drop a member out of the quorum numerator and emit a spurious `PASSIVE` edge. Placement, heal-deficit and role-assignment consumers keep reading `coreCountedMembers` — only the quorum numerator moved. **Formation is not gated behind consensus:** with the view now correctly absent at boot, the connectivity fallback carries cold start, and the same handshakes that satisfy it latch the evidence. This is why the rule rejected during design — requiring *authoritative* sync responses — deadlocked formation 0/5 while this does not: fresh formation cannot produce authoritative state, but it does produce reachability. **Operator-visible change:** `/health/ready` now means "I can reach a majority" rather than "a majority is configured", so a node reports ready later — after real connectivity rather than after config parse. The `/api/health` quorum detail string changes from `Counted core members: N / required: M` to `Reachable core members: …`, because "counted" had become the wrong projection name. `guarantees.md`'s minority-self-fence armed-latch claim is now earned rather than merely stated. **Evidence:** 10 unit tests across the two projections, each design decision mutation-checked red (a vacuous reachability filter fails the boot-seed regression plus two others; a non-latching flag fails exactly the two flap cases); 5-node in-JVM formation green via `ClusterFormationTest` (4/4, 25 s), confirming no formation deadlock. **Not yet verified:** multi-node with failure injection on real hardware — the remote-host suite and cloud sweep are the outstanding gates, and the readiness-timing change above is the specific thing they need to exercise.
- **Cluster start no longer declares quorum before a single peer is reachable (#557).** `TopologyObserver.addNode` recorded a newly *discovered* peer as `NodeState.healthy(...)` — optimistically healthy before any connection existed — then merely *requested* a dial and evaluated quorum synchronously in the same block. The health filter that count passed through was vacuous: `nodeStatesById` has only `putIfAbsent` and `remove`, and `NodeState.suspected(...)` is never called anywhere in the repository, so every entry stayed `HEALTHY` for life and `legacyHealthyActivePeerCount()` was arithmetically `nodeStatesById.size() - 1` — a discovery count wearing a health filter. That count governs boot specifically, because the SWIM-observed `MembershipView` is latched absent until FSM members reach quorum: a genuine catch-22 (the snapshot is published only after Rabia commits, which itself needs quorum) that the fallback exists to break. So at cold start quorum meant *"I have heard of ⌊n/2⌋+1 nodes"*, not *"I can reach ⌊n/2⌋+1 nodes"* — `RabiaEngine` was told `ACTIVE` and broadcast its one-shot `SyncRequest` into a network with zero connected peers, leaving the other nodes waiting for sync responses that were never sent, with nothing ever committing. It surfaced as a cluster that intermittently never formed — roughly 1 run in 12, seen as a 30-minute forge-tests timeout with **zero** failing assertions, because the test blocked on an untimed `cluster.start().await()` before reaching a single assertion. The boot count now intersects the discovery-derived dial set with the transport's last reported CONNECTED set, which `NetworkServiceMessage.ConnectedNodesList` already delivered to the observer on the same reconcile tick and which was previously used only to compute re-dials. `CONNECTED` is a genuine post-handshake fact — set after Hello completes and peer identity is verified — not the deliberately-distrusted `isActive()`. **Cost:** quorum establishes up to one reconcile interval later (5s production, 1s in-JVM Ember), measured against a declaration that previously fired ~1s *before* the first lane existed. The catch-22 is preserved rather than reintroduced: the dial path reads only the in-memory dial set and never consults consensus, so quorum stays satisfiable from transport connectivity alone with nothing committed. **This entry describes only the BOOTING-fallback half of #557 — see the following entry, which corrects two claims made here:** on a cluster whose config lists at least a quorum of cores, the fallback this fix corrects was never reached in production, and `/health/ready` was NOT unaffected. **New behaviour worth watching:** the boot count can now *drop*, which the monotonic discovery count could not, because `connectedPeers()` excludes `EVICTED` where `activePeers()` includes it specifically to avoid flicker — so a transient eviction in the pre-view window can emit a `PASSIVE` edge that previously could not occur. It recovers within one reconcile interval, and once the FSM view latches the view path governs.
- **A failed schema migration now holds slice activation — and holds only its own blueprint's slices (#542).** The gate was wrong in both directions at once. It blocked on `PENDING`/`MIGRATING` only, and since the orchestrator writes `PENDING` when scheduling a *recoverable* retry and `FAILED` on *permanent* failure, the gate was inverted: it held slices during retries that were going to succeed, and released them the moment the migration failed for good — so slices activated against an un-migrated database. It was also unscoped: it scanned every schema record in the KV-Store, so any blueprint's in-flight migration held every blueprint's slices. Datasource names are cluster-global (the default `schema/V001__*.sql` layout names the datasource `database` for every blueprint), which is what made an unscoped scan a cross-blueprint hold rather than a curiosity. `SchemaVersionValue` now carries a **required** `owningBlueprint` component, and the guarantee is: *a slice is withheld from activation if and only if its own blueprint owns a datasource whose migration is in `PENDING`, `MIGRATING` or `FAILED`* — `COMPLETED` alone releases, and another blueprint's failed or in-flight migration cannot hold it. Ownership matches on `group:artifact` with the version stripped, so a blueprint advancing `1.0.0` -> `1.0.1` still owns the records its earlier version wrote. A slice whose blueprint is absent from the leader's map, does not set `schema_required`, or carries no owner is reported ready rather than held — no record can be attributed to it, so holding it would be an unclearable hold. **Scope limit, stated plainly: the gate keys on migration *ownership*, not *usage*.** A blueprint that reads a datasource without declaring migrations for it is not held when that datasource's owner fails. Observability shipped with the fix: the leader emits a `SCHEMA_ACTIVATION_BLOCKED` audit entry and a `SchemaEvent.ActivationBlocked` naming the datasource, the owning blueprint and the held slices; `owningBlueprint` is surfaced on `GET /api/schema/status`, as the `OWNING BLUEPRINT` column of `aether schema status`, and on the dashboard schema panel alongside a `blocksActivation` hold badge.
- **Two blueprints claiming the same cluster-global datasource are now refused at deploy time with 409 (#550).** Because datasource names are cluster-global, a second blueprint declaring migrations for `database` silently overwrote the first blueprint's schema record, leaving two blueprints interleaving unrelated version sequences against one physical database. `POST /api/blueprints/deploy` and `POST /api/blueprints/publish` now refuse the request with `409 Conflict` before any KV command is applied, so a refused publish writes nothing. Republishing the *same* blueprint at a newer version is an owner advancing its own schema, not a conflict; a blueprint that declares no migrations passes trivially, so sharing a datasource for reads and writes stays legal — only duplicate migration *ownership* is refused. `POST /api/blueprints` (raw blueprint content) is unaffected: migrations are read from the artifact jar's `schema/` directory, so a raw-DSL blueprint declares none. **Precise scope of the check:** both routes are `DEPLOYMENT` task-group targeted, so a request is forwarded to the task-group owner and the ownership lookup reads that owner's state rather than a stale follower's — but the check is a read of the existing record followed by a write, not a compare-and-swap. Two publishes issued *concurrently* for the same unclaimed datasource can both observe it unclaimed and both proceed; sequential publishes, the realistic operator case, are reliably refused. This is the same deploy-time read-then-write window the other validations on that path already use.
- **`aether schema baseline` no longer wipes the record it baselines, and refuses to invent one (#551).** Baselining rebuilt the record from scratch, dropping `artifactCoords` — which broke `SchemaOrchestratorService.resolveAndParseMigrations` on every subsequent migrate of that datasource — and, once ownership became a required component, would have dropped the owning blueprint too, detaching the record from the very gate that consults it. Baseline now rewrites only the version, the marker migration name and the status, inheriting the coordinates and the owner from the existing record. A datasource with **no** record can no longer be baselined: the call fails with `Schema status not found for datasource` instead of fabricating the unowned orphan that required-ownership exists to make unrepresentable.
- **`aether schema` action commands no longer print a canned success line over a server failure.** `migrate`, `undo`, `baseline`, `retry`, `status` and `history` skipped the `checkResponseError` guard every other CLI command applies, so a failed call still printed e.g. `Migration retry triggered for orders_db` and exited 0. All six now surface the server's message and a non-zero exit code — load-bearing for the #542 recovery workflow, where `retry` against a non-`FAILED` record and `baseline` against an unpublished datasource are both expected failures.
- **Dashboard schema panel: rows survive a REST refresh.** The WebSocket frame and `GET /api/schema/status` describe the same record under different field names (`name` vs `datasource`), and the panel read only the WebSocket spelling — so the refresh that follows a dashboard Retry click blanked every row's name and its `x-for` key. REST rows are now normalized into the WebSocket shape before rendering.
- **Documentation corrections found while completing #542.** `management-api.md` claimed `POST /api/schema/retry` returns `409 Conflict` when the datasource is not `FAILED` while the code answered `500`. The claim was first corrected to describe the `500` honestly, and then the *code* was corrected to make the documented `409` real — see the Schema Management status-code entry below, which supersedes that interim wording. `feature-catalog.md` described the schema gate as "blocks ACTIVATE until COMPLETED", which was never what the code did.
- **Schema Management endpoints answer 404/409/400 instead of a blanket 500 — the documented status codes are now reachable.** Same defect class as the blueprint-publish `409` (#550): `SchemaRoutes` declared its failures as plain `Causes.cause(...)` constants, and `ProblemResponses.resolveStatus` tests `cause instanceof HttpStatusAware` and silently defaults everything else to `500`. A missing datasource, a refused retry and a genuine node fault were therefore indistinguishable on the wire, and `management-api.md` had just been corrected to document that `500` as the contract. The causes now live in a sealed `SchemaRouteError` and carry their own status: **404** for `Schema status not found for datasource '<name>'` (raised by every route in the group — status, history, migrate, undo, baseline, retry), **409** for a `retry` against a datasource that is not `FAILED` (the request is well-formed and the datasource exists; the conflict is with cluster state), and **400** for a present-but-non-integer `?version=` / `?targetVersion=`. Both failure messages now name the datasource, and the retry conflict additionally names the status it actually observed, so the ProblemDetail `detail` explains the refusal without a second call. **The 400 case was not a wrong status but a missing one:** `baseline`/`undo` parsed the version with a bare `Integer.parseInt`, and nothing between the route builder and `ManagementRouter` lifts — the `NumberFormatException` escaped the handler and was caught only by the outermost Netty guard, which answers `500` with a bare `{"error":"Internal Server Error"}` envelope, bypassing the RFC 9457 funnel entirely and dropping the request from management metrics. An **absent** parameter is unchanged and still takes its documented default (`1` baseline, `0` undo); the parameter tables that called it "required" were wrong and now say so. `SchemaRouteError.httpStatus()` is deliberately left **abstract** rather than defaulted to `500`, so a future variant cannot silently inherit the very default this type exists to eliminate. Proven at the ROUTE level, not the mapping level: `SchemaRouteStatusTest` drives the real `Route` handlers over an in-memory KV-Store and feeds the emerging cause through the exact `ProblemResponses.writeProblem` call `ManagementRouter.writeError` makes, asserting the cause arrives unwrapped (any composite/re-wrap erases the mixin and restores the `500`), plus a negative control proving a plain `Cause` still yields `500`. Mutation-verified: flipping the three statuses back to `INTERNAL_SERVER_ERROR` fails 12 of the 21 tests while the negative control correctly still passes. The two integration suites that grep this contract (`10-database/test-schema-retry.sh`, `06-deployment/test-schema-migration.sh`) match on the body phrase rather than the numeric code and stay green; their stale "HTTP 500" comments were corrected. (node 791/0.)
- **Declarative stream consumers deliver under a DEFAULT deployment — consumer placement no longer requires the partition owner to host the slice (#535, completing #488).** #488's wiring was correct and live-proven, but its gating rule was `partition owner ∩ slice deployed locally`, and on a real 5-node Hetzner cluster at default replication (slice on 3 of 5) **that intersection was empty**: three successfully-published events were delivered to nobody while every node truthfully reported `attachedSubscriptions: 0`. The forge fixture structurally could not catch it — it ran the slice on every node, making the intersection non-empty by construction. **Three premises in the issue's own fix directions turned out to be false, and disproving them collapsed the "largest change" option into the smallest.** (1) The remote-read path #488 assumed was missing already exists and is production-wired: `ForwardingReadRouter.localOrForwardOnNotLocal` forwards to the HRW owner on a `PARTITION_NOT_LOCAL` local read, and `StreamReadRouter` is built with a real forward client — only `ConsumerRuntimeState.pollPartition`'s hardwired `partitionManager.readLocal` needed a seam. (2) Assignment needs neither consensus nor `ConsumerGroupCoordinator`: `ReplicaPlacement.place(...)` is public and takes an ARBITRARY candidate set, and `DeploymentMap.byArtifact` already gives every node the artifact→hosting-nodes map from mirrored `NodeArtifactKey` notifications. The coordinator was rejected for a better reason than size — its `joinGroup`/`leaveGroup` have no failure detector (a crashed node never leaves, its assignment stays stuck) and assignment is leader-only, stalling through every election; HRW over the deployment map inherits failure detection for free. (3) The owner-forwards-invocation option is genuinely blocked: `SliceInvoker` has no node-targeted invoke and requires the CALLING node to hold a codec-bearing bridge, which the owner by definition lacks. Placement-aware deployment was rejected on substance rather than the feared ownership↔placement cycle — it cannot cover partitions > replicas, and HRW ownership moves on every membership change while placement does not follow, so the gap would reopen on each membership edge unless slice redeployment chased membership churn. **The rule is now a strict EXTENSION:** exactly one node is assigned per `(stream, partition, group)` — the HRW owner when the slice is `ACTIVE` there, else the HRW pick over the nodes where it is, with the candidate set intersected against `ReplicaSetController.reconciledMembers()` (deliberately the SAME member view stream ownership is computed from, so the ownership and placement halves cannot disagree about liveness). Where #488 already worked the assignment is bit-identical — same node, local reads, push listener — so the live-validated path carries zero regression risk and only the previously-silent path is new. A non-owner assignee reads through `streamReadRouter` with `GOVERNOR` preference; `NEAREST` would be wrong because it also forwards on an EMPTY read, which for a tail-polling consumer means a forward on nearly every poll. **A latent duplicate generator was fixed on the way:** the poll loop rescheduled EAGERLY, so a second poll could read from a cursor the first had not yet advanced and re-deliver the same events — harmless while every read was a synchronous local one, fatal once a read takes a network round trip. The loop is now serial (next poll scheduled only when the current cycle's read AND delivery complete). **The observability endpoint changed with the model rather than becoming the next claims-vs-reality defect:** `unconsumedOwnedPartitions` would have asserted a gap that no longer exists, so it is replaced by `unassignedPartitions` (partitions no node can consume because the slice is ACTIVE nowhere — the only remaining true gap); `partitionAssignments` is added, naming the consumer and owner node per partition so one call to ANY node answers "who consumes partition 3, and does it read locally"; and `eventTypePublishable` now returns **null rather than false** on a node that cannot know, since the probe needs the slice's own codec registry — `false` there was a fabricated value, not a degenerate one. **Quad closed:** the endpoint shipped in #488 at 1-of-4 (REST only, in neither the management-API reference nor the CLI); it now has both, plus `aether streams consumers`, with an explicit dormant-slot decision recorded for the dashboard. **Determinism proof, not a lucky one:** the new forge arm gets its guarantee from the pigeonhole — a 5-partition stream deployed at `instances = 1`, so the single host owns at most one partition and at least four MUST be read through their owners; a 1-partition stream at `instances = 1` would have exercised the interesting case only 4 times in 5. The arm asserts it really landed in the uncovered configuration (the runtime's own diagnostic must report forwarding) so a co-located run fails loudly instead of passing for free, and the unit-level non-vacuity arm shows the same subscription against the LOCAL reader delivering nothing. `DeclarativeStreamConsumerTest` keeps the co-located control on the same new stream at `instances = 5`, so a change that fixes the uncovered case by breaking the co-located one cannot pass; it also moved off port band 14000, which it silently shared with `StreamOwnerFailoverTest` while being absent from `TEST_PORT_ALLOCATION.md` entirely. **Guarantee (restated honestly):** at-least-once delivery per partition, conditional on the slice being ACTIVE on at least one live node. Duplicates arise from `RETRY` redelivery, from the reconcile-tick window during an ownership or placement change (old and new assignee may both deliver), and from resuming at the last checkpoint (≤1000 events or ≤30s) rather than the last delivered offset after an ungraceful move — a graceful detach flushes the exact cursor. NOT effectively-once: there is no fencing token on delivery, and two transiently-divergent assignment views can both deliver and both write the cursor, last write winning. Delivery is zero only when the slice is ACTIVE nowhere, and that is reported, not silent. No new background handles (#499): the reconcile tick is already in `periodicTasks`, poll futures are already retained and cancelled on unsubscribe/close, and an in-flight forwarded read at stop resolves into a cancelled state the existing guards drop. The `AetherNode` `streamReadRouter` block moved above the consumer wiring AS ONE UNIT — only `committedStreamOwnerSource` and `linearizableBarrier` were created later and both depend solely on far-earlier state, so no late-binding seam was needed.
- **Dead Management-API routes: every declared route is now served or absent, and a structural guard keeps it that way (#525).** Six routes were declared in `ManagementRoute`, consumed by CLI and dashboard, and served by nobody — the sweep also turned up two more the issue's own test could not see. Dispositions were made individually, not batched. `ROUTES_LIST` (`/api/routes`) is **implemented**: it was fetched live by the dashboard's routes panel (`stores/deployments.js` `refreshRoutes`) and offered as `aether routes`, both landing on a 404; `HttpRouteRegistry` is already cluster-wide (each `RouteInfo` carries the nodes serving it), so it registers against the existing builder that `/api/nodes/routes` uses. `WORKERS_LIST` is **implemented** against committed consensus state — the premise that the worker runtime had been removed was **false**: `AetherNode.activateWorkerMode` is live, `WorkerConfig`/`WorkerConfigLoader` parse a real `[worker]` section, and `GovernorAnnouncementValue` is the authoritative cluster-visible worker roster, so `/api/workers` now projects it per-worker (node, community, governor, `isGovernor`) where `/api/cluster/governors` projects the same announcements per-community; dissolved communities are excluded. `WORKERS_HEALTH` and `WORKERS_ENDPOINTS` **cannot** be built — no per-worker health fact is replicated and only the *governor's* `tcpAddress` reaches consensus — so they answer an honest **501 naming the missing capability**, and their CLI subcommands were removed rather than left advertising what they cannot deliver. `AUDIT_COMMANDS_LIST` **removed**: both `AuditLog` classes are pure SLF4J emitters with no queryable store, and the documented backing (`DirectLifecycleWriter`, `CommandReceived`/`CommandApplied`) exists nowhere in the tree — the endpoint's query parameters, response shape and `curl` examples were documentation for code that was never written; the only persisted audit data is API-key lifecycle, already served by `/api/cluster/keys/audit`. **The issue undercounted**: `CLUSTER_MIGRATE` and `CLUSTER_MIGRATE_PLAN` are dead too. They evaded the "is the enum constant referenced under `aether/node/src/main`?" test because they *are* referenced — in `ManagementRoutePermissions`, which grants authorization for a handler that does not exist — while `aether cluster migrate --target … --zone …` prompts for destructive confirmation and POSTs into the void; both now answer 501. The real count was 179 of 189 served, not 181 of 188. The durable artifact is `ManagementRouteCoverageTest`, which asserts EVERY `ManagementRoute` is either registered through the single `ManagementRoutes.route(...)` funnel or explicitly exempted as claimed by the `/repository/` prefix handler; it keys on handler registration rather than grep precisely so a permissions-table mention cannot satisfy it, an anti-cheat test verifies exempted routes really do sit under the claiming prefix, and a third test proves the scanner detects a known registration (a guard that quietly scans nothing always passes). It caught a real false positive during development — an indirect registration helper — which is why `NotImplementedRoutes` spells each registration out literally. (node 747, cli 616, `jbct:check` 0 format issues / 0 lint errors across aether-management-api, node, cli.)
- **Applications can publish their own types again — resource provisioning is now scoped to the deployed slice's codec (#526), and codec resolution is deterministic (#529).** Triage reclassified #526 from "a stream bug" to a **resource-provisioning-boundary bug with three symptom families**: `StreamPublisherFactory`/`StreamAccessFactory`, `CacheInterceptorFactory` (`DISTRIBUTED`/`TIERED`) and `IdempotencyInterceptorFactory` all read `Serializer`/`Deserializer` from the same `ProvisioningContext`, and all three received the **node-wide** codec, which knows framework types and nothing an application declares. `publisher.publish(new OrderPlaced(...))` therefore threw `No codec registered for class` before routing was ever reached, and a distributed cache could not hold an app-typed method result. Root cause: `SpiResourceProvider.enrichWithRuntimeExtensions` applied node-wide runtime extensions **unconditionally and last**, overwriting anything the slice supplied. Runtime extensions are now DEFAULTS layered *under* caller-supplied values. An exhaustive audit of all 16 SPI-registered extension types established the guard is provably inert everywhere else: only two extension types were ever caller-supplied (`ConfigurationProvider` and the sliceId `String`, both from `SliceLoadingContext`), neither is SPI-registered, so the intersection is empty. The slice's codec cannot exist at provisioning time — `Slice.codec(parent)` is an instance method and resources are provisioned *inside* the generated factory, before the slice object exists — so a single-assignment `DeferredSliceCodec` is handed to the resources and bound by the loader (`DependencyResolver.resolvedSlice`) the moment the instance is created, still strictly before `start()` and before the slice is invocable. Use before binding throws, naming the slice and the type; there is no fallback, because a substituted codec is the silent-wrong-state class one layer down. **Framework-typed streams are proven identical, not merely working**: the slice codec is a CHILD of the node codec and inherits every framework registration verbatim, asserted as byte-for-byte equal output for `String`, `List` and mixed containers, plus cross-codec decode. No codegen change, so `ENVELOPE_FORMAT_VERSION` stays 1000 under the GA freeze. Separately, `SliceCodec.findBySupertype` returned the **first assignable** entry from an unordered `Map.copyOf` and cached it, so which codec won was arbitrary, sticky for the process lifetime, and could differ between nodes — a cross-node encoding mismatch producing undecodable payloads rather than a clean error. Resolution is now the most-specific assignable supertype, with one documented tie-break (a class candidate beats unrelated interface candidates; Java's single-inheritance chain leaves at most one minimal class) and a loud failure naming the type and every competing candidate when genuinely ambiguous; the documented `ImmutableCollections$ListN -> List` case (which #488's batch path depends on) is covered by explicit `List`/`Set`/`Map` round-trips. The operator surface was corrected to match: `eventTypePublishable` on `/api/streams/declarative-consumers` consulted the node codec and warned "this consumer will receive nothing until #526 lands" — after the fix that alarm would have been false for exactly the case that now works, so it now consults the slice's own codec via the new `SliceBridge.sliceCodec()`. **Corpus gap closed**: all five stream blueprints were `String`-typed, which is why nothing caught this; `test-stream-consumer` gains an independent `OrderPlaced`-typed stream (publisher, declarative consumer, HTTP surface) alongside its untouched `String` stream, so a regression in either mechanism cannot mask the other. Both fixes are mutation-verified — restoring the first-match loop makes `Leaf` encode as `Base` and `MarkedRoot` as `Marker` with the ambiguity case throwing nothing; restoring the unconditional overwrite fails the precedence tests. (serialization-api 30, slice-api 294, resource-api 83, slice 718, aether-stream 641, node 735 — all green.)
- **`@PartitionKey` now actually routes — a shipped annotation that had never been read by the slice-processor (#507).** `aether/slice-api`'s `@PartitionKey` documented per-key partition routing and the whole runtime honored it (`StreamPublisherFactory`/`StreamAccessFactory` read `ProvisioningContext.keyExtractor()`; `DefaultStreamPublisher`/`PartitionedStreamAccess` hash it with `ReplicaPlacement.stableHash64`, cross-JVM-stable per `StablePartitionRoutingTest`) — but the annotation processor never looked at it: zero reads across `jbct/slice-processor/src/main`. Every publish therefore round-robined, so ANY multi-partition stream silently lost per-key ordering while its own annotation advertised the opposite. Shipped example `examples/notification-hub` was live proof: `NotificationEvent(@PartitionKey String senderId, …)` with `partitions = 4`, sender ordering claimed and not delivered. The processor now resolves the event type's `@PartitionKey` component and appends `.withKeyExtractor((Fn1<K, T>) T::component)` to the generated provisioning context, so the extractor the runtime was already looking for is finally supplied — the example gets keyed routing with zero source changes. Scoped to `StreamPublisher<T>`/`StreamAccess<T>`: topic `Publisher<T>` is unpartitioned (`PublisherFactory` ignores the extractor), so emitting one there would advertise routing that does not happen. Two `@PartitionKey` components on one record is now a compile error naming the type and both components — silently taking the first would make partitioning depend on declaration order, exactly the silent-wrong-state class this series is burning down. A primitive-typed key is emitted boxed (`Fn1`'s type argument rejects primitives; mutation-verified — un-boxing fails the generated compile). `@Key` and `@PartitionKey` now share ONE component resolver (`AnnotatedComponent`) instead of two copies that can drift. Streams with no `@PartitionKey`, topic publishers, and the #429 `test-stream-multipart` fixture (event type is `String`, not a record — round-robin distribution assertions untouched) are byte-for-byte identical to before. Emitted under the GA envelope freeze, so `ENVELOPE_FORMAT_VERSION` stays 1000 and the runtime accept-set is unchanged (recorded in `envelope-versioning.md`). (slice-processor 292/0 incl. 7 new `@PartitionKey` tests; slice-processor-tests 43/0; notification-hub clean build.)
- **A `security_mode = "NONE"` cluster can receive artifacts again — the two half-overlapping dev switches are unified (#520).** Live-caught on a real Hetzner cluster: the documented dev/eval posture (`[source.X.node_config.app-http] security_mode = "NONE"`, required so the bootstrap's own cluster-config write isn't 401-rejected) turns app-HTTP auth OFF — every caller is `anonymous`/`VIEWER` and API keys are ignored, `whoami` reporting `authenticated: false` even for the bootstrap-minted ADMIN key — while the artifact-publication gate in `MavenProtocolRoutes` consulted a *different* switch entirely (`AETHER_INSECURE_DEV_MODE`) and hard-required OPERATOR/ADMIN. The role was therefore structurally unholdable and `aether artifacts push` 401'd: a cluster you could provision but not deploy to. The integration harness never caught it because its compose env sets BOTH switches; only the cold bootstrap path sets one. The gate now admits a push for an authenticated OPERATOR/ADMIN **or** the dev-mode env **or** an app-HTTP security mode of NONE, reading the node's *effective* posture (the same `securityEnabled` value `ManagementServer` already receives from `AetherNode` — and `AppHttpConfig.securityEnabled()` is exactly `securityMode != NONE`, so the route cannot drift from the server it belongs to). `API_KEY`/`JWT` modes are unchanged and still reject anonymous and VIEWER. Every unauthenticated publish emits a WARN naming the artifact path, the admitting posture, and the remediation — a real operator push is matched first and never trips it. (node: 18 gate tests incl. warning-present *and* warning-absent assertions via a capturing appender; 269 green across `org.pragmatica.aether.api.**`.) **Live-verified** on a fresh 5×cpx32 Hetzner cluster: `whoami` → `anonymous`/`VIEWER`/`authenticated:false`, `artifacts push` → *All artifacts pushed successfully*, slice ACTIVE/HEALTHY on 3 nodes, `curl /api/hello/World` → `{"greeting":"Hello, World!"}` — the getting-started leg-4 acceptance ("a cold user reaches a served hello from the cloud cluster") met end-to-end for the first time.
- **`aether cluster destroy` no longer strands paid VMs, and no longer reports success when it does (#521) — root cause was a one-word section-name drift with a seven-month blast radius.** `BootstrapPhaseProvision` mined the bootstrap TOML for a **plural** `[sources.<name>]` header while the canonical section is **singular** `[source.<name>]` (`ClusterBootstrapConfigParser.SOURCE_PREFIX`), so `indexOf` missed on every real config and **every** persisted `SourceCleanupHandle` since that code shipped carried `credentialEnvVars = {}` (verified: all five clusters on the dev machine, back to 2026-07-11 — #439's handle-first cleanup guarantee has been dead on arrival for this path the whole time). Downstream, an empty credential map meant the missing-env-var loop never executed, so resolution reported **success** carrying zero credentials and the provider then failed with `Cloud credentials missing for provider 'hetzner': set HCLOUD_TOKEN` — naming an env var that *was* set and sending the operator to look in the wrong place. Three fixes, at the matching level: (1) the CLI now derives the stanza header from the parser's own `SOURCE_PREFIX` constant (made `public`) instead of re-spelling a literal — killing the drift class rather than the typo, and the new anchored regex also tolerates leading whitespace the old `indexOf` silently didn't; (2) a handle whose `credentialEnvVars` names no api-token env var is informationally equivalent to no handle and now falls back to raw provider env **loudly**, as the same demoted last resort, so resources are never stranded — a handle that *does* name an env var stays authoritative and an unset one still hard-fails, preserving #439; (3) registry + exit-code honesty — a failed cloud cleanup now KEEPS the registry entry (the operator's only handle on a cluster whose VMs may still be billing) and returns `ExitCode.CLEANUP_FAILED`, printing the literal retry command, while `--keep-resources` remains the acknowledged skip-and-succeed path; an aborted `cluster bootstrap` (the interactive prompt reading EOF in a non-interactive shell — indistinguishable from success in CI) now also exits non-zero. `ProviderResolver` additionally fails loud rather than returning success on an empty required-credential set. The #439 fixture that had *invented* the plural spelling — and so agreed with the bug — is rebuilt from the parser's constant and the real repo cluster TOML, with a non-empty-credential-mapping assertion; both fixes are mutation-verified (restoring the plural literal fails 8 tests; removing the resolver guard reproduces the incident's exact error string). (aether-config 312, cli 559.) **Live-verified** on a fresh Hetzner cluster: the new state file carries `"credentialEnvVars": {"api_token": "HCLOUD_TOKEN", …}` where every previously-bootstrapped cluster on the dev machine has `{}`, with zero fallback warnings (so the *mining* path was exercised, not the new safety net); an aborted attempt exercised failure-path auto-cleanup (all 5 VMs reaped, no strays); and `cluster destroy` exited 0 having actually terminated all five VMs with `Registry entry: removed`.
- **Installers: unpinned installs no longer silently deliver `1.0.0-alpha`, and upgrades across layout generations leave no broken binstubs (#510, #512).** Both installers' numeric-field sort treated `1.0.0-alpha`/`-rc1`/`-rc2` as ties (alpha won); resolution is now an explicit release rank — GA > `rc-N` > `beta` > `alpha`, numeric-aware (`rc10` > `rc9`), always excluding the moving `*-candidate` tags — and a requested-but-nonexistent version fails LOUD (exit 1 naming the releases list), never a silent fallback. `--version`/`--version=` now parse on all three scripts, with root `install.sh` gaining per-tool `--jbct-version`/`--aether-version` passthrough (the tools version independently). Cross-generation hygiene (#512): install/upgrade now purge ALL launcher scripts + libs of the tool regardless of prior layout (jar-mode ↔ archive-mode ↔ mixed — the dev-machine repro was a `bin/aether-forge` expecting a jar no generation ever placed), wrapper writes are `rm -f`-guarded so they can't write *through* a stale symlink (real bug found during proof), and every generated launcher validates its jar/dist target before `exec` with a "run install.sh again" message instead of a bare `Unable to access jarfile`. Also fixed: `setup_path`'s duplicate-guard grepped `"AETHER"` but inserts `# Aether` (case mismatch — every reinstall appended another PATH block to the user's shell rc); both guards now match the export line's own content. Proven via 9-case resolution fixture, synthetic mixed-generation sandbox upgrades (zero stale binstubs), and HOME-sandboxed idempotent-PATH runs.
- **`jbct init` scaffold passes its own gates and the first forge run serves traffic (#511, #513, #515) — with a structural drift gate so this class cannot recur.** Templates re-formatted/lint-cleaned to rc3-canonical (#511: the scaffold failed its own `format-check` at first `run-forge.sh`). `run-forge.sh` deploys by artifact coordinates (`<g>:<a>:<v>:blueprint`) instead of the dropped file-path form, stale `generate-blueprint.sh`/`blueprint.toml` remnants removed, and ForgeServer now exits non-zero when its startup `--blueprint` deploy fails instead of staying up healthy-looking over an empty cluster (#513). `deploy-test.sh`/`deploy-prod.sh` regenerated against the real CLI (`aether artifacts push` + `aether blueprints deploy --wait`; the old script invoked nonexistent `aether artifact push --env`), `aether.toml` points at the new bootstrap-config reference, and forge homes node data under `$AETHER_HOME/forge-data` — stream WAL crash-durable in local dev, one loud line instead of a read-only `/data` WARN wall with silent WAL-off (#515; production node `/data/aether` default untouched). The drift gate is two-stage: a fast jbct-cli test asserting generated sources pass current format+lint (proven to fail on injected template drift) and a Heavy forge-tests E2E driving `jbct init` → scaffold build → coords deploy → live `{"greeting":"Hello, World!"}` (plus a file-path-rejection arm). Riders: `jbct init --help` un-broken (a custom `--version` option silently disabled picocli's whole help/version mixin; version pinning stays via the per-tool flags), dead hidden `--slice` flag removed (`--no-slice` is the real opt-out), and jbct-lint RET-01/PAT-03 now exempt TEST_CLASS files so `jbct check` on a pristine scaffold reports zero errors (non-test code still fires both rules).
- **Silent-wrong-state reconciliation sweep — seven wired-or-removed dispositions across health, streams, config, and generated manifests (2026-07-24 pass; deferred members filed as #517/#518/#519).** (1) Dead operator escape hatch REMOVED: `ReconcilerRulesConfig`/`RuleSpec` documented `[reconciler.rules.<rule>] enforce=false` for a `LifecycleReconciler` that no longer exists — never parsed, zero consumers; an operator disabling a destructive rule was silently ignored. (2) Quorum honesty WIRED: node `StatusRoutes` computed `hasQuorum` as "≥2 nodes"/"≥1 peer" (a 2-of-5 minority reported quorum UP) and `cluster.quorate` as `size >= size/2+1` (always true); all three surfaces now share one derivation off the consensus layer's own `quorumLossSnapshot()` (fallback: majority over counted core members), so health, readiness, and status agree — and honest quorum only tightens CLI bootstrap formation (verified consumer audit). (3) Stream DELETE honesty WIRED: `destroyStream` failures propagated as non-2xx instead of `.recover`-swallowed always-`"deleted"`. (4) Default Docker image fixed: `ghcr.io/siy/aether-node:latest` → pinned `ghcr.io/pragmaticalabs/aether-node:1.0.0-rc3`, and (5) `KubernetesGenerator` now references `DockerConfig.DEFAULT_IMAGE` (one image constant; the generator emitted its own stale copy — aether-setup gained its first test pinning this). (6) Bootstrap `provider` typos fail LOUD: `ClusterBootstrapConfigParser` silently mapped an invalid provider string to `Option.empty()` (dropping cloud-provider identity); present-but-invalid now yields a parse error naming the bad value and valid providers, absent stays legitimate for ssh/forge/docker sources. (7) `management-api.md` corrected where it documented the old fake quorum semantics.
- **`blueprints deploy --wait` no longer reports a 300s timeout on a deployment that succeeded (#522).** Live-caught on a real 5-node Hetzner cluster during the #520/#521 re-validation: `--wait` printed `Deployment status: PENDING` for the full timeout and then failed, while the same cluster simultaneously reported the slice `ACTIVE`/`HEALTHY` on 3 nodes, carried `DEPLOYMENT_COMPLETED` in its event log, and served real traffic. Root cause was not a lost or unparseable state (the #438 suspicion): the gate **never queried a deployment-status surface at all**. It fetched `GET /api/slices` and substring-matched the user-supplied *blueprint* coordinates (`org.example:hello:1.0.0-SNAPSHOT`) against the response, which only ever carries the *derived slice* artifacts (`org.example:hello-hello-world:1.0.0-SNAPSHOT`) — a match that cannot occur, so the polled "status" was a hardcoded literal, not a reading. The same check was equally broken the other way: had the substring matched, it would have declared success the instant the coordinates appeared in the JSON in **any** state, including `LOADING` or `FAILED`. The gate now polls the purpose-built `GET /api/blueprints/status/{id}` and completes only on `overallStatus == DEPLOYED`, taking the blueprint id from the deploy response (a blueprint declares its own id, so deriving it from the requested coordinates was a guess) and failing loudly rather than waiting on an unidentified deployment if that id is absent. Because the node computes `overallStatus` from the same replicated `DeploymentMap` that backs `aether slices status`, the two surfaces are now structurally unable to disagree — no second source of truth was introduced, and no terminal state needed to be written. `PENDING`/`IN_PROGRESS`/`PARTIAL`/unreadable all keep polling, so a genuinely stuck or failed deployment still exits `TIMEOUT` (2); the status is now also sampled once before the deadline is examined, so an already-finished deployment is observed even at zero remaining time. (cli 580 green incl. 21 new both-direction tests replaying the live payloads; aether-deployment 3 new tests pinning the shared `DeploymentMap` derivation; aether-management-api round-trip test proving the artifact-shaped id's percent-encoded colons survive assemble→match — Netty's path decoder verified to return them intact.) **Not yet re-run against a live cluster.**
- **`aether scale --wait` and the rolling-upgrade drain gate read real status too — the #522 sibling sweep.** A sweep of every CLI wait gate for the same habit ("synthesize a status from a substring instead of reading the status field") found two more. (1) `scale --wait` counted occurrences of the artifact COORDINATES in the raw `GET /api/slices` body — but `ClusterSlicesResponse` names each artifact exactly once and its per-instance entries carry only `nodeId`/`state`, so the count was pinned at **1** no matter how many instances were running: `--wait -n 2` or higher timed out on a scale that had already succeeded (#522's exact failure mode), while `--wait -n 1` reported success immediately without reading instance state at all, including for a slice whose only instance was `FAILED`. It now counts instances the cluster reports `ACTIVE` for the exact requested coordinates, and an unreadable slice list stays below every legal target so the gate keeps waiting rather than passing. (2) `ClusterHttpClient.waitForDrainComplete` — which `WaveExecutor` gates rolling-upgrade waves on in four places — searched the whole `/api/nodes/lifecycle/{id}` body for `DECOMMISSIONED`; it now reads the `state` field, so a node still `DRAINING` cannot be reported as drained by any other field mentioning the token, and a wave cannot restart a node that is still shedding traffic. Both readings live in testable homes next to the existing `LiveNodesFilter` (`ScaleWait`, `ClusterHttpClient.isDecommissioned`) with 17 tests including the false-success arms; the scale fix is mutation-verified (dropping the ACTIVE gate fails 2 tests). Surveyed and left alone as correct: `ClusterDrainCommand.pollUntilDecommissioned` and `ClusterDestroyCommand.waitForDecommissioned` already parse `state`, and `waitForNodeReady` polls a real `/health/ready`. One adjacent non-gate finding filed rather than fixed: `ClusterRotateKeyCommand.extractFirstActiveKeyId` checks document-wide that *some* key is `ACTIVE` and then returns the *first* `keyId` in the document, which can be a revoked one.
- **Stream forward-retry unified across all three publish-forward sites (#485, #506).** `StreamWriteRouter` retried transient forward failures (`RemotePublishRetryable`, the owner-config-lag race) up to 3 attempts with a 150ms scheduled backoff — but `DefaultStreamPublisher.forwardToOwner` (#485) and `PartitionedStreamAccess`'s A6 owner-routed publish (#506, found by the #485 pass) forwarded single-shot, surfacing transient owner-materialization failures to the app. The retry state machine now lives in ONE shared `StreamForwardRetry` helper (thunk-driven; 3 attempts / 150ms `SharedScheduler` backoff / `RemotePublishRetryable`-only / propagate-on-permanent-or-exhaustion) routed through all three sites — eliminating the divergence class that produced these tickets (a fix landing on one copy and lagging the others). `StreamWriteRouter`'s observable behavior is unchanged (its 6 routing/retry tests pass unmodified); fail-soft bootstrap/local arms in `PartitionedStreamAccess` untouched. 9 scripted-client retry tests across the three sites; module suite 635/0; all five Heavy forge suites green including both owner-failover gates (end-to-end hot-path proof). Remaining partition-selection gap tracked as #507.
- **METRICS-lane community-metrics broadcasts no longer throw `No codec registered` (#492).** `CommunityMetricsSnapshot` (+ nested `PerSliceMetrics`/`PerMethodMetrics`) rides the core QUIC METRICS lane, but its generated codecs were registered only in `WorkerCodecs` — an **orphaned** assembly with zero consumers (remnant of the removed worker runtime) — so every broadcast from a core node failed in `writeToStream` (44× per forge failover run). The `aether.worker.metrics` codec aggregator is now registered in `NodeCodecs` (the only live registry), with a round-trip regression test through the production codec assembly. Sweep result per the ticket: the other four worker wired types (heartbeat/mutation/bootstrap/network) have no live traffic on the core mesh; `WorkerCodecs` dead-code disposition tracked separately.
- **Stream RF-restoration after owner-kill now converges — the #491-batch residual is closed (#499).** The real defect was a FIRE-ONCE backfill-completion ack: a replacement replica promotes CAUGHT_UP within milliseconds of its transport Hello — before its membership view populates — so the one-shot #336 `ReplicateAck` resolved no HRW owner and was silently skipped; on a write-idle partition nothing ever re-sent it, freezing the owner's replicas-view at `SYNCING@-1` over **fully-replicated data** (replication itself had rebuilt correctly — the deadlock was registry visibility, which also gates `convergedWithRfRestored` and every operator surface reading the replicas-view). `PartitionBackfill#reverifyNoOp` now re-sends the idempotent completion ack (interval-quiesced, no-op on the owner when already applied), so a lost one-shot ack self-heals under every loss mode. The ticket's original "empty HRW owner cannot catch up (watermark -1)" mechanism was DISPROVEN by per-correlation-id instrumented reproduces — that loop belonged to the killed node's zombie scheduler (below) logging into the shared forge console. Acceptance: `StreamOwnerFailoverPinnedTest` re-enabled as the permanent HARD gate and converged 3× consecutively (lossless reads phases 1–8 + RF-restoration phase 9).
- **A stopped node's periodic tasks no longer outlive it (#499, audit #501).** `AetherNode` armed 9 fixed-rate tasks on the JVM-global `SharedScheduler` and discarded every `ScheduledFuture` — `stop()` cancelled none of them, so a killed in-JVM node's backfill redrive kept firing forever against torn-down state (wiped rings, reset PeerStates), fabricating a convincing RF-restoration deadlock in the forge console and misdirecting the #499/#498 investigations. All 9 futures are now retained (`periodicTasks` record component) and cancelled first in `stop()`; the phase-9 harness additionally dumps every live node's self-tagged replica view on convergence timeout, so this failure class can never again die blind. Repo-wide audit of the remaining ~25 `scheduleAtFixedRate` call sites: #501 (rc4).
- **AwsClient: ELBv2 protocol corrected and provisioning-path hangs eliminated (#483) — caught by the LocalStack contract suite's first CI activation.** Two real defects: ELBv2 register/deregister/target-health spoke JSON+`X-Amz-Target` to a Query/XML-protocol service (now form-encoded Action/Version requests with XML response parsing; SecretsManager stays JSON); and `instanceStatus` hung 90 s on any instance lookup — it filtered by a nonexistent `tag:instance-id`, the empty reservation set NPE'd inside a `Promise.map` mapper, and an in-mapper throw leaves the promise unresolved forever (core-library behavior, tracked as its own design question). Fixes: native `describeInstancesById`, null-safe response DTOs, non-throwing mappers with typed causes, and a 30 s `HttpRequest` timeout on every AWS request as the structural no-hang bound. The LocalStack suite is now **10/10 green and blocking in CI** (docker-gated, opt-in flag removed; compute tests dropped from 90 s hangs to ~1.6 s). Fidelity correction in RFC-0016 §4.3: ELBv2 is LocalStack-**Pro**-only — Community asserts the bounded-typed-failure property; the happy path is unit-guarded and rides the Pro/live smoke.
- **Bootstrap SSH keys are cluster-scoped and never account-guessed (RFC-0016 W3, #444 partial).** Keys upload as `aether-bootstrap-<cluster>`, and the Hetzner replacement-provisioning lookup filters on that cluster's prefix derived from the persisted cluster name — the 3B account-wide bare-prefix fallback (which matched every cluster on the account) is deleted; an unresolvable cluster name fails loud with no guessing, never broad-matching. No dual-accept of old bare names (no pre-rc3 clusters exist, per the Q1 ruling). The #444 remainder (firewall references, labels policy, exact-id matching to close the nested-cluster-name prefix edge) is rescoped on the issue. (environment-hetzner 72/0, incl. cross-cluster isolation test.)
- **A failed bootstrap can no longer strand paid resources because cleanup resolved credentials differently than provisioning (#439).** Every cloud cleanup path (VM reap and SSH-key reap — the `CreatedResource` switch is exhaustive and was verified per-variant) now resolves credentials handle-first via the persisted `SourceCleanupHandle.credentialEnvVars`; both raw-env fallbacks are demoted to loud last-resorts reached only when no handle exists. Regression-proven on the exact 2026-07-11 incident shape: timeout-triggered cleanup with a non-default `HCLOUD_TOKEN_PROD` reaps the VM and the SSH key from the prod token while `HCLOUD_TOKEN` is asserted never read. (cli 538/0.)
- **A `[source.<provider>.spot]` sub-table on a provider without an implemented spot arm now fails validation loudly (RFC-0016 W10, epic #463).** PF-16 generalized from its Hetzner-only check to a data-driven unsupported-set (`hetzner`: no spot product; `gcp`/`azure`: client arm pending, named per provider) — previously a validated spot role on gcp/azure would have silently provisioned on-demand. AWS, carrying the only real spot arm since W1, is the sole provider accepting spot sub-tables today; docker was already rejected by PF-15 (spot is cloud-only). Extending a future arm = deleting one map entry.
- **Cloud (Hetzner): spec-level `[source.<provider>.<role>] image` (VM boot image / snapshot id) now reaches provision requests for both bootstrap seeds and CTM auto-heal replacements (#459).** The field was previously dropped at parse (`RoleSubTable` had no `image`), so the documented snapshot mechanism (`vm-snapshot.md`) had no effect and every VM booted the stock Ubuntu image — snapshot-accelerated provisioning (30–120s saved per VM) was unreachable. The field is now parsed and threaded through the seed path (`ProviderResolver` → `[cloud.compute] image`) and the node overlay (`BootstrapOverlayGenerator`), so replacements inherit the snapshot across leader generations via the persisted profile — the same mechanism as `ssh_key_ids`; the provider's silent hardcoded `ubuntu-22.04` default is demoted to a provision-time loud WARN fallback (kept safe — a stock image still boots — deliberately unlike `server_type`'s fail-loud from #442). Precedence: role `image` > `[cloud.compute] image` > loud default; existing TOMLs that set `[cloud.compute] image` directly behave unchanged. Unit-tested end-to-end across parser/overlay/resolver/provider (aether-config 296/0, cli 531/0, environment-hetzner 68/0, jbct:check clean); cloud verification (snapshot-booted cluster, `hcloud` image-id assertion) rides the first rc3 cloud run. Provider-agnostic generalization is RFC-0016 (epic #463).
- **Deployment state now survives leader failover — `Version` persisted in canonical parseable form (#438).** `DeploymentManagerImpl.addDeploymentCommand` stored `oldVersion`/`newVersion` via the record's default `toString()` (`Version[major=…]`), which `Version.version()` cannot re-parse; on every leader change, the new leader's `restoreDeployment` parse failed and an in-flight deployment was silently dropped from `activeDeployments`. Versions are now persisted via the canonical `withQualifier()` format; round-trip and rollback-path tests guard the writer. Adjacent `Version`-bearing values (`SliceTargetValue`, `VersionRoutingValue`) use typed `@Codec` fields and were verified unaffected — the defect class is confined to this one string round-trip. (aether-invoke 208/0.)
- **pg-codegen DDL analyzer no longer mis-parses `$tag$`-quoted or `;`-containing function/trigger bodies (#408).** `postgres.peg`'s `DollarString` matched only untagged `$$…$$` and `RestOfStatement` was `;`-blind; a tagged dollar-quoted `CREATE FUNCTION` with internal `;` was mis-split at compile time. `DollarString` now matches tagged forms via backreference and `RestOfStatement` skips string/dollar spans; `$1` positional params verified unaffected. Compile-time path only — the runtime migration splitter was never affected. (pg-parser 307/0, pg-schema 112/0.)
- **H2 migrations route through the dialect-aware statement splitter (#409).** `dialectFor(H2)` previously fell back to naive `split(";")`, mis-splitting `;`-in-literal statements. H2 now has a standard lexical `DialectSpec` (`;` terminator, `ddlTransactional=true`). SQLITE deliberately remains on the naive fallback: its `CREATE TRIGGER…BEGIN…END;` needs `END;`-aware machinery the line-anchored block primitive doesn't provide — documented as an accepted limitation until demand appears. (sql-splitter 170/0, aether-deployment 749/0.)
- **Integration harness: a hard-aborted sweep can no longer exit 0, the last unbounded poll loop is bounded, and transient publish flakes retry (#460).** Suites skipped after a `restore_cluster_baseline` unrecoverable verdict are now tallied as a distinct `skipped-unrecoverable` class ([ABORT] rows, non-zero exit) instead of blending into benign skips; the `log-follower` respawn loops (the fork-leak structure behind the 5.5 h silent stall) gained a wall-clock lifetime ceiling (`AETHER_LOG_FOLLOWER_MAX_LIFETIME`, default 7200 s) — every other post-restore wait path was verified already bounded by the rc2 belt; all three 08-resources publish loops retry transient transport failures (bounded, assertion failures never masked).
- **Integration harness: CLI/node-image version-parity preflight (#440).** `run-tests.sh` now asserts `aether --version` matches the node artifact's `Implementation-Version` before any bootstrap, loud-aborting on mismatch (the class that burned a full provision cycle when a stale `~/.aether/bin` rc1 CLI shadowed the freshly built one); `AETHER_BIN` pins an explicit CLI (still checked) and the preflight banner logs which binary resolved. Verified live: the guard catches the stale-rc2-CLI-vs-rc3-jar mismatch present on the dev machine today.
- **CI: the forge-tests PR job is no longer chronically red — five CPU-heavy multi-node in-JVM probes tagged `Heavy` and excluded from the GitHub-runner job only (#458).** Root-caused by log forensics: red since 2026-06-19 — the day the first untagged heavy probe landed — from CPU starvation of 5-node in-JVM Ember clusters on 4-vCPU public runners (drifting signatures: Awaitility timeouts, a 30-min step wall, a strict all-appends-succeed assert failing under consensus contention), not a product regression. The existing `failsafe.excludedGroups=Heavy` mechanism (SliceDeploymentTest precedent) now covers `ScaleUpFiveToSevenProbeTest`, `ProvisioningRecoveryAfterFailureBurstProbeTest`, `StreamFanoutConsumerTest`, `StreamCrashDurabilityTest`, `OwnershipFenceBaselineTest`; verified the exclusion applies ONLY in ci.yml — probes keep running in local builds and the release lane. Two of the five also fail on a dev workstation and are under probe-vs-product root-cause as #467.


## [1.0.0-rc2] - 2026-07-16

### Fixed
- **JBCT formatter/linter formatting fixes (#447).** Four cases: (1) import ordering now follows the book — `java → javax → org.pragmatica → third-party (alphabetical) → project`, statics last in the same grouping — enforced identically by lint (`JBCT-STY-06`) and the formatter through one shared `ImportGroups` classifier in jbct-core (the ordering was previously defined twice, divergently, with the book contradicted by both). (2) A fluent chain whose head call wraps its arguments no longer glues the first follow-up call to the closing-paren line and mis-anchors the remaining segments at the argument column — the ≥2-follow-up special case (`FlowPrinter`) and its `postBrokenArgsAnchor` machinery are removed, so the single-follow-up rule applies uniformly: break before the first follow-up, whole chain anchored at the head call's dot column. The `MultilineArguments.chainedWithArgs` golden fixture that canonized the glued layout is updated; new `WrappedArgsChain` fixture covers return and lambda-tail positions. (3) Statement-position chains — previously never broken regardless of length because `shouldBreakChain` was purely structural — now break when their flat rendering would exceed `maxLineLength` (short chains stay flat; new `StatementChains` fixture). (4) The never-read `alignChainedCalls`/`alignArguments`/`alignParameters` config keys are removed from `FormatterConfig`/TOML (alignment is unconditional; stale keys in user configs are silently ignored). First-ever tests for `CstImportOrderingRule` + unit tests for `ImportGroups`; jbct-core 28/0, jbct-format 65/0 (24 golden idempotency fixtures), jbct-lint 185/0. Existing formatted sources are re-flagged by `JBCT-STY-06` under the new order until the post-merge repo-wide reformat sweep (tracked on #447).
- **Stream replication: a new HRW owner no longer false-promotes to empty (`CAUGHT_UP@-1`) or truncates below a surviving replica under owner-failover churn — the #445 residual (availability arm).** The single-sourced placement fix (#445) closed the acked-then-lost durability break; run-13 then isolated a residual on the new-owner promotion path where two paths trusted a source the failover had left empty. (a) A non-owner backfilling from the deterministic HRW owner promoted off the owner's *empty* catch-up response — but during failover a freshly-provisioned/re-elected HRW owner holds an empty ring while the acked history survives on a replica, so trusting its `-1` tail false-flipped the replica to `CAUGHT_UP@-1` and truncated a stream whose watermark lived on the survivor. (b) A promoted owner whose catch-up from a confirmed-ahead survivor *failed* degraded, after a bounded wait, to a self-promote at its LOCAL watermark — truncating the acked suffix the survivor held. Both now defer to the probe-gated safety invariant the cold-start path already enforces: an EMPTY owner response routes to the probe path (`PartitionBackfill.applyOwnerResponse` → `handleNoSource`) and self-promotes only when every peer is reachable and none is probed strictly ahead — so a survivor still ahead keeps self SYNCING (no truncation) while a genuinely-empty partition still promotes at its watermark; and a failed catch-up from a confirmed-ahead survivor stays SYNCING and lets the redrive retry (a transient survivor recovers on a later tick; a genuinely-dead one leaves the member view, after which the owner re-evaluates and self-promotes safely) instead of degrading to a truncating local-watermark promote. The bounded liveness escape is now reserved for the genuinely-unreachable blind-peer branch, where no peer was probed ahead. This is an availability/authority bug, not data loss (history stayed readable via the caught-up replica); (b) closes a latent truncation hazard. Proven by six deterministic unit repros (`PartitionBackfillTest`: `EmptyOwnerReadDoesNotFalsePromote`, `PromotedOwnerSurvivorCatchupFailureStaysSyncing`); aether-stream 622/0, aether-node 682/0. `PartitionBackfill`-internal — no API/wire/stored-format change. Now also validated on a real 5-node containerized cluster (integration suite 02-chaos, --env remote, 7p/0f): the RF=2/min-sync=2 stream owner was killed and the newly-elected owner served ALL 20 pre-kill acked events (the exact assertion that failed pre-fix), accepted live writes, and the replica set re-converged with RF restored. Cloud-JVM validation completed 2026-07-16: the full stream-failover script passed every assertion in two independent cloud runs (complete post-failover history, ordered live tail, RF re-converged).
- **Code generators emit valid `.all(...)` assemblies beyond the core arity-15 ceiling.** `Result.all`/`Promise.all` (and `MapperN`/`FnN`/`TupleN`) stop at 15 arguments, so generated `.all(a1..aN).map(Ctor::new)` broke or refused for N>15. Six emitters now batch components into ≤15-wide `Tuple` parts (materialized with `.id()`), join the parts, and cascade `Tuple.map` to reconstruct every value — recursively (so >225 components does not reintroduce a cliff) and, for `Promise`, launching all parts before the join so concurrency is preserved. Fixed: pg-codegen `RecordGenerator` row mapper (stale `≤11` dispatch + a broken `// TODO` nested-mapper stub) and `FactoryGenerator` `@Query` record mapper; slice-processor `FactoryClassGenerator` slice-dependency assembly (previously a hard error above 15), its transitive step-resource assembly, and its two config-section parse expressions. Output for ≤15 components/dependencies is byte-identical, so `ENVELOPE_FORMAT_VERSION` is unchanged. Verified by compiling generated code at 16/31/230 columns and a 16-dependency slice (pg-codegen 218/0, slice-processor 285/0, slice-processor-tests 46/0).
- **Cloud (Hetzner) provisioned and auto-heal-replacement VMs now retain their `aether-cluster`/`aether-role`/`aether-source` metadata labels and inherit provisioning profile + operator SSH keys across leader generations — so label-scoped enumeration/reaping and keyless replacement provisioning keep working past the first generation (#442).** Five changes: (a) both Hetzner label-update sites — node self-tag (`HetznerComputeProvider.applyTags`) and discovery registration (`HetznerDiscoveryProvider.applyRegistrationLabels`) — are now read-modify-write merges; Hetzner's label update REPLACES the whole map, so the prior partial writes wiped the create-stamped base labels down to a single key, leaving VMs the label filter could no longer find. (b) Scale-up / reprovision via the wave path now carries the real cluster name into `ProvisionContext`, so the provider stamps `aether-cluster=<real>` instead of falling back to provider-config / env / "unknown". (c) Label VALUES are sanitized to Hetzner's constraint — ≤63 chars, `[a-zA-Z0-9._-]`, alphanumeric edges. (d) Operator SSH-key ids resolved at bootstrap are rendered into each node's `[cloud.compute] ssh_key_ids` and threaded into replacement user-data, so a replacement that later becomes leader provisions its own replacements from config (with a name-prefix account-listing fallback), closing the keyless-replacement lockout. (e) Server type resolves spec `instance_type` → provider `[cloud.compute] server_type` with NO hardcoded `cx33` default — an unresolved type fails loud instead of silently mis-sizing; plus a create-time diagnostic logging the exact label map sent to Hetzner per provision. Unit-tested across `HetznerComputeProviderTest` / `HetznerDiscoveryProviderTest` / `WaveExecutorTest` / `BootstrapOverlayGeneratorTest`; cloud RF / auto-heal end-to-end validation is not asserted by these commits.
- **`consumes = "application/x-www-form-urlencoded"` is now rejected at compile time instead of silently JSON-parsing the form body (#414).** `MediaTypeTypeChecker.checkConsumes` accepted `FORM_URLENCODED` for any parameter type, but `RouteSourceGenerator.bodyBindingCall` has no FORM_URLENCODED arm — a form-consuming route fell through to `.withBody(TypeToken)` and JSON-parsed the request body at runtime. The checker now hard-errors with a named diagnostic ("consumes media type with category FORM_URLENCODED is not supported yet — form-body binding is unimplemented; use application/json or a supported type"). No generated output changed, so `ENVELOPE_FORMAT_VERSION` is untouched; full form-body binding remains a future enhancement.
- **Full-cluster cold restart no longer self-fences healthy nodes — streaming WAL crash-durability proven end-to-end (A6).** On a simultaneous full-cluster restart the cluster reaches quorum (3/5) and flips `COLD_BOOT→NORMAL` ~14s in, but SWIM's first probe-acks lag the QUIC attach, so the `QuorumLossDetector`'s SWIM-alive count momentarily decays below threshold and HEALTHY nodes **self-drained** before convergence; terminal-removal made them unrecoverable, wedging the cluster at 3/5 and (with RF=1) stranding the data-holding stream owner so post-restart reads returned empty. The quorum-loss self-drain and the SWIM never-HEALTHY FAULTY-suppression are now both gated on a bounded post-boot **cold-boot convergence window** (~75s, covering the transport's 60s force-dial), so a node will not self-fence on the transiently-low membership count while the cluster is still forming — a genuine minority still self-fences once the window elapses. Separately, app-stream `NEAREST` reads are now **local-first** (read local; forward to the partition's HRW owner only on a local miss), so a post-restart consumer on a non-owner node recovers the log from the owner while a consumer co-located with its data is never forwarded to a lagging replica. Proven by the now-enabled `StreamCrashDurabilityTest` full-cluster-restart gate (10+ consecutive green), with `StreamFanoutConsumerTest` as the delivery regression guard.
- **SWIM no longer evicts a freshly-added node before its first probe-ack — new `OBSERVED` birth state (#336).** A node added to a running cluster (core scale-up, auto-heal replacement, or worker join) was introduced into SWIM as `SUSPECT` with its suspect→FAULTY death timer armed at birth, while the first probe is gated ~10s behind `startupDelay` and the only protecting grace (`joinGrace` ≈ `startupDelay`) covered the observation stream but not the `onMemberFaulty` death-path — so a live-but-not-yet-probed joiner was declared FAULTY and evicted before it could be confirmed (regression from the 2026-06-13 failure-detector rework; the original SWIM seeded joiners ALIVE). A freshly seeded/announced member (and a gossip-SUSPECT of an unknown id) is now introduced in a new **local-only `OBSERVED`** state: known and probe-eligible, but **not** alive and **not** death-timer-armed, until a real probe-ack (or a gossiped `Alive`) promotes it to ALIVE, or a sustained probe-timeout past the join deadline escalates it to SUSPECT (then the normal co-confirmed death path). `OBSERVED` is the weakest gossip-merge priority (a gossiped `Alive` promotes it; a gossiped SUSPECT/FAULTY is ignored — our own probing decides) and is never serialized onto the wire or counted toward quorum. The born-SUSPECT join-grace band-aids it subsumes were removed; tombstone (#231), co-confirmation (#126), and Lifeguard (#94) invariants are preserved. Validated on a real Docker cluster: 5→7 core scale-up converges in ~25s with zero spurious evictions, and a full-cluster total-restart (graceful and abrupt) re-forms cleanly.
- **Established healthy peers no longer false-evicted under churn — cluster-sync missed-pong feeds SWIM as reachability evidence, not a destructive disconnect (#336, complements the `OBSERVED` birth-state fix above).** The `OBSERVED` state protects a *freshly-added* node at join; this closes the complementary case — an *established* HEALTHY peer transiently SUSPECTED under load/jitter. (1) The cluster-sync app-level liveness path (RC1 "S01") disconnected a peer and broadcast an eviction hint on `pingTimeoutThreshold` missed pongs while it was merely SWIM-SUSPECTED; it now feeds a transport-unreachable hint into SWIM (`recordTransportHint(PeerUnreachable(PING_TIMEOUT))`), which drives the existing SUSPECT → 3s-floored-FAULTY → DepartedObserved → DEAD pipeline and is **refuted** when pongs resume — so a transient flap no longer evicts, and the destructive `network.disconnect` + eviction-hint broadcast (an S20 self-drain-cascade trigger) is gone. (2) SWIM refutation hardening: at-risk proactive self-refutation (advance own incarnation on inbound-reachability silence, so a healthy peer out-ranks an unheard `Suspect(self)` the failing link never delivered); a co-confirmation kill-gate (≥2 distinct accusers OR a transport-unreachable hint) before terminally departing an ever-HEALTHY peer on one prober's first-hand FAULTY; `log(N)` suspect-window scaling. Validated on a real remote cluster (02-chaos kill suite: a killed node is removed from membership in ~6s and the cluster re-elects/fails-over). The in-JVM `CommunityFormationProbeTest` is not a valid gate for this (single-JVM CPU starvation makes nodes genuinely unreachable); validate on real infra.
- **HTTP route selection is now arity-aware — an exact collection route is no longer shadowed by a sibling `/{param}` route (#343)** — a slice declaring both `GET /items` (collection) and `GET /items/{id}` (item) previously had the collection route mis-dispatched to the param handler: both normalize to the same `RequestRouter` base-path key, and `findFallbackRoute` returned the first spacer-free candidate by registration order, ignoring arity — so `GET /items` failed with `500 "Unknown request path"`. `RequestRouter` now disambiguates spacer-free candidates by path arity (new `Route.pathParamCount()`): a request whose trailing remainder has N segments selects the candidate declaring N path params. The single most common REST pairing (list + detail) now works. Surfaced by the new `examples/catalog` showcase — no existing slice paired the two shapes, so it was untested.
- **Slice-processor no longer drops a static path segment that follows a path parameter (#344; envelope 1004 → 1005)** — a route like `GET /items/{id}/image` or `GET /orders/{orderId}/items/{itemId}` was generated with everything after the first `{` truncated (`RouteDsl.basePath()` cut the path at the first param), so `/items/{id}/image` collapsed onto `/items/{id}` (colliding with the item route) and `/orders/{orderId}/items/{itemId}` silently lost its middle `items` segment. The generator now emits the full interleaved path — each static segment after the prefix becomes a `PathParameter.spacer("seg")` in the `.withPath(...)` chain (in path order), with the handler lambda binding spacer slots to `_` and the delegate receiving only the real params. The same fix corrects the equivalent `POST /{shortCode}/click` shape in `examples/url-shortener` (verified against rc2; it inherits the fix when it rebuilds off rc2 — it currently pins rc1). `ENVELOPE_FORMAT_VERSION` bumped 1004 → 1005 (runtime accepts `{1000…1005}`). Surfaced by `examples/catalog`'s binary `GET /items/{id}/image` route.
- **Bootstrap operator API key is now ADMIN, not VIEWER (#290)** — the key `aether cluster bootstrap` generates and persists to `~/.aether/clusters/<name>/api-key` is now created with the `ADMIN` role. Previously the CLI omitted the role on the key-creation request, so the server applied its least-privilege `VIEWER` default and the operator's own bootstrap key was rejected (`403`) from every ADMIN/OPERATOR endpoint (auto-heal toggle, deploy, drain, …). The server-side `VIEWER` default for unspecified roles is unchanged — only the bootstrap operator key is explicitly elevated.
- **Scheduled-task pause/resume routed to the leader for list read-after-write consistency** — `POST /api/scheduled-tasks/pause` and `/resume` were placed on the STRATEGIES task-group owner while `GET /api/scheduled-tasks` (list) is leader-bound, so a pause that committed on the owner was not yet reflected when the immediately-following list read hit a different node. Both now route to `LEADER` (matching list/state), so a post-pause readback reflects `paused=true` immediately (0s, was a 30s timeout on cloud). The pause write still applies cluster-wide via consensus; only request placement changed.
- **Migration DDL and its `aether_schema_history` row now commit in the same transaction on transactional dialects (PostgreSQL/DB2/SQL Server/H2/SQLite), closing an applied-but-unrecorded window on process crash (#338).** Previously, a crash between DDL commit and the subsequent history INSERT could leave a migration applied but unrecorded, causing schema drift. Proven by a real-PostgreSQL crash-injection test: last-statement failure leaves neither the schema change nor the history row.
- **Quorum-loss self-drain re-feeds the detector on the `Member`-boundary FSM edge** — the quorum-loss detector is now refreshed on every membership-FSM transition that crosses the exact-`Member` boundary (Member→Suspect and the Suspect→Member refutation), not only on the 15s presence down-hysteresis crossing and the DEAD edge. When multiple cores were lost simultaneously, a survivor's strict member count dropped internally but the detector kept reading the stale count for far too long, so the minority never recognized quorum loss and never self-drained — a leaderless wedge. The `splitTimeout` drain window, refutation-cancel, and the co-confirmation gate still guard against transient SUSPECT flaps.
- **Auto-heal replacement nodes advertise a routable address** — a node now resolves its advertise host at boot (explicit `AETHER_ADVERTISE_HOST` / `--advertise-host=` override → SWIM `WhoAmI` source-IP reflection off a seed → loud hostname fallback) and builds its self `NodeInfo` once with that address, instead of `InetAddress.getLocalHost().getHostName()`. On cloud (Hetzner) a CTM-provisioned replacement previously advertised an unresolvable container/VM hostname, so peers' SWIM probes failed DNS resolution, suspicion accrued, and the replacement was killed within ~15s of joining — the cluster flapped and never re-reached a stable size, so auto-heal never completed. The resolved address propagates immutably to SWIM, QUIC, consensus, and the DHT. Bootstrap (self present in 3-part PEERS) is byte-for-byte unchanged.

### Added
- **Slice-processor now emits a loud compile-time WARNING when a locally-installed processor jar is older than its own source tree — turning a silent stale-`~/.m2` regression into an actionable message (#403).** Consumers reference the processor through `annotationProcessorPaths`, so `-am` never rebuilds it and a stale jar in `~/.m2` can silently reintroduce an already-fixed codegen bug (green→red with nothing pointing at the cause). A new `StalenessGuard` compares the processor's embedded `BuildInfo.BUILD_TIMESTAMP` against the newest mtime of the `jbct/slice-processor/src/main` tree plus the module `pom.xml` (located by walking up from `user.dir` to the monorepo-root marker); when the source out-paces the installed jar it prints `Diagnostic.Kind.WARNING` naming the remedy (`mvn install` in `jbct/`). It degrades to silence in every uncertain case — unresolved build stamp, source tree absent (any consumer built outside this monorepo), or an unreadable filesystem — so it never fails a build and never warns an external consumer with no `jbct/` tree to rebuild. Unit-tested (`StalenessGuardTest`, `SliceProcessorTest`). No generated output changes, so `ENVELOPE_FORMAT_VERSION` is untouched.
- **Per-slice autoscaler bounds + observability: blueprint `maxInstances` / threshold overrides (#424) and the `SCALE_CAPPED` event + per-slice decision snapshot (#425).** Blueprint `[[slices]]` entries accept optional `maxInstances`, `scaleUpThreshold`, and `scaleDownThreshold`; they flow SliceSpec → ResolvedSlice → `SliceTargetValue` (three trailing additive KV fields — legacy 5/6-field values still parse, no envelope bump) → the leader blueprint. Deploy rejects `maxInstances < instances` and warns when an autoscaled slice has no `maxInstances`. The leader's cap now honors `maxInstances` **before** the cluster-size cap, and `DecisionTreeController` uses per-slice threshold overrides with the cluster `ScalingConfig` as the default tier. When demand is swallowed by either cap, a `ScaleCapped` scaling event bridges to a new `ClusterEvent.ScaleCapped` (the 33rd closed-set event). A per-slice decision snapshot (outcome + guard reason: window-not-full / in-progress / cooldown / max-instances / cluster-cap / error-block, plus the current per-slice load factor and cluster-CPU node-capacity context) is held in the control loop and exposed via a new management triad: REST `GET /api/controller/decisions` (leader), CLI `aether controller decisions`, and reference docs.
- **Worker governor announces its community membership on every SWIM observation edge — the community-formation loop is now closed (#241, slice 3).** A worker node's SWIM observation listener now re-reads its ALIVE SWIM set on each edge, narrows it to the worker's OWN community via `CommunityMembershipFilter` (matching each member against its committed `AetherValue.ActivationDirectiveValue.communityId` read from the consensus-backed `KVStore` snapshot — NOT SWIM `SwimMember` source-labels, which are empty for gossip-learned peers and would silently drop community members), and hands the community-scoped slice to the election-gated `GovernorAnnouncer.onMembershipChange`. The committed `communityId` is threaded from the `ActivationDirectiveValue` into `activateWorkerMode` (authoritative consensus identity over emergent group-tracker state), and the announcer applies through the `forwardingClusterNode`, not the raw cluster node — a worker is observation-only and cannot drive consensus locally, so the `GovernorAnnouncementKey` Put is relayed to a core peer instead of being dropped as a local apply. A new read-only, hot-path-free `CoreSwimHealthDetector.aliveMembers()` projection feeds the loop. Election/idempotency (duplicate-announce, governor-change, worker-death) is handled at the `GovernorAnnouncer`/`GovernorElection` layer; `CommunityMembershipFilterTest` pins the filter and self-election cases (incl. follower-no-write), and the in-JVM `CommunityFormationProbeTest` stays `@Disabled` (single-JVM CPU starvation makes it an invalid gate; validated on real infra instead). **Deferred (tracked on #241):** the announcer is still constructed with `observedCoreEpochSupplier = () -> Epoch.ZERO`, so the now-live loop feeds the leader FSM un-fenced announcements (a stale-governor announcement can't yet be epoch-rejected) — to be resolved alongside the governor read-source swap; and `CommunityMembershipFilter.communityAliveMembers` does a full `kvStore.snapshot()` scan per SWIM edge (O(KV size) under churn), fine at current scale, flagged for later.
- **Runtime-switchable per-injection-point observability — one engine, ambient + surgical (#277).** System observability is now a single runtime engine: each cross-slice / topic / timer / HTTP-entry dispatch seam carries an `ObservabilityStrategyCell` (one `AtomicStrategy` volatile lambda per `artifactBase/methodName`), and the write-side `ObservabilityConfigRegistry` pre-composes the KV config into an "around" strategy and swaps it in wholesale on a config change (push-on-event; the hot path never does a registry lookup). The retired `ObservabilityInterceptor` fleet layer is **absorbed** into a **baseline** posture: an injection point with no config runs the ambient facets (depth-leveled logging, sampled tracing into the same `InvocationTraceStore` the `/api/traces` routes read, invocation counting; spans off) — "off means baseline, not blind" — so HTTP entry points, which bypassed the old interceptor, gain ambient observability for the first time. An explicit non-off config runs **only the facets its toggles select** (`logging`/`metrics`/`tracing`; `spans` reserved, #304), composed from the **same** facet bodies as the baseline at the config's own depth; an explicit all-off config is **identity** (one volatile read — surgical darkening). Configs resolve through a **scope hierarchy** — method → artifact (`*` method) → global (`*/*`) → baseline, nearest scope wins whole (never a per-field merge). The `/api/observability/depth` routes are re-backed by this unified store (`ObservabilityDepthKey/Value` retired): a depth-set **materializes** a method-scope config pinning the baseline-equivalent toggles with the new depth, so setting a depth never darkens a point. New management triad: REST `GET/POST/DELETE /api/observability/config` (+ single-point `GET .../config/{artifactBase}/{methodName}`, ADMIN for writes, viewer for reads), CLI `aether observability config` / `config-get` / `config-set` / `config-remove`, and reference-doc coverage of the effective-state (`baseline`/`configured`/`darkened`) + scope + materialization semantics. Mechanism is dispatch-seam strategy cells, **not** the originally-planned generated-wrapper codegen (spec §5/§11 superseded; no envelope bump).
- **One boundary-neutral value-object descriptor `ValueMapping<T,P>` — value objects now bind to HTTP path/query segments with a typed 400 on bad input (#397; envelope 1006 → 1007).** A value object declares exactly one `static ValueMapping<Self, P> valueMapping()` — the pair of functions it already owns: `lower : T -> P` (total unwrap) and `lift : P -> Result<T>` (fallible, parse-don't-validate re-parse) — carrying no DB/HTTP/wire dependency, only `Fn1` and `Result`. This is the boundary-neutral generalization of the just-shipped `PgRepr<T,P>` (#388): `PgRepr` is **absorbed** (renamed + moved to `org.pragmatica.aether.slice.mapping.ValueMapping`, convention `pgRepr()` → `valueMapping()`, resolver `PgReprResolver` → `ValueMappingResolver`), and the DB single-column bind/decode path is behaviour-identical — a rename, not a semantics change. The new capability closes the HTTP gap: previously a value-object-typed path/query segment silently fell back to `aString()` and was never lifted, so a bad value was not rejected. The slice-processor now discovers a path/query parameter's `valueMapping()` and generates a composed parser — `PathParameter.a{P}().mapped(Vo.valueMapping().lift())` / `QueryParameter.a{P}(name).mapped(Vo.valueMapping().lift())` — so the framework owns the transport-specific `String -> P` leg (new `PathParameter.aUuid()` / `QueryParameter.aUuid(name)` + a `mapped(...)` combinator) and the value object contributes only its `P`-level `lift`; no transport concept (String, PathParameter, HTTP status) ever leaks into value-object code. A malformed primitive **or** a rejected `lift` both surface as a typed `HttpError` **400**, never a 500 and never a silent raw string. A value object whose `P` is outside the supported HTTP primitive set (`String`, `Integer`, `Long`, `Boolean`, `Double`, `BigDecimal`, `LocalDate`, `LocalDateTime`, `UUID`) is a compile error. Generated `*Routes` output changed → slice-envelope format version bumped 1006 → 1007 (runtime accepts `{1000…1007}`). Composite / multi-primitive value objects and the facts (codec) boundary are deferred to rc3 (documented future work in the spec).
- **First-class typed topics with a single-source `Topic<T>` constant (#396; envelope 1005 → 1006)** — a pub/sub topic's name and payload type are now declared exactly once as a `static final Topic<T>` constant (`org.pragmatica.aether.slice.topic.Topic`, `TypedPublisher`, `TypedSubscriber`) in a shared interface both the publishing and subscribing slices can see. `@ResourceQualifier(config = "CLICK_EVENTS")` names that constant's Java identifier (not the topic string): the slice processor scans the compilation round's `static final Topic<?>` fields, wraps the provisioned `Publisher` in a `TypedPublisher` bound to the constant at the provide site, and generates the topic name into each slice manifest (`publish.topic.{i}.topicName` / `reactive.{i}.topicName`, extracted from the constant's `Topic.of("...", ...)` initializer via the Compiler Tree API). Two compile-time diagnostics are emitted via the Messager: `config` naming no visible `Topic` constant, and an injected `Publisher<T>` / handler parameter type that does not match the constant's declared payload type. At runtime the topic address is resolved off the generated manifest name — the subscription side reads `reactive.{i}.topicName` directly, and a typed publisher whose section is absent from `resources.toml` defaults to a topic named after its provisioned section — so the author no longer writes a `resources.toml [section] topic_name`. Dispatch is additive: an UPPER-first `config` is a constant reference (resolve-or-error), while a lowercase/hyphenated `config` stays on the legacy resources.toml section path, so existing slices are unchanged. `examples/url-shortener-v2` migrated to a shared `Topics.CLICK_EVENTS` constant referenced from both slices with its `[click-events] topic_name` removed. Generator output structure changed (typed-publisher wrap + `topicName` manifest keys) → slice-envelope format version bumped 1005 → 1006 (runtime accepts `{1000…1006}`). — a served slice version now emits standard lifecycle response headers (`VersionResponseHeaders`): `Deprecation: true` when the served version is marked `deprecated`, `Sunset: <RFC 1123 date>` when it declares a `sunset` date, and `Link: <{apiPrefix}/v{L}/{path}>; rel="successor-version"` pointing at the highest non-deprecated successor when one exists. Usage is observable through three Micrometer counters (`VersioningMetricsSink` / `AetherVersioningMetricsSink`): `http.requests.versioned{slice,version,method,status}` per versioned request, `api.versioning.deprecated.requests{slice,version}` when a deprecated version is served, and `api.versioning.missing.header{slice}` per header-mode request with no version header. The versioned slices a node hosts and their lifecycle state are introspectable at `GET /api/versions` and via the `aether versions` CLI. This lands the header emission deferred from the Phase-1+2 entry below.
- **Deploy-either-way API versioning: HEADER mode (#198 §7)** — the SAME compiled slice now serves its versioned routes in **path mode** (`{apiPrefix}/v{N}/{path}`, the default, unchanged) OR **header mode** (versions share the bare `{apiPrefix}/{path}` and the version is selected from a request header), chosen by a cluster-level deploy-time setting — no recompile. New `[app-http]` config keys `api_versioning_detection` (`"path"` default | `"header"`) and `api_version_header` (default `API-Version`) thread through `AppHttpConfig` → `AetherNode` → `HttpRoutePublisher` as a `RouteMountMode`. The generated `routes()` no longer bakes the path-mode mount; it returns un-mounted routes (bare path + `.versioned(N)` metadata) and a `create(slice, jsonMapper, RouteMountMode)` factory method, and the registration consumer composes the mounted paths ONCE per mode — feeding the SAME composed routes to both the local `SliceRouter` dispatcher and the wire route-table extractor so they agree. Header-mode dispatch selects the version per the pure, unit-tested `VersionSelector` (#198 §7 policy): header present + known → that version; present + unknown / non-numeric → `404`; absent + `requireVersionHeader` → `400` naming the header; absent + `defaultIfMissing` → that version; absent + no default → highest declared (latest-wins). Per-slice override of the detection mode is a documented follow-up; cluster-level is the rc2 scope. Generator output structure changed (un-mounted `routes()` + new factory method) → slice-envelope format version bumped 1003 → 1004 (runtime accepts `{1000, 1001, 1002, 1003, 1004}`). Proven by the in-JVM `SliceVersioningTest` (path mode, still 3/3) and a new `SliceVersioningHeaderModeTest` deploying the same two-version slices in header mode and asserting header→version selection over real HTTP, plus the strict (`requireVersionHeader = true`) 400 gate.
- **API path-mode versioning for slice HTTP routes (#198, Phase 1+2)** — a `routes.toml` may now declare an `[api]` section (`prefix` = version-agnostic base, `requireVersionHeader` parsed+stored for a later phase) plus per-version `[vN.routes]` blocks and `[vN]` metadata blocks (`deprecated`, `sunset` (RFC3339, validated now), `defaultIfMissing`). Each bind key resolves to a slice method by decision D8 (`get` → `getV{N}`, or an explicit inline-table `method = "..."` override) and the route mounts at `{api.prefix}/v{N}/{path}` bound to that handler — version is just a path segment, so runtime route matching is unchanged. Flat `[routes]` + top-level `prefix` still means an unversioned slice (byte-for-byte unchanged); mixing the two schemas is a compile error. Five compile-time checks (schema mixing, duplicate bind key within a version, >1 `defaultIfMissing`, unparseable `sunset`, unresolved `(vN, bindKey)` method) are extracted into the unit-tested pure `VersionSchemaValidator` and reported via the Messager. The manifest gains version metadata (`versions.count`, `api.prefix`, `api.requireVersionHeader`, per-version `deprecated`/`sunset`/`defaultIfMissing`) for later phases. Header-mode version extraction and Deprecation/Sunset/Link header emission are deliberately deferred (metadata is parsed+stored now). Generator output structure changed (more `/vN/` Route entries + `getV{N}` handlers) → slice-envelope format version bumped 1001 → 1002 (runtime accepts `{1000, 1001, 1002}`). Proven by an in-JVM Forge test deploying a two-version slice and asserting that both `GET /api/orders/v1/{id}` and `GET /api/orders/v2/{id}` serve their version-specific responses.
- **`produces`/`consumes` media types for slice HTTP routes (#339)** — a `routes.toml` route entry may now be an inline table `{ route = "POST /export", consumes = "application/json", produces = "text/csv", security = "public" }` declaring request/response media types (decision D2: single each; bare-string and `[dsl, security]` array forms still mean JSON in/out, byte-for-byte). The slice-processor resolves each media type to its `CommonContentType` constant (or the `ContentType.contentType(...)` escape hatch for vendor/parametrized types) and emits the declared output `.as(...)` plus the consumes-appropriate body binding — `.withStringBody()` (TEXT/HTML/XML), `.withByteBody()` (BINARY), `.withMultipartBody()` (MULTIPART), or the existing `.withBody(TypeToken)` (JSON). Output already flows through the unified `ResponseSerializer`, so a `produces = "application/octet-stream"` route returns its `byte[]` verbatim under the binary Content-Type (proven by an in-JVM Forge test asserting verbatim bytes + header). A strict (D3) compile-time check (extracted as the unit-tested `MediaTypeTypeChecker`) hard-errors when a declared media category is incompatible with the method's Java types (e.g. `produces = "text/csv"` on a non-`String`/`byte[]` return, or `consumes = "multipart/form-data"` on a non-`MultipartRequest` parameter). Generator output structure changed → slice-envelope format version bumped 1000 → 1001.
- **Resumable migrations on autocommit dialects (MySQL/Oracle): `aether_schema_history` gains `status` (SUCCESS/IN_PROGRESS/FAILED) + `statements_completed` via an internally-versioned, self-evolving history table; a partially-applied migration resumes from its last checkpoint instead of replaying from the first statement (#338).** The history table self-evolves via a meta-version table + ordered, version-gated ALTER steps (no reliance on non-portable `ADD COLUMN IF NOT EXISTS`). On re-entry, the resume gate skips durably-committed statements after re-validating the stored checksum; `queryApplied` filters to `status='SUCCESS'` so a partial row never counts as applied. A crash between an autocommit statement's commit and its checkpoint update re-attempts that one boundary statement on resume — idempotent ⇒ harmless, non-idempotent ⇒ marked FAILED for operator intervention (inherent to non-transactional migrations). Transactional dialects are all-or-nothing (see Fixed above).
- **Membership diagnostics endpoint + CLI (`GET /api/cluster/membership`, `aether cluster membership`)** — a read-only, per-node-local observability surface (not leader-forwarded) exposing the responding node's authoritative `MembershipFsm` lifecycle view (per-peer state / incarnation / role / strict-core / counted membership) plus its quorum-loss self-drain readiness (`strictCoreMemberCount`, `requiredThreshold`, `belowThreshold`, `armed`). Diagnoses SWIM-under-concurrent-loss — per survivor, which peers are SUSPECT/DEAD and whether the node's self-drain window is armed and below threshold. CLI renders a per-peer table plus the summary counts.
- **SWIM address reflection (`WhoAmI` / `WhoAmIReply`)** — a joining node can ask a seed which source address its datagram was observed from, used to self-resolve a routable advertise address with no provider-specific metadata. CTM replacement user-data additionally exports `AETHER_ADVERTISE_HOST` from the VM's routable IP (`ip route get`) for both container and JVM runtimes.
- **Dialect-aware SQL migration statement splitter (new `aether/pg-tools/sql-splitter` module, #337)** — one pure lexer engine + per-dialect descriptors for PostgreSQL, MySQL/MariaDB, DB2, SQL Server, and Oracle. Handles dollar-quoting (`$$`/`$tag$`), `DELIMITER` switching, `--#SET TERMINATOR`, `GO` batch separator, `/` PL/SQL block terminator, Oracle alt-quoting (`q'[…]'`), and `COPY … FROM STDIN` data blocks. PostgreSQL and MySQL validated against real database containers.

### Documentation
- **`examples/catalog` — media-types + API-versioning worked example** ([`examples/catalog`](examples/catalog/README.md)) — a new in-reactor single-slice example demonstrating #339 + #198 end-to-end: ONE deployable artifact serving `v1` (deprecated, emitting `Deprecation`/`Sunset`/`Link` headers) and `v2` simultaneously, a `text/csv` export, an `application/octet-stream` binary passthrough, a `text/csv` import, and the cluster-level path-vs-header detection switch. Explicitly distinct from `url-shortener`/`url-shortener-v2` (which demonstrate *deployment* versioning — two artifacts the cluster swaps). Live-deployed on a 5-node Forge cluster, it doubled as the regression proof that surfaced and validated the two routing fixes above. Cross-linked from the API Versioning & Media Types guide.
- **API Versioning & Media Types slice-developer guide** ([`aether/docs/slice-developers/api-versioning-and-media-types.md`](aether/docs/slice-developers/api-versioning-and-media-types.md)) — a cohesive user-facing guide for the #339 media-type and #198 versioning features: declaring `produces`/`consumes` (recognized `CommonContentType` set + escape hatch + the strict compile-time type rule), versioning a slice (`[api]`/`[vN.routes]`/`[vN]` schema, `getV{N}` auto-suffix + `method` override, per-version media-type divergence, schema-mixing error), cluster-level detection mode (`api_versioning_detection` path/header + the header-mode selection policy), the deprecation lifecycle (`Deprecation`/`Sunset`/`Link` headers, the three versioning metrics, `GET /api/versions`), and migration (promoting an unversioned slice + the path-mode URL-break trade-off). Linked from the slice-developers README index; the feature-catalog #198 entry is promoted Partial → Complete.

### Changed
- **Autoscaler is now per-slice: cluster-average CPU dropped as a scaling trigger, fixing the cross-slice mis-attribution defect (#422/#423).** Previously `DecisionTreeController` scanned every node's `method.*.calls` gossip and issued a `ScaleUp` for whichever artifact was under evaluation, so load on one slice amplified an unrelated idle slice (#422); cluster-average CPU compounded this by construction (a node metric applied to every blueprint). CPU is dropped as a trigger — its `ScalingConfig` weight is redistributed to ACTIVE_INVOCATIONS/P95 (error rate stays a gate). Each node now publishes real per-artifact metrics (active invocations, worst-method p95/error, calls) from its `InvocationMetricsCollector` via the `WorkerMetricsAggregator` → `CommunityMetricsSnapshot` carrier; the leader maintains **per-artifact** `MetricWindow`/`CompositeLoadFactor` state and `DecisionTreeController` consults only the target artifact's own windows (evaluate per method, actuate per artifact — worst-sustained method drives). Raw `method.*` gossip parsing is removed from the decision path (`inv|` gossip stays for observability). Proven by unit red/green attribution tests and an in-JVM forge probe (two slices → two separate per-artifact decision records, no idle-slice mis-scaling).
- **Integration harness: restore-baseline READY barrier is advisory, not a counted failure (#17)** — `restore_cluster_baseline`'s "N+ cores reporting READY" step is a documented soft barrier, but it used `wait_for`, which emits a counted `[FAIL]` on timeout. `ready_core_count` (the SWIM-fed lifecycle projection) lags the leader's authoritative membership by minutes on cloud, so the barrier false-failed cluster-B chaos suites while the authoritative deficit gate (step 8) reported the cluster whole. It is now a non-counting advisory poll (warns, never fails); the leader deficit gate remains the terminal barrier.
- **Integration harness: cloud-aware sustained auto-heal window** — the 02-chaos auto-heal assertion now uses a cloud-aware catch-window (a VM replacement needs ~3 min vs. seconds for a container) and requires the recovered member count to be sustained, eliminating transient flap-to-target false passes.
- **Integration harness: `restart_all_nodes` reliably recovers cluster B after a full self-drain (S20).** After the S19 3-kill + 2-survivor self-drain, the S20 recovery `docker compose down -v && up -d` intermittently returned rc=0 while starting only a **minority** (observed 2/5) of containers, leaving the cluster sub-quorum with no leader — so S20 timed out (348s) and every later cluster-B chaos file cascade-failed at its 5-node precondition. Two fixes to `restart_all_nodes`: (1) pre-clean by **name prefix `aether-b-node-`** instead of the `aether.provisioned-by=ctm`+`aether.cluster=b` label filter, which missed CTM auto-heal replicants mislabeled `aether.cluster=default` (`DockerComputeProvider.clusterOrDefault` fallback) — they outlived `down -v` and squatted a node's name/alias; the prefix matches every compose node AND CTM replicant and cannot match cluster A (`aether-a-node-*`). (2) A **container-count guard** that verifies N `aether-b-node-` containers are running via docker directly (authoritative while the management API is sub-quorum), force-recreates once on a shortfall, then fails loud with per-container status + tail logs. `--remove-orphans` is deliberately NOT used — clusters A and B share one default compose project, so it would delete cluster A + forge-postgres. Validated on real infra (`--env remote --suites 02`): S20 recovers 5/5 in ~30s (guard's force-recreate rescues the flaky `up -d`).
- **`AetherSchemaManager` migration execution now uses the dialect-aware splitter (#337)** — PG-family, MySQL/MariaDB, DB2, SQL Server, and Oracle all run through `StatementSplitter` with a 2-mode transaction strategy (transactional for PG/DB2/SQL Server; autocommit for MySQL/Oracle, and for any file containing a non-transactional statement). Fixes mis-splitting of function/procedure bodies containing internal `;`. H2 and SQLite retain prior behavior (no regression).
- **Versioned slice routes carry version as metadata; mounted path composed at registration time (#198 §6.4)** — the `/v{N}/` segment is no longer baked into route paths during code generation. A generated versioned route now keeps its un-versioned path and carries `Route.version() = N`; the generated `routes()` composes the mounted path `{apiPrefix}/v{N}/{path}` at route-registration time via `Route.mountInPathMode(...)`, defaulting to **path mode** so the wire behavior is byte-for-byte identical to the previous baked form (proven by the in-JVM `SliceVersioningTest`, still 3/3). This lets the SAME compiled slice be exposed in either path mode or header mode as a deploy-time setting (header-mode dispatch is a separate next step). The generated `{Slice}Routes` gains a `versionRegistry()` override (`SliceVersionRegistry`: `apiPrefix`, declared version set, `defaultIfMissing` version, `requireVersionHeader`, per-version `deprecated`/`sunset`); the manifest gains per-route `route.N.version`. `RouteConfig` carries the per-handler version and leaves `prefix` empty for versioned slices (`apiPrefix` carries the base). Unversioned slices are unchanged. Generator output structure changed → slice-envelope format version bumped 1002 → 1003 (runtime accepts `{1000, 1001, 1002, 1003}`).
- **Stream replication: two-knob durability model (`replicas` + `min-sync-replicas`, Kafka `min.insync.replicas` semantics) — #262.** `replicas` sets the replication factor; `min-sync-replicas` (counts the owner) makes a publish await `min-sync − 1` peer acks. Fixes a prior off-by-one that made synchronous app-stream replication unusable, wires `/api/streams/publish` to honour it, and adds a catch-up-before-serve owner-promotion gate. #262 stream failover convergence complete: reconcile-on-stream-config edge; promoted-owner watermark reseat + backfill ack; fresh-HRW-owner catch-up via peer-watermark probe; recovery appends adopt committed owner epoch (was Epoch.ZERO, fenced). Suite 02 fully green on real infra (replica-failover 9P/0F, lossless failover, RF restored).

## [1.0.0-rc1] - 2026-06-13

### Added
- **Cloud bootstrap zone fallback (multi-zone provisioning)** — a cloud `[source.X]` may now declare an ordered `zones = ["fsn1", "nbg1", "hel1"]` list; when a zone runs out of capacity (Hetzner `412 resource_unavailable` "error during placement"), the bootstrap PROVISION phase rotates to the next zone and retries that node instead of aborting the whole cluster. A per-role-group cursor means once a working zone is found, subsequent nodes start there and skip known-full zones (a cluster may legitimately span zones — desirable for resilience). Capacity failures are now a distinct `EnvironmentError.CapacityUnavailable` (mapped from the Hetzner `resource_unavailable` code), so the retry is scoped to genuine capacity exhaustion — auth/quota/other provision errors still fail fast without wasting attempts across zones. Backward-compatible: a single `zone = "..."` (no `zones`) behaves exactly as before (one attempt), and a source with neither uses the provider's default region. Auto-heal replacement provisioning (`provisionReplacement`) now zone-rotates too — see the #334 entry below.
- **`jbct doc <ClassOrPackage>` — source-anchored documentation extraction** — prints the class-level markdown-javadoc header of any Pragmatica Core class or package (`jbct doc Verify`, `jbct doc org.pragmatica.lang.vo`, `jbct doc Result --api` for a heuristic public-API listing). Resolution chain: explicit `--jar` → pragmatica repo checkout (walk-up from cwd) → latest/`--use-version` sources jar in `~/.m2`; not-found exits 1 listing the attempted chain. Business logic (`SourceTarget`/`HeaderExtractor`/`SourceResolver` + sealed `DocError`) lives in jbct-core with 13 unit tests; the picocli `DocCommand` is a thin wrapper. Built for the source-anchored skill-chapter model: class headers are the single source of truth for API documentation, and this command is how agents and developers read them.
- **Skill-grade source headers across core (docs-as-source chapters)** — class-level headers upgraded to self-sufficient reference chapters: `Verify` (ensure overload families, full `Is` predicate catalog, `ensureOption` contract, `combine`), `Contract`/`TerminalOperation`/`NullReturn` (intent-annotation decision procedures; `Contract` doc now states the actual lint semantics — blanket rule exemption), `Result`/`Option`/`Promise` (combinator maps grouped by purpose, incl. the `mapWith` family and gating-vs-observing semantics), and new package catalogs: `vo/package-info` (5 built-in VOs + check-before-hand-rolling rule), `parse/package-info` (7 exception-safe parsing wrappers), `utils/package-info` (failure vocabulary, resilience family, memoization, scheduling). These headers are the canonical chapters consumed via `jbct doc`. Validated end-to-end 2026-06-12: a blind jbct-coder run resolved them through the `~/.m2` sources jar and used built-in VOs, the `Verify.Is` catalog, and write-time `@Contract` correctly on first delivery.
- **Context-preserving pipeline combinators in core (`mapWith` family)** — `Result`/`Option`/`Promise` each gain six combinators: `mapWith(operation, factory)`, `flatMapWith(operation, factory)`, `ensureWith(operation)`, plus field-scoped overloads taking a `getter` projection so operations are written against their narrow natural input and wired per-stage with a record accessor reference. One pipeline stage = run an effectful operation, then combine the **original** value with the operation's result via a factory: `mapWith` takes a pure factory, `flatMapWith` a fallible one (parse-don't-validate stage constructors), and `ensureWith` discards the operation's result and gates the chain on its success — the previously missing *fallible, gating* counterpart to `onSuccess`. The factory slot's `(T, B) -> U` shape matches the canonical constructor of knowledge-accreting stage records (`record Stage<T>(T request, Knowledge k)`), so a well-shaped stage is a single lambda-free line of method references. Purity is encoded in method names rather than overloads (erasure makes `Fn1<B, A>` / `Fn1<Result<B>, A>` same-name overloads ambiguous for implicitly-typed lambdas); deliberately no multi-getter arities — multi-projection decomposition remains `all(...)`'s job. 34 new unit tests including method-reference-only stage-accretion inference proofs on all three carriers; core suite 812/812 green. Pattern reference: `core/docs/knowledge-gathering-pipelines.md`.
- **Fenced leader writes + activation-gated KV notification replay (overhaul Wave 8: H4, M5/§5.8-AMENDED)** — `LeaderValue` now carries the election `viewSequence` and the KV applier enforces compare-and-put: a `LeaderKey` write applies only when strictly greater than the stored sequence (deterministic in the replicated applier; rejected writes mutate nothing and emit no notification), so a minority-flapped node's stale election can never overwrite the legitimate leader. Election proposals are baseline-anchored (`nextViewSequence` = observed-committed + 1, advanced only by observing commits — retries cannot inflate a flapped node's sequence past the majority's commit; same-baseline peers produce identical commands for the consensus BatchId dedup the cold-boot parallel-proposal path relies on). **Boot ordering inverted per the user-ratified §5.8 amendment — `sync → activate → replay`:** snapshot install populates KV silently; the engine activates; then `KVStore.replayNotifications()` fires the deferred burst as the FIRST work on the apply path (one synthetic put per key, diff-replay against the last-replayed view for mid-life installs: puts for new/changed, removes for vanished; mutation-free — replay never traverses `handlePut`, so the H4 fence never sees it). Invariant bought: **a KV notification structurally implies an ACTIVE engine** — no consumer checks activation status, ever. Deleted as subsumed: the consumer-side `ActionLog` (the first Wave-8 cut; worked as built, superseded structurally), `GossipKeyRotationHandler.replayFromStore` (rotations now arrive as replayed puts through the normal subscription — late-joiner decrypt by construction), and the per-consumer activation guards (~460 lines net removed). `NodeDeploymentState.handleActive` self-heals un-loaded KV-claimed ACTIVE self-instances (M5 phantom-ACTIVE healed by construction, fed by replay). The election-from-`MembershipDecision` item (audit 5) takes the spec's named fallback — transport feed for liveness, the fence closes the minority-flap write path (delta feed would invert the consensus→aether dependency). Docker-validated: re-election in **2s** after leader kill, full-cluster-wipe reformation < 60s, 03-scaling 3p/0f, 13-edge-cases 4p/0f; unit: fence applier accept/reject + codec round-trip, replay ordering/mutation-freedom/diff-replay, gossip-key late-joiner, viewSequence seeding via replayed put. Spec: `cluster-topology-overhaul-spec.md` Wave 8 + §5.8 (AMENDED 2026-06-11).
- **Membership FSM completeness + parallel-view collapse (overhaul Wave 7)** — `MembershipFsm` is now the sole membership authority with no inescapable states: DEPARTING gains exits (H2: `SwimHealthy` at a STRICTLY higher incarnation recovers a drainer back to MEMBER — mere liveness at the known incarnation does not cancel a drain; a manager-armed DEPARTING timeout terminalizes a silent drainer), death-flags clear on EVERY fresh entry into MEMBER (H3: `swimFaultySeen`/`livenessGoneSeen` no longer sticky across `UpHysteresisMet`/DEPARTING recoveries), and the death-ward boundary extends to the FSM layer (`Suspect + PeerConnected → ignore`: transport may report death, never life; recovery flows only through `SwimHealthy` or `UpHysteresisMet`). `PresenceSampler` is demoted to a pure sensor (feeds hysteresis edges INTO the FSM; the FSM→sampler eviction call and the pre-FSM boot fallbacks are gone; the generation snapshot source derives from `FSM.coreMembers()`), `TopologyObserver.tombstonedNodes` retired (inert since Wave 4), and operator drain is routed through the FSM (M10, `DrainRequested` ingress + the previously-dead `JoinGraceExpiredNeverHealthy` reaper wired live). **The integration gate caught a real formation regression in the first cut and the transition journal root-caused it in minutes**: the reaper killed never-SWIM-healthy joiners on FSM-state alone, but under the boot deploy-storm (consensus apply backlog ~50) joiners legitimately exceed the 12s grace to their first probe-ack — reap→replace→reap churn (epoch 1:83), formation never reached 5 READY, while the committed Wave-6 JAR formed in 0–16s (disambiguation run). Fixed per the ratified #126 wording ("readiness signal, NOT FSM state alone") with **co-confirmed ghost death**: the reaper fires only for never-healthy members with NO live transport connection (live connection → defer + re-arm), and the same veto applies at the SWIM layer's never-healthy grace-expiry FAULTY emission (new `Predicate<NodeId> transportConnected` seam, default-false for standalone use). Transport vetoing a death of a never-probed peer is co-confirmation, not a death-ward violation — nothing is ever promoted to ALIVE by transport. Also: 21 stale `LeaderReconcilerTest` tests rewritten to the #131 Model C contract (SUSPECT held counted through the eviction backstop; new `ModelCKillSequence` pins the mid-window hold). Docker-validated: 02-chaos **6p/0f**, 12-network **4p/0f**, 13-edge-cases **4p/0f**; module suites swim 133, aether-deployment 554, node 533 — all green. Spec: `cluster-topology-overhaul-spec.md` Wave 7.
- **QUIC transport: PeerState as the single emission source + receipt-evidence liveness TTL (overhaul Wave 5)** — every `PeerState` phase mutation now produces exactly one `PeerTransitionRecord` through one chokepoint feeding BOTH the Wave-1 journal AND the typed transport emissions (records queued under the per-peer monitor, flushed outside it — no cross-peer lock chains; REMOVED→REMOVED duplicates structurally impossible; write-path/close-listener/sweep evictions now emit the `PeerDisconnected` they used to swallow). Reconnect provenance (`announcedUpstream`) suppresses the duplicate-`PeerJoined` of the EVICTED→re-dial→attach loop (the in-code #245 echo) and conversely emits the ADD a dial-failed-then-attached peer never got. The liveness TTL is now RECEIPT-EVIDENCE only: `attach()` no longer refreshes the inbound clock (our own dial success is not proof the peer sends to us); a fresh attach gets exactly one TTL grace window (`phaseAge`-paired zombie predicate). Key discovery: **SWIM rides its own UDP socket, not QUIC lanes**, so an idle healthy follower↔follower link carries zero inbound — root of the journaled ~10s CONNECTED→EVICTED→re-dial background cycle (audit M7); fixed with a minimal transport keepalive (`NetworkMessage.KeepAlive`, CONTROL lane at ping cadence, swallowed at the inbound funnel, no-op-routed in RabiaNode/PassiveNode per the `Hello` idiom). Broadcast filtering is now mandatory at construction (no unfiltered path; FSM `broadcastEligibleMembers()` in production, topology-default fallback) and the dial path uses a per-attempt side timer that ADOPTS late successes instead of discarding them (H10). Validated: settled-suite journals show the idle evict cycle GONE (0–6 EVICTED per node per full 02 run, all death-driven kill victims); 02-chaos 6p/0f with **0.00% error rate during kill-under-load** (first-ever zero; prior best was single-digit), 12-network 4p/0f (detection 7s); consensus unit suite 641/641. Spec: `cluster-topology-overhaul-spec.md` Wave 5 (legacy-gate strip deferred to Wave 9).
- **SWIM Lifeguard failure-detector robustness (overhaul Wave 6, D4 ratified into RC1): Local Health Multiplier + dogpile dynamic suspicion timeout** — LHM: a per-node self-awareness score 0–8 (+1 on own probe-cycle timeout, failed probe send, or a *genuine* remote suspicion of self; −1 on each verified probe-ack) stretches the suspicion windows this node arms by ×(score+1), capped ×8 — an overloaded accuser becomes conservative about declaring peers FAULTY instead of false-killing them (the #94-under-load / checkpoint-run node-4 class). Dogpile: a per-suspect deduped confirmer set (gossip senders; no wire change) shrinks the suspicion window logarithmically from max (= base × LHM multiplier) toward base as *independent* confirmations accumulate — a lone degraded accuser keeps the full window for the suspect to refute, a genuinely dead peer is confirmed fast. Tunables `lhmMaxScore` / `dogpileExpectedConfirmers` in `SwimConfig`; every decision journaled (suspicion start with window/score/K, each confirmation with window shift, LHM changes with cause, FAULTY-residency interval at sweep). Probe cadence deliberately NOT stretched (fixed-rate tick keeps genuine-kill detection latency predictable); buddy system explicitly excluded (fast self-refutation already covers it; named RC2 re-evaluation). Live-validated on the gate run: a loaded-but-alive slice owner was suspected and refuted within its ×2 window instead of killed, while the genuine kill 27s later was FAULTY-confirmed cluster-wide in ~5–10s. Spec: `cluster-topology-overhaul-spec.md` Wave 6.
- **FSM-emitted membership deltas close #245 (overhaul Wave 4)** — `MembershipFsm` now emits a typed JOINED/REMOVED delta edge at its central dispatch chokepoint (OBSERVED→MEMBER entry into the counted set, with `everJoined` latch so an OBSERVED→DEAD member that never joined emits nothing and a fenced rejoin re-emits; boot-seeded cores route through the same chokepoint). A new `MembershipDeltaProjector` (async single-drainer, quorum-gated dequeue with self-terminating flush retry — robust on followers where all boot edges precede the local quorum flip) is the **sole** `MembershipDecision` emitter, replacing `TopologyObserver`'s `previousCoreMembers` baseline-diff (deleted); `evaluateQuorumState` is narrowed to quorum-presence routing only, and the 2026-06-09 `ReevaluateMembership` death-edge re-poke is deleted (ratified D7). Result: a CTM replacement's death now emits `NodeRemoved`/`NODE_FAILED` within the SLO — `Kill_node_during_active_load` passed with a replacement victim (62s) and 02-chaos went 6p/0f for the first time. Also: `assignNodeRole` no longer counts the joining node in its own core-count denominator (with edge-driven emission the self-inclusive count deterministically demoted every count-restoring replacement to WORKER/observer → stuck SYNCING → voter-set decay → cluster-wide consensus outage; live-diagnosed RC-1). `LeaderReconciler` reacts to cluster-config changes (`trigger=CONFIG_CHANGE`, #257) and self-arms a bounded deficit-convergence follow-up until the core deficit is resolved — the restore-after-churn 600s provisioning wedge is closed from both ends. Related issues filed from the same investigation: #256 (gossip-key day-rollover), #258 (Rabia stall-detector proposal re-broadcast gap), #259 (descriptor-vs-assigned role display divergence).
- **Worker accounting hygiene (overhaul Wave 2, W1–W6 + invariant A8)** — quorum, auto-heal deficit, role assignment, and `QuorumLossDetector` now count CORE-role members only via the FSM's new role-scoped `coreCountedMembers()` / `strictCoreMemberCount()` projections (full `countedMembers()` call-site audit; a single worker can no longer inflate quorum, mask a core deficit, flip role assignment, or receive `CORE_ONLY` placement). Provisioned nodes carry their intended role explicitly end-to-end (`provisionReplacement(role)` → `ProvisionContext` → `AETHER_ROLE` env + `aether.role` docker label — the bootstrap-host inheritance hole is closed); WORKER/SPOT-targeted runtime scale is rejected with an operator error instead of silently resizing the core. Descriptor role/source gained a blank-downgrade guard (audit M9: a label-less gossip-rebuilt NodeInfo no longer wipes a known role), and surplus-drain victim selection gained an age-based safety grace (`nttDepartureTimeout×2`) so a just-joined member whose role hasn't propagated yet is never drained as phantom surplus (deferred + follow-up re-eval instead). New `MembershipFsm.memberAgeMs`/`memberIncarnations`. Late joiners replay `GossipKeyRotationKey` from synced KV on Rabia state restore (new `RabiaEngine.onStateRestored` seam) — previously a node joining after a key rotation PUT could never decrypt-join SWIM (#256). New 13-edge-cases worker-join accounting scenario validates W1–W6 live (worker joins+classifies in 2s, never fills a core deficit, never receives CORE_ONLY placement). Spec: `cluster-topology-overhaul-spec.md` Wave 2.
- **Dialer-side QUIC Hello identity enforcement (overhaul Wave 3, audit H6/F4)** — `QuicClusterClient.completePeerConnection` now verifies `hello.sender() == dialedPeerId`; a mismatched identity (e.g. a DNS re-resolution landing on whatever answers) is rejected before attach: connection closed, dial failed through the normal connect-failed/backoff path, WARN + `dialer-hello-REJECTED expected=… actual=… addr=…` transition-journal record. All post-handshake registration is keyed by the verified identity. Spec: `cluster-topology-overhaul-spec.md` Wave 3.
- **Cluster transition journal + topology truth-capture (overhaul Wave 1, diagnostic-only)** — per-node bounded ring-buffer journal recording every `MembershipFsm` transition and every QUIC `PeerState` phase mutation `(seq, wallClock, layer=FSM|PEER, id, from, to, cause, incarnation, role)`, dumpable via new `GET /api/cluster/journal?layer=fsm|peer&limit=N` + `aether cluster journal` CLI; WARN-class transitions (→DEAD, CONNECTED→EVICTED) auto-flush the tail to the log. Companion diagnostics, all log/read-only: dialer-side expected-vs-actual Hello identity log (`QuicClusterClient.completePeerConnection`, audit §6.1 precursor to the Wave-3 identity check); periodic 3-view membership-baseline trace (`previousCoreMembers` vs `coreMembers()` vs presence); SWIM FAULTY-residency interval trace (audit H8/§6.6); boot-time future-history WARN when persisted Rabia state exceeds the joined cluster's (mixed-wipe detect-only, audit §6.4); `/api/cluster/topology` now exposes per-member FSM state + incarnation + descriptor (role/source). Spec: `aether/docs/specs/cluster-topology-overhaul-spec.md` Wave 1.
- **Membership-convergence FSM — Phase 1 (shadow)** — a per-member state machine (OBSERVED/MEMBER/SUSPECT/DEPARTING/DEAD) runs in parallel with the live NTT+LeaderReconciler, observing only and logging `MEMBERSHIP-FSM-DIVERGENCE` where its verdict differs from the live decision. Gated by `AETHER_MEMBERSHIP_FSM_SHADOW` (default off); acts on nothing.
- **In-process black-hole fault injection for membership chaos testing** — reproduces the transport-gated decommission gap on the fast loop.
- **Hierarchical per-slice configuration composition (RC1, 2026-05-22)** — new `LayeredConfigProvider` (`integrations/config/config-service/.../LayeredConfigProvider.java`) walks an ordered list of providers L→R and returns the first hit. `AetherNode` startup now builds `node-composite = KV ⊕ node.toml` once at process boot; `SliceStore.loadSlice` builds `slice-composite = slice.toml ⊕ node-composite` per slice by parsing the slice JAR's `META-INF/resources.toml` into a new `IntrinsicConfigProvider` and layering it under the shared node-composite. Effective precedence for slice queries: `KV > node.toml > slice.toml` — operator overrides always win, environment-level overrides next, slice intrinsic defaults at bottom. Each slice's composite is stored on `LoadedSliceEntry.sliceConfig` (`Option<ConfigurationProvider>`) and GC'd at slice unload — no explicit cleanup state. Cross-slice intrinsic-config isolation is structural: two slices shipping the same TOML key see their own value, eliminating last-writer-wins races. The `ConfigService.instance()` singleton is retained as a fallback for non-slice contexts (Batch 1-2 of 5 sequential commits).
- **Eager resource provisioning via `ProvisioningContext.extension(ConfigurationProvider.class)`** — `SpiResourceProvider.loadConfig` now consults the slice-composite attached to the provisioning context BEFORE falling back to the constructor-supplied loader. `@PgSql` / `@Sql` / `@Http` / `@Notify` factories construct resource wrappers at slice instance creation using the slice-composite — no eager external validation (no DNS, no TCP handshake, no JDBC connect). Validation stays at first invocation, matching the existing factory implementations (`JdkHttpClient`, `NettyConnectibleBuilder`, `PgAsyncSqlConnector`, `JdbcConnector`, `SmtpClient`, `HttpEmailSender`). Threading via existing `ProvisioningContext.extension(Class<T>)` slot (Batch 3).
- **`NodeDeploymentState.{resolveTopicName, resolveScheduleConfig, resolveStreamName, buildConfigFacade}` consult slice-composite (Batch 2)** — eager-at-activation resolvers in the activation chain now query the slice's composite via `SliceStore.sliceComposite(Artifact)` before falling back to `ConfigService.instance()`. Closes the @MessageReceiver dispatch race where `BlueprintRegistered` Put could trigger activation before `BlueprintResources` Put applied the slice's TOML to the global provider. `Artifact` threaded through `readSubscriptionsFromManifest`, `readStreamSubscriptionsFromManifest`, and their `doPublish*`/`doUnpublish*` callers.
- **`SliceStore.sliceComposite(Artifact)` accessor** on the `SliceStore` interface — `Option<ConfigurationProvider>` lookup that filters by `Promise::isResolved` on the loaded-slice entry. Single source of truth for "what does this slice see as config" from runtime callers.
- **`SliceLoadingContext` slice-composite seam (Batch 3)** — new `setSliceComposite` / `setCompositeBuilder` / `materializeComposite` / `sliceComposite()` methods + `CompositeAwareResourceProvider` inner facade that injects the composite into every `ProvisioningContext` flowing to the SPI provider. Builder pattern lets `SliceStore` pass a closure resolved against the slice classloader once it exists (in `DependencyResolver.loadSliceClassAndResolveDepsWithContext`), avoiding both a chicken-and-egg with classloader construction and any retained references to the old config service singleton.
- **Membership v2 architecture spec (`aether/docs/specs/membership-architecture-v2-spec.md`)** — foundation spec for the planned RC1 redesign of the topology-management layer. Eliminates the parallel state (membership FSM, slot-occupancy classifier, reachability gates, leader-pinned timers, drain-FSM integration) that lives on top of the proven-reliable simple scheme (SWIM → QUIC → Rabia → LeaderManager), in favor of derive-from-reality + an NTT (Node Topology Tracker) per-node component. 17 sections + scenarios + E1–E6 migration plan. Subsequent amendments captured in this session: §1 retargeted to RC1; §4 `localQuorumCount` vs Rabia voter-set divergence note; §7.4 NEW hybrid reconciliation triggers (NTT events + KV-subscribed configured/drain changes + leader-activation map-drain + periodic tick at `provisioning_timeout × 1.5`); §8.2 step 3 amended (LEAVE preserved as SWIM acceleration); §10 LEAVE deletion entry amended (delete only the FSM state-transition machinery, keep SWIM-internal LEAVE); I12 NEW (NTT per-peer claimable `Map<NodeId, TopologyUnhealthyEvent>`, claim-then-process); §14 entries added for `membership.nttObservation` feature flag (ramp gate during E1–E4 migration) and reconciliation-tick period rationale. Implementation pending — E1 ships observation-only behind the feature flag (default `off`).
- **Stream namespaces & addressing (production-readiness rework, #239)** — streams are now addressed by a fully-qualified `(namespace, stream, version)` triple: `StreamAddress` plus a `MAJOR.MINOR.PATCH` `StreamVersion` (immutable once registered; schema fixes bump PATCH). The `namespace` segment partitions the global stream key space, with `system` reserved for framework-internal streams. Stream metadata is held in two complementary consensus-replicated registries — `StreamConfigKey` (`stream-config/{stream}`: flat config/retention/partitions, hydrated locally via `onStreamConfigPut`) and `StreamRegistryKey` (`stream-registry/{namespace}/{stream}/{version}`: namespaced refcount + registration metadata, read from replicated KV) — so non-governor nodes serve stream metadata and the `/api/stream-namespaces/*` views from local replicated state (#215). New namespaced HTTP surface (`/api/streams/{list,versions,latest,metadata,events,tail,publish,publish-batch,delete}/...`, `/api/streams/groups/...`, `/api/stream-namespaces/{list,get}`) and an `aether stream` CLI command group (`list`/`show`/`tail`/`delete`/`group create`/`group delete`). `tail` is polling-based against `/events`; an SSE/WebSocket subscription is deferred to issue #212.
- **Pub/sub topic namespaces & addressing** — pub/sub topics now use the same fully-qualified `(namespace, topic, version)` addressing as streams: `TopicAddress` plus a `MAJOR.MINOR.PATCH` `TopicVersion`, both implemented as the topic-flavored view of a shared `ResourceAddress`/`ResourceVersion` abstraction (the single source of truth for grammar, validation and the reserved `system` namespace). `StreamAddress`/`TopicAddress` are thin nominal wrappers over it, preventing a stream address from being passed where a topic address is expected. A topic's namespace is derived from the publishing slice's blueprint Maven coordinates (`groupId.artifactId`); bare/legacy declarations (`topicName = "order-events"`) keep deserializing unchanged and resolve to `default:<topic>:1.0.0` pre-deploy (the deploy path swaps in the derived namespace), while an explicit `namespace:topic:version` declaration in slice config is parsed and round-trips verbatim. `TopicSubscriptionKey` is namespaced; `PubSubValidator` rejects the reserved `system:*` namespace for app topics (mirroring `StreamResourceValidator`). The topology graph (`GET /api/slices/topology`, dashboard) now keys topic node identity and cross-slice pub→sub matching on the resolved canonical address, so a bare publisher and an explicitly-namespaced subscriber of the same logical topic connect. Pub/sub remains in-process and declaration-driven: no `aether topic` CLI group and no topic management HTTP route are introduced.
- **Sealed `ClusterEvent` model with `@Codec`** — cluster events are now a sealed record hierarchy (topology, leader, quorum, deployment, slice-failure, network) carrying a generated codec, replacing the ad-hoc event payloads, so they serialize across the cluster transport.
- **Active replica-set controller (HRW) for all streams** — a membership-driven `ReplicaSetController` recomputes Highest-Random-Weight (HRW) replica placement on every membership change and writes the resulting replica set into the `ReplicaRegistry`, maintaining the target replication factor for every stream. App-stream replication and cross-node write-forwarding are now activated (a producer on any node forwards to the owning replica), and a catch-up backfill brings a newly-placed replica current from existing replicas' watermarks.
- **Production retention for `system:cluster-events`** — count + bytes + age caps, configurable via `CLUSTER_EVENTS_MAX_COUNT`, `CLUSTER_EVENTS_MAX_BYTES`, `CLUSTER_EVENTS_MAX_AGE_MS`, and `CLUSTER_EVENTS_EVENT_SIZE_BYTES`.
- **`KVStore.isReplaying()` replay signal** — cluster-event emit is suppressed during snapshot/resync replay so historical deployment events are not re-emitted on catch-up.
- **Stream budget-exhaustion observability (`ClusterEvent.StreamMemoryExceeded`)** — when a stream cannot reserve off-heap (at create-floor or during growth), a `StreamMemoryExceeded` cluster event (WARNING) is emitted node-locally (owner-gate-bypassed, per-node fact) into `system:cluster-events` and surfaces in `/api/events`, with per-`(stream,phase)` 60s rate-limiting. Budget exhaustion now propagates loudly: management publish → HTTP 500 with the exact `detail`; a resource/slice whose declared stream can't be provisioned classifies the failure as transient (`ResourceCapacityExhausted`) → retries → `DeploymentFailed` after max retries (no more "deployed but dead").

### Changed
- **Replicated stream reads forward to the HRW owner; `system:*` streams replicate to all cores (RF=N)** — fixes `GET /api/events` (and the generic `/api/streams/.../events` read API) returning `200 []` when served by a node that is not yet a caught-up replica. Root cause: forward-read source selection used only the node-local `ReplicaRegistry` CAUGHT_UP set, but peer watermark state is never propagated cross-node (the production registry uses a NOOP watermark store) and the forward-capable consumer had no owner resolver wired — so a lagging node fell through to reading its own empty local partition. Reads now fall back to the deterministic HRW owner (every node computes the same placement; the owner self-promotes first), and the two duplicated routing implementations (`PartitionedStreamAccess` typed reads, `StreamReadRouter` raw reads) are converged into one generic `ForwardingReadRouter`. System streams now place a replica on every core node (`systemReplicationFactor = clusterSize`, superseding `max(3, N-2)`), so no core is a permanent non-replica and the event-log's durability matches the cluster's own fault tolerance by construction.
- **One quorum denominator (overhaul Wave 9, item 1, M1)** — `ClusterConfigKey.coreCount` is now the single source of the cluster's core-count denominator. The consensus-side `TopologyObserver.effectiveClusterSize` atomic is demoted to a derived cell: the aether-side `ClusterConfigKey` KV subscription (`AetherNode.onClusterConfigPut`) pushes the committed `coreCount` into the observer via its pre-existing `handleSetClusterSize` trigger on every config commit (the observer cannot read aether KV directly — module boundary — so this is the single KV→atomic bridge). §3.1 preserved: `handleSetClusterSize` stays one of `evaluateQuorumState`'s pre-existing triggers; only the value's origin changed. Boot ordering: the atomic is seeded from `TopologyConfig.clusterSize()` and holds that (config-equal) value until the first `ClusterConfigKey` commit supersedes it.
- **One split timeout `T` (overhaul Wave 9, item 2 — CONFIG-BREAKING, 5c)** — the separate `nttDepartureTimeout` (15s, majority departure / re-provision) and `quorumLossDrainThreshold` (8s, minority quorum-loss self-drain) knobs are collapsed into a single `MembershipConfig.splitTimeout` (`T`, default 15s) governing BOTH paths. The `[membership]` TOML keys `ntt_departure_timeout` + `quorum_loss_drain_threshold` are replaced by `split_timeout`; the legacy keys are no longer accepted (clean break, ratified) — only `split_timeout` is read, defaulting per spec §14 when the key or section is absent. The no-double-active ordering (minority halts before majority re-provisions) is preserved by the natural detection-lag between observation points — minority measures `T` from local-quorum-loss (t=0), majority from the departure-verdict (t=SWIM-convergence+co-confirmation>0) — not by a second knob. New `MembershipConfigSplitTimeoutOrderingTest` pins the ordering invariant.
- **Transport `NodeInfo`/`Hello` no longer carry a transport role (overhaul Wave 9, item 3, A8/Q3)** — `NodeInfo` is now `(id, address, labels, resolvedAddress)` and `NetworkMessage.Hello` is `(sender, address, labels)`; the CORE/WORKER/SPOT classification lives solely in the `role` label and the aether `MemberDescriptor`. Codecs regenerate automatically (pre-release, no wire compat required).
- **SWIM: fair least-recently-probed scheduling — a flapping peer no longer starves a dead peer's failure detection (#94 NODE_FAILED-within-60s)** — `selectNextProbeTarget` selected `members.values().get(probeIndex++ mod size)`, but `members` is a `ConcurrentHashMap` (hash-bucket order) and the candidate list is rebuilt every tick; a peer flapping (removed+re-added via gossip every ~4s after a partition-heal) reshuffled `members.values()` order and size under the single free-running `probeIndex`, silently breaking the "probe each member once per round" invariant — a genuinely-dead peer could go un-probed for ~129s and, because SUSPECT entry is probe-gated, never enter SUSPECT, so NODE_FAILED landed ~140s out (past the 60s budget). Replaced the position-based index with **identity-keyed least-recently-probed selection**: a per-member `lastProbedAt` ordinal (a strictly-monotonic `probeOrdinal` — wall-clock ms was too coarse and re-tied selections within a tick), `selectNextProbeTarget` picks the smallest (never-probed = `Long.MIN_VALUE` wins), tie-broken by `NodeId`; stamped on probe, cleared in `clearDeathMemory`. A churning peer retains its ordinal and cannot jump the queue, so every probable member is probed within ≤N periods (≤5s at N=5) regardless of churn → a dead peer reaches FAULTY well inside budget. Touches only *which* member is probed next: SUSPECT→FAULTY (already a fair per-member timer), cold-boot suppression, join-grace, and tombstone anti-oscillation are all unchanged; detection still requires a full direct+indirect probe failure. Unit: new `deadPeer_detectedWithinBudget_despiteAnotherPeerFlapping` + `selectNextProbeTarget_everyProbableMemberProbedWithinNPeriods_underMembershipChurn`; `SwimProtocolTest` 32 green.
- **QUIC transport: structural reconcile/dial hardening — every desired core converges to a live link, no terminal-stuck states (#131 12-network `connectedPeerCount=3`)** (1) **Dial-time DNS re-resolution** — `connectPeer` built `new InetSocketAddress(host, port)`, whose two-arg constructor resolves DNS *eagerly at construction*; after a partition-heal a seed's cached SWIM address could be stale (`<unresolved>`), so every reconciler tick rebuilt the same unresolved address and the dial was rejected before a packet left — the peer sat EVICTED, dialed-but-never-connecting forever. Now the host is re-resolved fresh at dial time, **non-blocking** on the Netty event loop (`QuicClusterClient.resolve` via `DefaultNameResolver`); `beginConnecting` moved into the resolution-success continuation (so CONNECTING always means a real in-flight dial, backstopped by the staleness sweep), and a resolution failure leaves the peer dial-eligible for the next backoff-paced tick instead of poisoning it. (2) **Last-inbound-frame liveness TTL** — the liveness sweep only caught `!isActive()` links; a Flavor-2 zombie whose `isActive()` lies true forever and whose `closeFuture` never fires was invisible. `PeerState` now tracks `lastInboundAtNanos` (stamped in `onMessageReceived`, reusing the single existing inbound funnel across all lanes — SWIM/pong/consensus, no new lane; blackholed inbound deliberately not counted), and `sweepPeer` evicts a CONNECTED link silent beyond `pingInterval × 8` → re-dial. (3) **Membership guard** — `MembershipFsm.updateDescriptor` no longer lets an empty-address descriptor downgrade a known address to none (verified SUSPECT cores already stay in `desiredConnections()`). All three mechanisms converge to `EVICTED → re-dial`; gates that prevent the forever-redial wedge are untouched (re-resolution/TTL operate only inside already-approved dials; DEAD peers are excluded upstream). Unit: consensus QUIC 58 green (+ new deferred-resolution, zombie-TTL, blackhole-not-counted cases), `MembershipFsmTest` 72 green.
- **QUIC transport: a stuck dial no longer wedges a peer in CONNECTING forever (#131 12-network `connectedPeerCount=3`)** — the missing-peer reconciler's in-flight-dedup guard (`if (phase == CONNECTING) return;`) had no staleness escape, so a dial that neither completed (`onPeerConnected`) nor failed (`onConnectFailed`) — a hung `client.connect(...)` — pinned the peer in `CONNECTING` permanently; every subsequent tick skipped it, so it was never re-dialed and never counted as connected (the leader's `connectedPeerCount` dropped to 3 and the cluster never reached full READY). Fixed with two complementary bounds, both sized at `helloTimeout × 3` (a shared `CONNECTING_STALENESS_HELLO_TIMEOUT_FACTOR`, deduplicated with the existing `disconnect` protection window): (1) a **per-dial timeout** on `connectPeer` (`client.connect(...).timeout(...)`) so a hung dial deterministically routes to `onConnectFailed`; (2) a **reconciler staleness escape** (`evictStaleConnecting`) that force-evicts CONNECTING→EVICTED and re-dials the same tick once the phase age exceeds the bound — the dedup guard still protects genuinely in-flight dials within the window. Also fixed a latent no-op: `onConnectFailed` called `PeerState.evict()` (which only handles CONNECTED→EVICTED), re-wedging the peer; it now uses the new `PeerState.evictStaleConnecting` (CONNECTING→EVICTED). Diagnosed from live cluster logs: the dialer-side wedge is the load-bearing half of the `connectedPeerCount=3` failure (the acceptor-side Flavor-2 zombie is reaped once the dialer re-dials and adopt-newer supersedes it). Unit: `QuicClusterNetworkConnectingStalenessTest` (2) + `PeerStateTest` `evictStaleConnecting` (5); full consensus QUIC suite 49 green.
- **JBCT formatter re-enabled across the codebase (PR #243, #242)** — the `jbct-maven-plugin` format goal was disabled since 2026-05-12 because it silently deleted `///`/`//` comments in structural gaps the trivia walk never visited. PR #243's **orphan-trivia sweep** (`FlowPrinter`) emits any comment not claimed by a leading/trailing node at its source position, guaranteeing `output_comments ⊇ input_comments` by construction. Verified: idempotent (second whole-repo pass reformats 0 files), **0 comment deletions across 2667 files** (new corpus gate `FlowCodebaseCheckTest`), all 80+ modules compile, unit tests green. `build.sh` Step 2 now runs the `format` goal (was `lint`); the whole codebase was reformatted in one pass (843 files). Lint is intentionally decoupled for now — the combined `process` (format+lint) goal surfaces 33 pre-existing JBCT lint errors (aether-deployment/node/aether-stream/aether-invoke; RET-01/RET-03/EX-01) that predate this change; once that debt is cleared, Step 2 returns to `process` to re-enable the lint gate. See `docs/contributors/jbct-formatter-disabled.md`.
- **QUIC transport: dead follower↔follower links are now evicted event-driven + by a liveness sweep, not only by the write path (#131 dialer-side completion)** — the adopt-newer fix closed the *acceptor* zombie; this closes the *dialer* twin. A `PeerState=CONNECTED` link whose channel is dead is (1) counted as connected, (2) skipped by the missing-peer reconciler (it only dials NON-connected peers), and (3) never evicted, because the sole eviction trigger was an outbound write failing — and **nothing writes to a follower↔follower link** (ping is leader-only, pongs go only to the leader, Rabia is round-driven). So a dead follower↔follower link persisted forever, dropping `connectedPeerCount` with no self-heal. Two complementary fixes in `QuicClusterNetwork`: (A) a **liveness sweep** (`sweepStaleConnectedLinks`) at the head of `reconcileMissingPeersUnsafe` — before `connectedPeers()` is computed, so a just-evicted zombie re-dials the same tick — demotes any CONNECTED peer whose connection reports `!isActive()`; (C) an **event-driven close listener** registered exactly once per successful attach (`onPeerConnected`) on the just-bound connection, so a genuine QUIC `closeFuture` evicts immediately without waiting for a write that never comes. The listener is **identity-guarded** (`current == closed` reference equality) so an adopt-newer supersede — which binds a different connection object — does not evict the live replacement. Residual not covered: the `isActive()`-lies-true orphan (only a round-trip ping-timeout or a re-dial-from-the-other-side resolves it; deferred). Validated: 12-network **4p/0f**, **0** false-evictions of healthy links on cluster-B (no-regression confirmed); unit `QuicClusterNetworkLivenessSweepTest` 4/4 (sweep evicts dead / no-ops on live, listener evicts bound / no-ops on superseded).
- **Membership: terminal eviction of a co-confirmed-dead member is now DEFERRED behind a backstop window (#131, "Model C" — membership half)** — previously a member observed with BOTH death planes (`swimFaulty ∧ livenessGone`) was marched straight to terminal `DEAD` (`MembershipFsm.maybeConfirmDeparture` → `DownHysteresisMet`+`Stopped`) in ~3s. Because a kill and a brief network partition are indistinguishable for that window, a node partitioned for less than the self-drain threshold was terminally removed (and rejoin-fenced via `terminalIncarnation`) before it could self-drain — so on reconnect it sat as a fenced zombie, never rejoining (the 12-network partition-heal READY-timeout). Now co-confirmation **arms a per-member backstop timer** (`quorumLossDrainThreshold`, default 8s — the SAME value the minority's quorum-loss self-drain uses, single source of truth) and the member **stays SUSPECT** (still counts toward effective, still recoverable). Terminal `DEAD` is reached only on the existing confirmed-departure paths (graceful `SwimDeparted`, join-grace expiry) OR when the backstop fires; a `SwimHealthy`/`PeerConnected` recovery within the window cancels the backstop (via `clearConfirmedDeath`) and the node rejoins through the existing `SUSPECT→MEMBER` edge. The backstop callback re-checks `coConfirmedDead()` under the per-member monitor so a recovery racing the timer can't terminal-evict a recovering node. Scoped to established-member co-confirmation only — JOINING-window kills (join-grace expiry) and single-plane signals are unchanged. **Necessary but not sufficient for #131**: it un-fences the rejoin (Docker-validated: nodes rejoin membership and reach local READY), but the rejoined node still could not commit consensus — the acceptor side held its pre-partition QUIC link as immortally-active and DUPLICATE-rejected the dialer's reconnect. The QUIC adopt-newer fix (next entry) is the companion landing that actually closes #131 (the address-staleness theory was refuted: the QUIC dial uses the configured hostname, DNS-resolved fresh in Docker, so the dial address was never the problem). Co-confirmed eviction is now asynchronous (≤8s), so eviction-dependent observability (`NODE_FAILED`) and the 02-chaos kill timings shift later within budget; no 02-chaos assertion regressed (5p/1f baseline).
- **QUIC transport: a fresh handshake now supersedes an aged "active" incumbent connection (#131, "adopt-newer" — the transport half that closes it)** — root cause of the partition-heal READY-timeout, found by live probe instrumentation: after a brief partition heals, the leader (the lower-id, designated dialer) re-dials the rejoined node and the handshake completes, but the **acceptor** still holds its pre-partition QUIC connection to the leader as `isActive() == true` forever — because the cluster sets `maxIdleTimeout = 0` (disabled) with no QUIC keepalive, so a connection whose peer closed it *during* the partition (the `CONNECTION_CLOSE` never traversed the dead path) becomes immortal. `PeerState.attach` then DUPLICATE-rejected every reconnect and closed the fresh link, so the leader looped connect→evict every ~5s and the rejoined node could never commit its `NodeReportedState=READY` through consensus. Fix: in the `CONNECTED` branch of `PeerState.attach`, when the incumbent reports `isActive()` **but is older than `SUPERSEDE_MIN_AGE_NANOS` (3s)**, adopt the fresh connection (RECONNECTED) and hand the displaced old connection back to the caller to close (via a new `AttachOutcome(result, superseded)` record; `QuicClusterNetwork.onPeerConnected` routes the superseded connection through the standard close path). Rationale: a completed Hello handshake is a *current* liveness proof, whereas `isActive()` can lie indefinitely on a partition-orphaned link; `ConnectionDirection.shouldInitiate` guarantees exactly one dialer per pair, so a fresh inbound handshake means the designated dialer detected death and re-dialed — defer to it. The 3s age guard preserves the existing protection against the sub-millisecond dual-dial race during formation (a young active incumbent still wins as DUPLICATE). Also enables QUIC `activeMigration(true)` on both client and server codecs, so a path change that does not tear down the socket survives without any reconnect. Validated: **12-network 4p/0f** (was 2p/2f) — the partition-heal `4+ cores reporting READY` gate now converges in **0s** (was a 600s timeout); the acceptor logs `RECONNECTED` on the leader's first post-heal redial instead of looping; `connectedPeerCount` and kill-detect tests green. Unit: `PeerStateTest` 28/28 incl. the adopt-newer + young-incumbent-duplicate cases.
- **Quorum signal flow made strictly unidirectional: `TopologyObserver → RabiaEngine → everyone` (channel-separation half)** — `ClusterStateNotification` previously had two producers on one shared, type-keyed bus — `TopologyObserver` (simple-majority "quorum established/lost") and `ConsensusBridge` (Rabia's "consensus active") — so every consumer (`NodeDeploymentManager`, `ControlLoop`, `ScheduledTaskManager`, `ReplicaSetController`, `ClusterSyncScheduler`, `DeploymentMetricsScheduler`, `AppHttpServer`, `ClusterEventAggregator`, `LeaderManager`) was gated on a conflation of the two. `TopologyObserver`'s established/lost edge is now delivered to `RabiaEngine` ONLY, via a dedicated single-subscriber channel (a `quorumPresenceRouter` `DelegateRouter` whose one-entry table routes `ClusterStateNotification → consensus::clusterState`, wired off the shared bus); `RabiaEngine` is removed from the shared-bus `ClusterStateNotification` route. `ConsensusBridge` is now the SOLE shared-bus emitter — Rabia's active status reaches all public consumers + `LeaderManager`. Net effect: every public consumer is upgraded from the weak "simple majority present" predicate to the strong "active consensus" (`RabiaEngine.isActive()` = majority **and** caught-up **and** progressing). Intended timing shift: `NodeDeploymentManager.markSubsystemsReady`, the `QuorumEstablished/Lost` audit events, and `AppHttpServer`'s per-request `quorumEstablished` flag now reflect active consensus (a sync-round later on cold-start) instead of bare majority. `TopologyObserver.haveQuorum()`'s input and the `MembershipView.strict` quorate gate are deliberately untouched — re-sourcing `haveQuorum` to raw transport connectivity (the partition-heal latency fix, #131) is the follow-on landing that completes the transition.
- **Generation-snapshot membership set sourced from the FSM (#110)** — the generation-snapshot path's membership *set* is now derived from `MembershipFsm.countedMembers()` (MEMBER+SUSPECT) instead of `PresenceSampler`'s debounced presence set, completing the membership/health unification of the quiesce path (its health source already moved to FSM `healthHints()` in Wave E). Both the `PresenceGenerationSnapshotSource` BOOTING→NORMAL quorum latch and the `GenerationSnapshotPublisher` member projection now read the FSM. Behavior delta (spec-aligned: a SUSPECT node is a Rabia voter / counts toward effective): the snapshot is SUSPECT-inclusive, and the cold-start latch is edge-driven (fires on the first quorum-many counted members) rather than waiting on NTT's ~2-sample debounce — marginally faster cold-start, one-way latch (no flapping). `propagateMemberCount` already read the FSM with an NTT count fallback (unchanged). `PresenceSampler.currentMembers()` has no remaining production callers (retained only as a test-observability window onto the live debounce `stableMembers` set); `currentMemberCount()` stays as the `propagateMemberCount` boot fallback; `peakMembershipCount()` (LeaderReconciler cold-start latch) untouched. Docker-validated: 02-chaos **5p/1f = baseline**, no formation/quiesce regression.
- **Membership-FSM is now the transport connection authority (#109, Wave D2)** — the per-member `MembershipFsm` no longer only decides *who consensus broadcasts to* (the unification below) but *which QUIC connections the transport maintains*. The missing-peer reconciler reconciles live connections against the FSM's published desired core-member set (`desiredConnections()` → an injected `ClusterNetwork.setDesiredConnections` supplier of `NodeInfo`) instead of the static configured topology, and **drops its two own membership-decision gates** (`swimMembershipAllows`/`swimHealthAllows`) — the FSM projection (MEMBER+SUSPECT, non-worker, address-known) already encodes them. Teardown is event-driven off the FSM's co-confirmed-DEAD edge: `MembershipFsm.onConfirmedDeparture` → `departurePermanent`, so a co-confirmed-dead peer's QUIC link drops promptly (Docker: a JOINING-window replacement left membership in **1s** vs the prior ~14s SWIM-FAULTY wait) instead of lingering as a zombie. Model is **ADD-level + DEAD-event-REMOVE**: the reconciler only ever dials (never tears down on mere absence-from-desired), and teardown reacts only to the FSM's existing co-confirmed-DEAD verdict — so there is no new cluster-to-zero pathway. The change is additive and gated by the supplier: when unwired (consensus-only fixtures/tests) the legacy topology-driven reconciler path is byte-identical. Boot seeds the FSM descriptors from configured topology so `desiredConnections()` carries dial addresses immediately (formation does not stall). FSM `desiredConnections()`/`coreMembers()` now read an atomic `(state, descriptor)` snapshot (fixes a two-acquisition race). Docker-validated: 02-chaos **5p/1f = baseline** (formation 24s, re-election 2s, multi-kill auto-heal, self-drain S19 24s exit-code-2, S20 recovery), no regression; the residual 1f (NODE_FAILED-within-60s under load, #94) and the post-multikill quiesce cascade (#68) are pre-existing separate roots, unchanged.
- **Membership-FSM unification — per-node single authority + `PresenceSampler` (#68 storm root fixed)** — the per-member `MembershipFsm` is now **always-on per node** (no leader-gating; only scaling decisions stay leader-gated) and is the **single membership authority every consumer reads**. Consensus `broadcastPayload` now targets the FSM's non-DEAD members (`broadcastEligibleMembers()`) instead of the transport's own `peers.values()` cache — dissolving the consensus dead-ULID retry-storm at its root (a co-confirmed-DEAD peer is dropped from the broadcast set the instant it dies; Docker-validated: the perpetual `CONSENSUS stream backpressured` loop is gone, give-up count 0 on all nodes, zero formation regression). `QuorumLossDetector`, forward-routing (`reachableMembers`), DHT liveness, and the generation quiesce-health (`healthHints()`, behavior-preserving) all read the FSM. Nodes self-describe `source`/`role` as SWIM-gossiped `NodeInfo` labels (`AETHER_SOURCE`/`AETHER_ROLE` → `ClusterIdentityEnv`, carried on Announce → `SwimMember` → `dialInfoFor`); the FSM stores a per-member `MemberDescriptor(address, role, source)` retained through DEAD (enables same-source replacement provisioning + operational visibility, tracked in #241). `NodeTopologyTracker` is renamed **`PresenceSampler`** and demoted to a pure SWIM-sampling debounce clock (sheds its dead `keepOnlyAccessible` accessor). Deferred to their own validated efforts: the full level-triggered transport executor (FSM drives connect/disconnect — D2) and migrating the generation-snapshot membership source off `PresenceSampler`. NOTE: `healthHints()` has no TTL expiry whereas the legacy `SwimHintsRegistry.currentTtlFiltered()` did — a known behavior difference tracked under #68 (the post-auto-heal quiesce residual, a separate CTM-churn/SWIM-latency root, is unaffected by this change and remains open).
- **Membership-convergence FSM — Phase 2 cutover, reconciler now COUNTS the FSM (#68/#94)** — `LeaderReconciler` derives its effective/provisioning count from `MembershipFsm.countedMembers()` (the MEMBER+SUSPECT id set) instead of `NodeTopologyTracker`'s presence set. This eliminates the over-provisioning churn: a member in transient SUSPECT still counts in the FSM, so the reconciler no longer sees a phantom deficit when NTT's down-hysteresis drops a still-stabilizing replacement (the old "4 provisions for 2 kills" / `effective=4`-while-present storm). To keep SUSPECT bounded (invariant I4) now that the FSM is the counted authority, NTT's debounced down-hysteresis crossing is routed *into* the FSM as `DownHysteresisMet` (new `NodeTopologyTracker.onDownHysteresisCrossing` callback → `MembershipFsm.onDownHysteresisMet`) — a sustained-absence node still departs (SUSPECT→DEPARTING→DEAD) even without a SWIM-FAULTY verdict, while a brief flap (down-streak resets) stays counted. NTT keeps its own `stableMembers` set as a presence **sensor** (forward-routing, quorum-liveness — `QuorumLossDetector` unchanged); it is no longer the membership authority the reconciler counts. Satisfies spec I2 (effective is a pure function of FSM states) and I3 (single counted authority); no identity-model change.
- Membership-convergence FSM — Phase 2 (cutover): the per-member FSM is now the live authority for member death/eviction (its DEAD edge drives `ntt.evict`, leader-gated, consensus-independent via SWIM + liveness). Deleted the LeaderReconciler death flag-tangle (`swimFaulty`/`livenessGone`/`terminalIncarnation` + co-confirmation methods) and the shadow divergence reporter. Fixes the under-load `NODE_FAILED`-within-60s detection (02-chaos 5p/1f→6p/0f, kill-under-load error rate ~40%→0%).
- **Terminal-removal restart-disabled invariant documented and enforced consistently** — Aether's membership model terminally removes a dead NodeId (recovery is always a new-ULID replacement minted by auto-heal), so any runtime that auto-restarts a crashed node under the same identity corrupts membership. Fixed the two contradictions found: `aether/forge/docker-compose.yml` (`restart: unless-stopped` → `restart: "no"`) and `SystemdUnitTemplate` (the generated systemd unit emitted `Restart=on-failure` + `RestartSec=5s` → now `Restart=no`; test assertion updated). Stated the invariant + rationale consistently across `aether/tests/integration/docker-compose.yml`, `aether/docker/docker-compose.yml`, the operator guide `aether/docs/operator/deployment-recovery.md` (§2.2 now names terminal-removal + ULID explicitly; §6 reading-list cites `SystemdUnitTemplate`), `aether/docs/guides/rolling-upgrade.md` (distinguishes operator-orchestrated upgrade-restart from prohibited crash auto-restart), `aether/docs/reference/cloud-integration.md` (Auto-Heal section), `aether/docs/reference/feature-catalog.md` (new lifecycle row), and `aether/docs/specs/membership-architecture-v2-spec.md` §12.7 (invariant note superseding the pre-rework "same NodeId returning"/KSUID framing). The CLI-generated compose (`DockerComposeTemplate`) and cloud-init container path (`UserDataTemplate` → `docker run --restart no`) already emitted the correct policy.
- **Cluster name is now required at node startup** — a node refuses to start (loud error, non-zero exit) when its cluster name resolves to missing/empty from `AETHER_CLUSTER_NAME` or bootstrap-seeded KV. The cluster-name validation regex is unified across node, CLI, and config (1-63 lowercase DNS label, no trailing hyphen) and relaxed to permit single-character names.
- **Codec registry naming: `codec.registry.suffix` annotation processor option (RC1, 2026-05-22)** — `CodecProcessor` (`integrations/serialization/codec-processor/.../CodecProcessor.java`) accepts a new `-Acodec.registry.suffix=<suffix>` option; `deriveRegistryName(packageName)` appends the suffix to the generated aggregator class name. Each module that contributes `@Codec` types to a shared package now declares a distinct suffix via `<compilerArgs><arg>-Acodec.registry.suffix=...</arg></compilerArgs>` in its `maven-compiler-plugin` configuration. The four modules contributing to `org.pragmatica.aether.slice` (slice, slice-api, node, aether-invoke) generate `SliceCodecsSlice` / `SliceCodecsSliceApi` / `SliceCodecsNode` / `SliceCodecsInvoke` respectively — each in its own classfile, no shade collision. Consumers (`NodeCodecs`, `WorkerCodecs`) reference all suffixed sub-registries explicitly. Without the suffix all four modules previously emitted `org.pragmatica.aether.slice.SliceCodecs.class` and `maven-shade-plugin` retained whichever entry it processed last — the surviving registry held a 1-element list with only `MethodNameCodec`, so cluster-network serializers failed with `IllegalArgumentException: No codec registered for class: org.pragmatica.aether.slice.ExecutionMode` whenever a slice published a `ScheduledTaskKey/Value` to KV via consensus.
- **`system:cluster-events` moved from a node-local view to a replicated partition stream (#239)** — cluster events were previously materialized into a per-node KV/ring-buffer view (the rc1 `ClusterEventLogPublisher` rate-capped KV writer + `ClusterEventLogSweeper` GC + in-process `RingBuffer`); they are now produced into the real, partition-managed, **replicated** `system:cluster-events:1.0.0` stream over the off-heap partition transport. This gives cross-node event visibility (any node's `/api/events` reflects cluster-wide events, not just locally-observed ones). Emit is owner-gated — only the HRW owner of partition 0 produces each event, deduplicating across replicas — and the stream is replicated to all core nodes (RF=N — the replica set ≡ the core set). Retention is enforced by the stream's production `RetentionPolicy` (count/byte/age) rather than the old sweeper.
- **`GET /api/events` `?sinceSeq=` cursor behavior change (#239, review C5)** — the events cursor is now an `Instant` in the namespaced-stream events API. For backward compatibility the legacy `?sinceSeq=` query parameter is still accepted, but its value is now reinterpreted as **epoch-milliseconds** and fed to the new `eventsSince(Instant)` path. An existing caller passing a small opaque sequence number therefore receives events since ~the Unix epoch (effectively "from the beginning") rather than since that sequence position. Operators should migrate to ISO-8601 / epoch-milli timestamps.
- **Writes to `system:*` streams over HTTP are rejected with `405 Method Not Allowed` (#239)** — any mutating verb (`POST`/`PUT`/`PATCH`/`DELETE`) targeting a `system`-namespace stream is refused regardless of role, even when management security is disabled. The check runs ahead of the role/auth pipeline in `ManagementServer`: each identity-bearing write route (`STREAM_PUBLISH`/`STREAM_DELETE` and the catalog-form `STREAMS_PUBLISH`/`STREAMS_DELETE`/`STREAMS_GROUP_CREATE`/`STREAMS_GROUP_DELETE`) resolves its target via the same `ManagementRoute` route-match the real dispatch path uses (not a raw path-segment scan), reduces it to an engine key (via `ResourceAddress` for catalog-form routes, the raw name for flat-form), and rejects when that key names a `SystemStreams.ALL` entry. A route match whose params fail to resolve to a valid identity (malformed namespace/version) fails closed — denied, not passed through. `STREAM_CREATE`'s target name is body-carried, not path-carried, so this pre-auth gate structurally cannot see it; it is covered instead by a separate, post-auth, handler-level guard in `StreamRoutes` that runs unconditionally as the first statement of the sole method that mints a stream, closing the pre-bootstrap race window where a create could otherwise name-squat a reserved stream with caller-controlled config before `SystemStreamBootstrap` registers it. `CONSUMER_GROUP_JOIN`/`CONSUMER_GROUP_LEAVE` carry their target stream name in the request body too and remain uncovered by either mechanism — a known, tracked gap closed by the route-reshape into catalog form. Reads of system streams are unaffected.
- **Stream off-heap allocation is now floor-reserve + lazy elastic growth (#96)** — `OffHeapRingBuffer` no longer pre-allocates its full retention `maxBytes` at creation. It allocates a per-partition FLOOR (header + index + one 256 KiB data segment) up front and grows the data region one segment at a time toward the `maxBytes` cap as data arrives, via a segmented `List<MemorySegment>`. Budget accounting moved to a single CAS `tryReserve`/`release` against `totalAllocatedBytes` (fixes a prior read-then-add TOCTOU); `createStream`/`hydrateEntry` admit the floor (loud `STREAM_MEMORY_EXCEEDED` if it can't fit), growth admits per-segment, and `closeAndRelease` returns the LIVE allocated bytes. Net effect: the per-node 128 MB stream budget (`DEFAULT_MAX_TOTAL_BYTES`) now holds far more streams (a 4-partition 4 MiB-retention stream reserves ~2 MB at create instead of ~17.7 MB), eliminating the ~7-stream wall. EVENTUAL streams that can't grow under pool pressure evict-and-succeed (with event); STRONG streams reject loudly. No segment reclamation on eviction yet (high-water held for stream lifetime; RC2 follow-up).
- **`CLUSTER_EVENTS_MAX_BYTES` default 64 MB → 16 MB** — `system:cluster-events` previously reserved half the per-node 128 MB stream budget, starving app-stream creation; cluster events are small JSON so 16 MB retains many thousands while freeing the budget for app streams.

### Removed
- **Deprecated cause-first `Verify.ensure` overloads removed (deprecation finished)** — the six `@Deprecated(forRemoval = true)` variants taking `Cause`/`Fn1<Cause, T>` as the FIRST argument (unary/binary/ternary predicate shapes × fixed-cause/provider) are gone; the cause/provider is always the last argument now. Zero callers existed across the reactor (full main+test compile verified). Removed before 1.0.0 freezes the API surface.
- **Vestigial transport `NodeRole` (ACTIVE/PASSIVE) vocabulary + its no-op filter sites (overhaul Wave 9, item 3, A8/Q3)** — `consensus.net.NodeRole` was never produced as PASSIVE in production (every construction site hardcoded ACTIVE), so every `isPassive`/`skipPassive`/`PASSIVE` filter was a structural no-op. Deleted the enum, the `role` field on `NodeInfo` + `Hello`, `PeerState.isPassive`/`markPassive`, the `skipPassive` broadcast filter, and the ~12 `!= NodeRole.PASSIVE` / `!isPassive()` filter sites across `QuicClusterNetwork`/`QuicClusterServer`/`QuicClusterClient`/`SwimProtocol`/`TopologyObserver`. `TopologyManager.isPassive` now returns `false` (retained for caller compatibility). The cluster standardizes on the config CORE/WORKER/SPOT vocabulary.
- **`TopologyGrowthMessage` (zero callers) and the TCP/Netty transport (`NettyClusterNetwork` + the `net/netty` Encoder/Decoder/Handler pipeline + its perf test) retired (overhaul Wave 9, items 3/4, D6, M8)** — `TopologyGrowthMessage` was superseded by the `ActivationDirectiveKey` KV path; the Netty transport had no production instantiation path (QUIC is the sole transport). `TransportObservation.ObservationSource.NETTY` removed.
- **CTM dead code stripped (overhaul Wave 9, item 4, M3)** — `ClusterTopologyManagerRecord`'s write-only `realActualStableSinceMs` stability anchors (+ `bumpRealActualStability`/`anchorBootstrapGrace`/`maybeBumpAnchorOnHealthyOnDutyEdge`/`snapshotHealthyOnDutyCount` that only fed them), the retired-slot `inFlightSlotIndices` map + `safetyNetTimer`, the unused `lastObservedRealActual`/`lastObservedHealthyOnDutyCount` counters, and the never-read `inQuorum` constructor parameter (threaded through the `ClusterTopologyManager` factories — `TopologyObserver.inQuorum()` and its real consumers remain live and untouched).
- **Dead `AggregatedReachabilitySnapshot` reachability-aggregator pipeline removed** — deleted the orphaned per-ping reachability snapshot surface that was producer-dead (always emitted `Option.none()` on the wire) and decision-dead (every reader folded to its identity branch — `isReachable(none, _)` was always `true` and the route-layer `transportLag` downgrade always `false`). Removed the `AggregatedReachabilitySnapshot` type (+ nested `ReachabilityState`/`ReachabilityKind`) and `aether/docs/specs/reachability-aggregator-spec.md`; dropped the `aggregatedReachability` field from `ClusterSyncPing` and the `communityReachability` field from `CommunityReport` (collapsing their back-compat constructors/factories into the canonical constructors); deleted `ClusterSyncCollector.{lastReachabilitySnapshot, setLocalSnapshotSupplier, bestSnapshot}`; and simplified `/api/status`, `/api/cluster/status`, and `/api/cluster/topology` core-count/derived-status computation to MembershipView ∪ SWIM ∪ quorum only — identical results, no behavior change. The live SWIM-fed failure detector (`PeerConnectivityObservation`, `PeerObservationStore`/`Buffer`, `ClusterSyncPongSignalFan`) is untouched.
- **Distributed control-plane task-assignment machinery removed (#235)** — deleted `TaskAssignmentCoordinator`, `TaskGroupActivator`, `DelegatedComponent`, `TaskGroupAssignmentRegistry`(+Impl), `TaskAssignmentKey`/`TaskAssignmentValue` (+ their `KVStoreSerializer` cases), the server-side `/api/cluster/tasks` routes (`TaskRoutes`), and the `aether cluster tasks` CLI command (~2,000 LOC net removed). The core cluster is ≤~15 nodes and the 10K-node scale lives in the worker tiers (spokesman/governor election, which are independent of this machinery), so distributing leader-intrinsic control-plane coordinators across the small core gave only marginal load-offload while causing an assignment/reassignment bug class. All control-plane components (`ClusterDeploymentManager`, `ControlLoop`, `RollbackManager`, `TTMManager`, `AbTestManager`, `DeploymentManager`, `DelegatedStorageAdapter`, `StreamingCoordinator`, `DeploymentMetricsScheduler`, `LoadBalancerManager`) are now leader-pinned via `LeaderNotification.LeaderChange` toggles, matching the existing CTM/Reconciler pattern. The `TaskGroup` enum survives only as a management-request routing tag; management owner-resolution now resolves to the leader.
- **`BlueprintResourcesValue` KV round-trip (envelope version 1000 → 1001)** — the publishing-side `BlueprintService.buildAllCommands` no longer emits a `BlueprintResourcesKey` Put, and the consuming-side `DynamicConfigManager.onBlueprintResourcesPut` `@MessageReceiver` plus `applyBlueprintResources` helper are deleted. The `BlueprintResourcesKey` record (`AetherKey.java`) and `BlueprintResourcesValue` record (`AetherValue.java`) along with their `KVStoreSerializer` discrimination/serialize/parse cases are gone. Slice intrinsic TOML is now read locally from `META-INF/resources.toml` in each slice JAR at slice load — no consensus round-trip, no @MessageReceiver dispatch order to race. `ClusterDeploymentState.resolveSchemaRequired` migrated to read `resourcesConfig` from the persisted `AppBlueprintValue.blueprint()` (which already embeds the TOML in `ExpandedBlueprint.resourcesConfig`).

### Fixed
- **Stream backfill no longer false-promotes to `CAUGHT_UP` from a blind/behind source — the write/replication arm of the forward-read fix (#333).** A replica that fell behind with a gap (local tail below the live offset) could wedge permanently: its backfill picked a source from the node-local `ReplicaRegistry` CAUGHT_UP set, but peer watermark state is never propagated cross-node (the production registry uses a NOOP watermark store), so a stale/behind source drove a false `CAUGHT_UP` at a low watermark — after which `ReplicationReceiveHandler` rejected every live batch as a gap forever and the false-ready defeated the read-forward via `selfCoversPartition` (the 5th node stuck on `/api/events` after the read-arm forward fix (#333) recovered the other four). Backfill now extends the read path's owner-forward principle to the write arm: a non-owner pulls catch-up from the DETERMINISTIC HRW owner (computed locally — authoritative for the partition's full history), starting at the local ring head + 1 (so owner-frame offsets land contiguously), and promotes only when it reaches the owner's true tail (`response.toOffset()`); the registry CAUGHT_UP source remains a bootstrap-only fallback. Also corrects the `SelfWatermark` wiring (`PartitionInfo::tailOffset` → `::headOffset`): self's local watermark is the HIGHEST held offset (matching the peer probe), not the earliest retained — the tail value understated self's position, breaking both the cold-start promotion contest's symmetry and the owner-source `fromOffset`. The source's earliest-retained case (replica's tail below the owner's retained window) stays SYNCING safely and is tracked separately (#261). Gated by a deterministic unit test: replica stuck at offset 1, owner at 15 → pulls 2..15, reaches CAUGHT_UP@15, never a false CAUGHT_UP@0; a stale registry peer is ignored in favour of the authoritative owner.
- **`NODE_FAILED` reaches `/api/events` after a node crash on a multi-node cluster — sourced from the ungated FSM DEAD edge, not the quorum-gated membership decision (#210).** After `docker kill` of a non-leader, the cluster auto-heals (the FSM confirmed-death edge fires; SWIM/φ detection works) but no `NODE_FAILED` ever appeared at `/api/events` on cloud, while `NODE_JOINED` arrived fine — the masking asymmetry. The failure event was sourced ONLY from `MembershipDecision.NodeRemoved`, a quorum-gated, drainer-confined projection that the `MembershipDeltaProjector` drops during the post-kill re-election window; `NODE_JOINED`, by contrast, comes from the ungated transport handshake. `NODE_FAILED` is now emitted from `ClusterEventAggregator.onConfirmedDeparture`, wired to the SAME ungated `MembershipFsm.onConfirmedDeparture` DEAD edge that drives auto-heal — so whenever the cluster confirms a death, the leader emits the event (leader-gated: the edge fires on every node's FSM but only the leader publishes, so no fan-out). The projector derives `NodeRemoved` FROM that DEAD edge, so the edge is a strict superset — no failure is lost and there is no double-emit (the aggregator's `NodeRemoved` decision case is now a no-op). Mirrors the earlier `NODE_JOINED` fix (transport handshake, not the unreliable membership delta). `NODE_LEFT` (graceful `NodeDecommissioned`/`NodeDraining`) stays on the consensus decision. Unit-gated (`ClusterEventAggregatorTest.confirmedDeparture_emitsNodeFailed` + leader-gate tests); cloud-validated via 12-network.
- **Auto-heal replacement provisioning now zone-rotates on capacity exhaustion (#334).** Extends the bootstrap zone-fallback (#334) to the runtime auto-heal path: `ClusterTopologyManager.provisionReplacement` now attempts each zone in the source's `effectiveZones()` in order, rotating to the next on `EnvironmentError.CapacityUnavailable` and failing fast on any other error (list exhausted → clear failure). Previously a single zone's capacity exhaustion failed the replacement outright — so an auto-heal during a capacity-tight window left the cluster below target (observed on cloud: replacement node-IDs minted with no backing VM → the provisioning circuit tripped → chaos suites cascaded). Empty `zones` preserves the prior single-attempt `computePlacementHint()` zone-balancing behavior; non-cloud (Docker/forge) providers ignore placement. Async (no blocking await), mirroring the bootstrap rotation.
- **NDM activation self-heals if the consensus-`ACTIVE` edge is dropped at cold-start (latent dropped-edge robustness).** The single CAS-latched `ClusterStateNotification.ACTIVE` notification can be silently dropped while a node's message-router delegate is being rebuilt — `NodeDeploymentManager` then never leaves `Dormant`, so `subsystemsReady` never latches and the node reports `SYNCING` forever despite being a healthy, in-quorum SWIM member. Added a consumer-side level-heal: a 5s periodic tick, gated on live consensus-active (`clusterNode.isActive()`), calls `NodeDeploymentManager.reconcileActivation()`, which re-dispatches `QuorumEstablished` ONLY while the FSM is still `Dormant` (a guarded no-op once Active — the FSM ignores `QuorumEstablished` there), so a dropped edge self-heals within one interval. Consumer-side by design: re-emitting `ACTIVE` on the shared bus would re-trigger every consumer (e.g. duplicate `QuorumEstablished` cluster-events). This closes a verified latent gap (edge-only emission with no level recovery); it is a robustness fix, not a fix for the 02-chaos S20 full-wipe cold-start-formation failure (a distinct membership-formation issue still under investigation).
- **`/api/events` no longer 503s during leader churn — observability stays available throughout re-election (#267).** The #239 cluster-events rework left `/api/events` leader-bound (`RouteTarget.LEADER`), so a follower forwarded the read to the leader and returned 503 whenever no leader was present — exactly during the churn/election when operators most need events. cluster-events is a replicated single-partition stream, so the route is now `RouteTarget.ANY` (served from any core node) and the cluster-events consumer is forward-capable (`ANY_REPLICA`): a node that is not a cluster-events replica read-forwards to a CAUGHT_UP replica instead of reading its own empty partition. The forward-capable consumer was previously reverted to LOCAL (#94/B5) because a fresh replica did not actually hold the partition's history and offsets were unverified; Wave-1 #260 (receiver verifies `fromOffset`, rejects gaps + repairs) and #261 (backfill fires on becoming a replica; CAUGHT_UP only after history coverage) fixed that root, so a forwarded read now returns the full retained window. Documented staleness: a replica-served read reflects that replica's CAUGHT_UP watermark (may trail the owner by the in-flight replication window, typically sub-second under steady load) — acceptable for observability and far preferable to a 503. Aligns `/api/events` with the already-`LOCAL` `/api/alerts` and `/api/traces` reads, which share the same forward-capable consumer. `TopologyObserver.evaluateQuorumState` is CAS-edge-triggered only on structural membership events (add/remove/`setClusterSize`/start) and reads `healthyOnDutyCount()` off a *pull-based* `MembershipView` snapshot, so a transient QUIC/SWIM flap that dropped then RESTORED the snapshot quorum count WITHOUT a structural event had no trigger to re-emit `QuorumEstablished` — Rabia stayed paused after `doPauseForQuorumLoss`, no leader could be minted, and the cluster wedged leaderless permanently (suite 13 drain-aftermath hung 480s). The periodic 5s `initReconcile` tick now re-runs `evaluateQuorumState` (idempotent: the CAS latch routes a notification only on a genuine false→true / true→false edge, so it is a no-op on settled clusters and cannot churn — distinct from the retired per-tick `NodeRemoved` delta emission), bounding quorum-presence recovery to one reconcile interval. Hetzner-validated: the drain-aftermath barrier (`Reactivate_nodes`/`Recovery_complete`) passes; zero spurious PASSIVE edges in cluster logs.
- **Concurrent first-publish to a new stream no longer 503s when the leader is a still-catching-up replacement (Hetzner gate).** Stream publishes are leader-pinned (`STREAMS_PUBLISH` → STREAMING task group → leader); a first-publish auto-creates the stream via a synchronous `node.apply(StreamConfigKey).await(10s)` consensus commit inside `createFreshStream`. When the leader was a freshly-promoted replacement whose own consensus replication was still backpressured, that commit stalled past the 5s management-forward timeout and the publish returned 503 — even though the local partition ring was already materialized. The publish auto-create path (`StreamPartitionManager.ensureStreamMaterialized`, used by `StreamApiRoutes.ensureStreamExists`) now acks on local materialization and fires the config commit ASYNC (retried by the existing uncommitted-entry path); explicit `STREAM_CREATE` keeps its synchronous durability contract, and local-materialization failures (AHSE-required, floor exhaustion) still fail loud. Hetzner-validated: concurrent A+B publishes both return 2xx. Deeper leader-consensus-readiness gating tracked as #329; reconciler over-provisioning convergence as #331.
- **Quorum-loss self-drain no longer fires instantly on the consensus PASSIVE edge — the post-partition-heal self-destruct (overhaul Wave 9, Fix A)** — the `ClusterStateNotification` PASSIVE edge previously triggered an immediate `initiate(QUORUM_LOSS)` process exit: after a partition heal, a transient false-FAULTY storm could drop counted-members below quorum for a few seconds and every node self-destructed before the storm cleared. The PASSIVE/ACTIVE edge is now debounced through the same split-timeout `T` window as the count-driven path (`QuorumLossDetector.onQuorumPresence`): PASSIVE arms a one-shot firing check at `T` measured from this node's local quorum-loss observation (the ratified Wave-9 item-2 design); re-ACTIVE within the window cancels it; the drain fires only if quorum is STILL lost when the window elapses. Arming is gated on the shared cold-start guard (a never-quorate node never self-drains), and the count-path firing check also moves from the old 8s `quorumLossDrainThreshold` to `T`.
- **A death-verdict's own REMOVE no longer co-confirms the death it caused (overhaul Wave 9, Fix B)** — `PeerConnectivityReporter.onPeerDisconnected` now carries a `deathPathInitiated` flag, threaded from the transport view-change path: a REMOVE that THIS node initiated *because of* a death verdict (`departurePermanent` off a SWIM-FAULTY/gossip verdict) is marked and no longer feeds the `MembershipFsm` liveness-gone co-confirmation input — the circular path where a verdict "co-confirmed" the very death it caused (the other half of the post-partition-heal self-destruct). The connectivity observation itself is still buffered (transport coherence); organic closes (`deathPathInitiated == false`) remain independent death evidence.
- **SWIM: a re-ANNOUNCE from a known member now refreshes its probe address (overhaul Wave 9, Fix C — stale-IP false-FAULTY root)** — after a partition heal, container restarts can reshuffle IPs; a resident member's cached probe address went stale, direct+indirect probes hit the OLD address, and the Wave-6 ack-origin identity guard (correctly) rejected any mismatched ack — so a perfectly healthy member was declared FAULTY on stale-address evidence. `handleAnnounce` for an already-known member now adopts the fresh source-derived probe address (`refreshProbeAddressIfChanged`, INFO-logged transition) instead of only updating incarnation. Known residual: a heal where the member never re-ANNOUNCEs (it only refutes suspicion) is not yet covered — the refresh hook exists but nothing fires it on refutation traffic; tracked as the remaining B5-facet-2 work.
- **CDM stale-entry cleanup racing the membership wiring no-ops instead of mass-classifying (overhaul Wave 9, item 5, M4)** — `cdmCoreCountedMembersSupplier` fell back to `Set.of()` before the FSM holder was populated (boot window), so a cleanup pass racing the wiring saw "zero counted members" and could classify every KV-known member as departed. The supplier now yields the identity-distinguished `MembershipFsm.MEMBERSHIP_NOT_WIRED` sentinel, and the cleanup treats it as "membership unknown — no-op".
- **Leader adoption and the reciprocal-dial wedge (the pre-existing B5-facet-2 "READY-convergence 600s" roots), found by the Wave-8 gate.** (1) **Dead grace-bypass made higher-id nodes permanently unable to dial never-initiating lower-id peers**: the missing-peer reconciler's 60s higher-id grace correctly elapsed and called `connectPeer`, but `connectPeer` independently re-checked the single-dialer guard and silently returned on every tick — the exact mechanism behind a leader never dialing its joined replacements (journal: two FSM Members held undialed while their twin was dialed at its real-incarnation edge) and a joiner's 15-minute `phase=INIT` re-dial loop. Fixed by threading a `forceInitiate` flag through the grace-elapsed reconcile path; every formerly-silent dial exit now logs its cause. The dial set itself was proven incarnation-agnostic (regression-locked). (2) **Leader adoption was transport-vetoed, then — after removing the veto — corpse-prone**: a consensus-committed `LeaderKey` was rejected on adoption when the leader was absent from the transport-fed topology view ("leader not in topology", the silent 500ms-pull skip that left joiners leaderless), but full unconditionality re-adopted a DEAD leader's stale commit during re-election (Kill_leader_and_re-elect wedged 300s). Final semantics — **adoption gated by sequence, not transport, not membership**: Electing/ReElecting snapshot the observed-committed `viewSequence` at entry and adopt only commits that POSTDATE the decision to elect; all other states adopt unconditionally (a committed value is consensus-validated truth). Convergence proven both directions: re-election in 2s (stale corpse skipped, successor fence-committed, all electors adopt), fresh joiners adopt the healthy leader in `AwaitingKvSync` before ever electing, simultaneous electors converge without ping-pong. Docker-validated: 02-chaos Kill_leader 6→2s, Kill_2_nodes restore green; the suite's only residual (S20 ROUTING→ACTIVE wedge) is a distinct pre-existing defect (same 2/3-ACTIVE signature pre-dates Wave 8), filed separately.
- **SWIM correctness batch (overhaul Wave 6): incarnation fabrication, FAULTY residency (H8) decoupled from the death signal, Ack origin check, stale self-suspicion gate.** (1) `applyAliveFromAck` promoted a remote member with a *fabricated* `incarnation+1` gossiped cluster-wide — a probe-ack is alive-evidence at the OBSERVED incarnation only; promotion now preserves it and incarnation advancement is exclusively self-owned (`handleSelfUpdate` refutation ordering verified). (2) H8 confirmed and fixed: the residency stamp `transitionToFaulty` wrote was deleted same-tick by `expireSuspectIfOverdue`, so FAULTY members were swept 2–4ms after their FAULTY edge instead of the designed 30,000ms; `faultyStampedAtMs` (the Wave-1 diagnostic map) is now the authoritative residency clock, written on all three FAULTY-entry paths, honoured live (31s measured). Crucially, the cluster death signal was **decoupled from the sweep**: `DepartedObserved` — the only SWIM observation that drives FSM death (`SwimDeparted → Departing`) — was emitted ONLY from the residency sweep, structurally delaying SWIM-driven death by +30s (measured live: `NODE_FAILED` at ~55–65s vs the 60s SLO on churned clusters — the spec §6.6 "same-tick sweep is load-bearing" caveat, empirically confirmed); it now fires at the FAULTY edge as an atomic `FaultyObserved`→`DepartedObserved` pair (FAULTY *is* confirmed death; the LHM/dogpile-scaled suspicion window is the refutation time; the sweep is map hygiene only, retention/refutability unchanged). (3) An `Ack` counts as probe-ack evidence only when its `from` matches the probed/relayed target; mismatched or unsolicited acks no longer promote anyone and don't consume the pending probe (relay verification via `RelayInfo.targetId`). (4) `handleSelfUpdate` acted on EVERY received `Suspect(self)` gossip receipt — each stale re-broadcast re-bumped self-incarnation and incremented LHM (healthy idle nodes pinned at score 6–8, sustained incarnation churn in the gossip plane); stale receipts (incoming incarnation < current) are now ignored entirely. Docker-validated: 02-chaos **6p/0f** (under-load departure event green, error-rate facet green), 12-network **4p/0f** with transport-led detection in **1s** and the SWIM FAULTY backstop at ~12s; swim unit suite 130/130 (3 pre-existing red tests rewritten to the post-`188e0b522` join-grace-defer contract). Companion issue filed from the same gate evidence: #284 (CDM deployment retry storm — no backoff/blacklist + replacement artifact-distribution gap — flooding the consensus event log).
- **Management API: `whoami` now surfaces the authenticated principal, and `/api/certificates` reports `NOT_CONFIGURED` when app-TLS is off (#95).** Two independent management-API correctness defects exposed by 05-security: (1) `GET /api/whoami` returned `authenticated=false, authorizationRole=VIEWER, principal=anonymous` for a valid ADMIN api-key even though the *same* key was honored for route authorization (401/403/200/RBAC all correct) — `ManagementServer.validateManagementSecurity` resolved the full `SecurityContext` only to make the allow/deny decision and then collapsed it to a `boolean` (`.isSuccess()`), and the management dispatch path never bound `SecurityContextHolder`'s `ScopedValue` the way `AppHttpServer` does, so `whoami` read an unbound scope and fell back to the anonymous context. Fixed by returning the resolved `Result<SecurityContext>` and dispatching authenticated management requests inside `ScopedValue.where(SecurityContextHolder.scopedValue(), sc)` (security-disabled requests dispatch unscoped → anonymous, as before; denied requests still stop after the 401/403). This completes the principal-injection contract (rbac-spec §5.5/§6.7) on the management port — every management route can now read the caller's identity, not just `whoami`. (2) `GET /api/certificates` reported `renewalStatus=HEALTHY` while `tlsEnabled=false`: the QUIC cluster transport always builds a self-signed cert provider, so a `CertificateRenewalScheduler` is always created+started and legitimately reports HEALTHY, but `StatusRoutes.toCertificateStatus` emitted that status without consulting `tlsEnabled`. Fixed with a presentation-layer guard: `!tlsEnabled ⇒ NOT_CONFIGURED`. Both unit tests previously masked the bugs by faking the precondition (empty scheduler / manually-bound scope); the cert test now covers the real `(tlsEnabled=false, scheduler present)` case. Docker-validated: 05-security **3p/0f** (was 1p/2f) — `renewalStatus=NOT_CONFIGURED`, admin `whoami` resolves to `authenticated=true / ADMIN / api-key:<name>`, and unauthenticated/invalid-key enforcement (401/403, WWW-Authenticate) unregressed.
- **All cluster-canonical cluster-events are now LEADER-gated, not owner-gated.** Generalizes the #94 departure fix: `NODE_JOINED` (`onPeerJoined`), `LEADER_ELECTED`/`LEADER_LOST` (`onLeaderChange`), `QUORUM_ESTABLISHED`/`QUORUM_LOST` (`onQuorumStateChange`), `NODE_LIFECYCLE_CHANGED` (`onNodeLifecycleChanged`), and `GENERATION_CHANGED` (`onGenerationChanged`) now emit via `ClusterEventAggregator.emitAsLeader` (gated on the leader) instead of `emit` (gated on the HRW owner of the `system:cluster-events` partition-0). Owner-gating dropped these cluster-wide facts whenever the partition owner was mid-churn or not observing the event — the root of a lost `NODE_JOINED` for a CTM replacement (12-network: a replacement joined and the cluster recovered to 5 healthy cores, but no `NODE_JOINED` reached `/api/events` within 180s). `NODE_JOINED` keeps the transport `PeerJoined` handshake as its source (the membership delta does not fire for a not-yet-counted JOINING replacement, so `MembershipDecision.NodeJoined` stays a documented no-op); the leader, which dials every core member, observes the handshake. Operational events (deployment/scaling/blueprint/config/backup) remain owner-gated; per-node facts (stream budget exhaustion) remain node-local. Docker-validated: 12-network `Replacement … observed on /api/events` now passes (quic-connectivity 2p/2f → 3p/1f, the residual being the env-transient `connectedPeerCount`).
- **`NODE_FAILED`-within-60s for replacement-node deaths under load (#94, B5).** A CTM replacement (KSUID) that died at steady core size never fired a `MembershipDecision.NodeRemoved`, so no `NODE_FAILED` event reached `/api/events` and `/api/nodes/status` over-provisioned (count stuck). Root: `NodeRemoved` was emitted only *incidentally* — `TopologyObserver.publishCoreMembershipDelta` recomputes the core-membership delta only via `evaluateQuorumState`, which an original's death re-ran solely through the follow-on `addNode` of its auto-heal replacement; a replacement's death has no following join, so the diff never re-ran and the removal was never advertised. Fixed by edge-triggering the recompute once-on-death: `MembershipFsm.onConfirmedDeparture` now also routes a new `NetworkServiceMessage.ReevaluateMembership` to the `TopologyObserver` (dispatched on the router thread, mirroring the SWIM-discovery path), whose handler re-runs `evaluateQuorumState()` — CAS-gated on quorum and `previousCoreMembers.getAndSet`-gated on the delta, so it is idempotent and fires exactly one `NodeRemoved`. Once-per-edge, **not** per reconcile-tick (per-tick re-evaluation regressed READY-convergence to 600s). Departure-event *delivery* was also moved from owner-gated to **leader-gated** (`ClusterEventAggregator.emitAsLeader` — the just-removed node is frequently the cluster-events partition owner, which would suppress its own `NODE_FAILED`) with a leader-local `/api/events` read, and every `ClusterEvent` now carries a `type()` discriminator (`NODE_FAILED`, …) via a `ClusterEventView` DTO so the events JSON is self-describing. Docker-validated: 02-chaos **6p/0f** (replacement removed from membership in 1s, `NODE_FAILED` delivered under active load, READY-convergence ~20s across restores — no regression).
- **`GET/DELETE /api/nodes/{id}/lifecycle` for an unknown node returns `404 Not Found`, not `500`** — a lifecycle lookup miss is mapped to `HttpStatus.NOT_FOUND` instead of surfacing as an internal error.
- **In-flight provisioned (dispatched) node IDs now survive leader change** — the in-flight provisioned node IDs are propagated via the leader-broadcast `ClusterSyncPing` (sticky, term-fenced), so a newly-elected leader inherits in-flight provisions instead of over-provisioning the cluster on failover.
- **Stream-registry keys survive snapshot/restore (#239, review C1)** — the KV-store snapshot serializer now round-trips `StreamConfigKey` and `StreamRegistryKey` entries. `StreamRegistryKey` snapshots as an 8-field pipe-delimited value (`address|refCount|registeredAtEpochMillis|registeredBy|maxCount|maxBytes|maxAgeMs|retentionMode`) with matching `serialize`/`parse` arms; `tierAwareRetention` is reconstructed as `none()` to match the stream-config convention. Without the parse arms a snapshot restore silently dropped stream-registry state (refcounts and registrations), so a node recovering from a snapshot lost its view of registered streams.
- **Auto-heal: CTM-provisioned replacements no longer crash-loop at boot.** `DockerComputeProvider` stamped a replacement's `aether.cluster` label, container name, and `NODE_ID` from the KV-authoritative `ProvisionContext.clusterName`, but forwarded the `AETHER_CLUSTER_NAME` env verbatim from the provisioning (leader) node's process env via the `ClusterIdentityEnv.IDENTITY_VARS` allow-list. When those disagreed (e.g. compose env `b` vs bootstrapped cluster name `integration-test`), the replacement's boot-time label-consistency guard (`Main.verifyClusterLabelConsistency`) saw env≠label and `System.exit(1)` — and CTM retried forever, a provisioning storm that prevented the cluster from ever healing (and starved generation quiesce). Now `AETHER_CLUSTER_NAME` is emitted from `clusterOrDefault(ctx)` — the same authoritative source as the label — with the allow-list loop de-duping it. The cloud path (`UserDataTemplate`) already sourced it correctly from the threaded cluster name.
- **Transport: a transiently-partitioned same-incarnation peer can rejoin (incarnation-fenced REMOVED reversibility).** The QUIC transport drove a co-confirmed-dead / FAULTY-swept peer to the terminal `PeerState.Phase.REMOVED`, an irreversible dead-end that ignored the membership layer re-admitting the same NodeId — contradicting the spec invariant that only a strictly-higher incarnation is terminal (a same-incarnation peer must rejoin). `PeerState.readmit()` now resets `REMOVED → INIT`, invoked from the missing-peer reconciler (dial side) and `onPeerConnected` (accept side), both gated on `swimMembershipAllows` (`coreNodes()`, the SWIM incarnation authority that only re-admits after `supersedeOrRefuse`). The SWIM probe-ack remains the sole ALIVE authority, preserving the anti-resurrection guarantee — a peer absent from `coreNodes()` (genuine/stale departure) stays terminally REMOVED.
- Membership incarnation: unified onto a single SWIM self-incarnation authority; removed the `System.nanoTime()` boot-epoch (arbitrary per-JVM origin, not monotonic across restarts) that the metrics readiness epoch used — membership and readiness now key `(NodeId, incarnation)` on one boot-time-monotonic value.
- Node identity: require an explicit, stable node id (`--node-id`/`AETHER_NODE_ID`/`NODE_ID`) — removed the HOSTNAME and random-id fallbacks that let a node boot under a fresh-every-restart identity mismatched from its PEERS entry; missing id now fails fast at boot.
- Canonical node naming: blank-cluster prefix no longer yields `aether--node`/bare `node-`; auto-heal replacements always share the canonical `aether-<cluster>-node-` prefix.
- **Provider-minted replacement nodes now reach `READY` in the leader's view (#34)** — a joining replacement kept advertising itself via SWIM ANNOUNCE until acknowledged by a peer instead of self-suppressing once the cluster became quorate, and the ClusterSync ping set now unions the delta-fed topology with the live connected-peer set. Together this lets a freshly provisioned node's presence propagate to the leader so it transitions out of `SYNCING` to `READY` rather than lingering invisible after auto-heal.
- **Replacement killed during its joining window is now re-provisioned** — `LeaderReconciler`'s in-flight sweep self-reschedules so a replacement that dies before completing its join (within the in-flight expiry window) is detected and re-provisioned, instead of leaving the slot permanently unfilled.
- **Cluster-identity environment now propagated to provisioned replacements across all compute providers** — a shared allow-list (`AETHER_CLUSTER_NAME`, `AETHER_CLUSTER_SECRET`, `AETHER_PROVISIONED_BY`, `AETHER_API_KEY`; Docker additionally `AETHER_DOCKER_NETWORK`, `DOCKER_GID`) is forwarded to replacement nodes — Docker via `-e` flags, cloud providers via cloud-init `userData`. Azure now honors `userData` (mapped to `customData`), which it previously dropped, so Azure replacements receive cluster identity like the other providers. The literal `"default"` cluster-name fallback was removed.
- **Data-path request forwarding filters targets by live membership** — candidate target nodes for forwarded/retried data-path requests are now intersected with NTT's live membership (`keepOnlyAccessible`), so a hard-killed node that still appears transport-connected is no longer selected; requests are no longer routed or retried into a node that has left the cluster, improving routing correctness during node-kill chaos.
- Membership: SWIM detector and join-ANNOUNCE now start at transport-ready instead of being gated on consensus quorum-ESTABLISHED — fixes a sub-quorum auto-heal deadlock where a higher-KSUID replacement could never advertise itself to survivors, leaving its consensus `connectedNodes` stuck at 0 and the cluster wedged. Restores the spec-mandated SWIM-before-announce-before-quorum bootstrap ordering. (#230)
- **Paused Rabia responder serves a live-equivalent sync snapshot with `pendingBatches` (#230)** — `RabiaEngine.doHandleSyncRequest` previously answered a `SyncRequest` from the persisted/empty snapshot when the engine was `Paused`, dropping the responder's in-flight `pendingBatches`. A `Paused` responder retains its full in-memory protocol state, so it now serves the SAME live-equivalent snapshot (stateMachine snapshot + `currentPhase` + `pendingBatches`) that an `Active`/`Observing` responder serves — letting a syncing joiner re-propose in-flight batches instead of losing them. Latent correctness fix found during cluster-B recovery diagnosis.
- **CTM aborts a slot-fill that cannot preserve a confirmed-healthy quorum (#230)** — the cluster topology manager now refuses a slot-fill when committing it would leave the cluster without a quorum of confirmed-healthy voters, routing to dissolve instead of provisioning into an unrecoverable below-quorum state. Previously a fill could proceed during churn and drive the cluster below quorum with no recovery path.
- **Compute providers confirm an instance reaches `RUNNING` before reporting provisioning success (#230)** — provisioning now gates on a readiness check: a provider returns success only once the instance is observed `RUNNING`, and on timeout it fails the provisioning request and frees the slot instead of registering an unstarted phantom node. Previously a provider could report success the moment the cloud/Docker API accepted the create call, so a node that never finished booting still consumed a slot and left the cluster permanently under capacity.
- **Leader-side φ-accrual failure detection replaces the 2-plane `ReachabilityGate` (#231)** — black-holed nodes (QUIC channel open but silent) never produced a transport `NODE_FAILED`, so they lingered `ON_DUTY` past their decommission window; the old `ReachabilityGate` required two independent planes (transport-unreachable + aggregator quorum) to co-confirm, and a silent-but-connected peer satisfied neither. Detection is now driven by a leader-side φ-accrual detector (`PhiAccrualDetector`/`PhiAccrualConfig`) feeding the `MembershipFsm` via `PhiObserver`: the leader accrues φ per peer from inbound signal interarrival and decommissions on sustained suspicion. SWIM handoff is warmth-conditional (`PhiWarmth`) — a peer only hands off to the φ verdict once its detector window is warm, so a cold-start peer is never decommissioned on an unseeded estimate. Leader-stall guards prevent a stalled leader from manufacturing false positives: K=5 consecutive-tick debounce before a verdict fires, the stall pass is skipped when no pong has advanced (the leader's own clock is stalled, not the peer), a quorum self-guard, and self-incarnation exclusion. σ-floor of 200ms bounds the variance estimate so a tight, regular signal stream cannot collapse the suspicion threshold to a hair-trigger.
- **ClusterSync metrics/health gossip resumes after a leader change (#235)** — ClusterSync (the 1s metrics/health/topology ping-pong) was a delegated component whose adapter overloaded the quorum events (`activate()→QuorumEstablished`, `deactivate()→QuorumDisappeared`); when its task group was reassigned the FSM was driven to `Dormant` with no resume path, so after every leader change the new leader's `ReachabilityAggregator` and failure detection went blind — a significant contributor to leader-kill churn. ClusterSync ping-dispatch is now leader-pinned: only the current leader runs the ping cycle (followers remain responsive via the unconditional pong response), and the leader gate is re-evaluated every tick so a newly-elected leader resumes gossip immediately. This also removes an all-to-all ping topology that, combined with a latent leader-term-fencing asymmetry (a stale-term ping is dropped without a pong), could amplify into a cluster-wide eviction-hint storm and collapse a cluster under leader churn.
- **Slot occupancy can no longer wedge `FILLING` indefinitely (#230 recovery backstop)** — `ClusterTopologyManagerRecord.classifyOccupied` mapped an occupied slot's `JOINING`/`DRAINING`/absent occupant lifecycle to `FILLING` but never consulted the slot's stamped `deadlineMs`, so a slot bound to a node killed before reaching `ON_DUTY` (occupant lifecycle stuck `JOINING`/absent, never reaching `STOPPED`) stayed `FILLING` forever — `selectEmptySlotsToFill` excluded it (`emptyToFill=0`) and nothing re-provisioned, leaving the cluster permanently under capacity after chaos kills. New `freeStaleFillingSlots` reconcile pass (parallel to `freeDeadSlots`, reusing `freeSlot`) reclaims an occupied slot when it is classified `FILLING`, past its stamped `deadlineMs`, AND its occupant has left `connectedCoreMembers()` — the disconnected-occupant gate keeps a connected-but-slow `JOINING` node's slot (matching the §5 self-drain live-occupant predicate), so a live node is never reclaimed (unlike the removed leader-side surplus reaper). Freed slots union into the same pass's `freedIndices` and refill immediately; the reconcile log now reports `freedStale={}` alongside `freedDead={}`.
- **Failure detection no longer starved under load (scheduler substrate fix)** — `SharedScheduler` now delegates to a new `VirtualThreadScheduler` (`core/src/main/java/org/pragmatica/lang/utils/VirtualThreadScheduler.java`): one platform timer thread owns a deadline-ordered `DelayQueue` and dispatches every task body to a virtual-thread-per-task executor, decoupling timekeeping from execution. The previous fixed-size `ScheduledThreadPoolExecutor` (`max(cores,8)`) was shared process-wide by ~40 subsystems; under load, blocking/long task bodies consumed all worker threads and delayed the latency-critical detection ticks (SWIM probe, ClusterSync ping, aggregator dispatch), so a killed node's `NODE_FAILED` could miss its 60s budget. Microbench: detection dispatch-lag dropped from ~7.3s (STPE, all workers blocked) to ~6ms (VT). Confirmed on Docker cluster-B: kill-under-load detection went from `No NODE_FAILED within 60s` to `Departure observed under load` (fast `transport-failure` path). Also fixes STPE's silent-cancel-on-throw footgun — a throwing periodic task is now logged and its recurrence preserved.
- **Churn-window decommissions no longer dropped (#230)** — `MembershipFsm.applyLifecycleCommand` now leader-gates ONLY the promotion commands (`ForceOnDuty`, `RecordJoining` — the sole re-projection vectors); `ForceDecommission`/`ForceDrain`/`RequestReJoin` propose unconditionally, restoring the legacy `DirectLifecycleWriter` liveness so a decommission issued during a leader-transition window is no longer silently dropped (the cluster-B ghost-`ON_DUTY`-accumulation regression). Docker cluster-B `kill-node` went 2/2 → 5/0 with no regression.
- **`DockerComputeProvider` mints node identities via the shared KSUID `IdGenerator`** — replacement/provisioned containers now derive their `NodeId` from the cluster-wide `IdGenerator` (KSUID), matching the cloud providers, instead of reusing per-slot ordinals that could collide with departed nodes. The `container_name` still equals the minted `NodeId` so `docker kill <nodeId>` remains the authoritative chaos handle; replacement nodes are now overlay-only (ephemeral host port).
- **SWIM-ALIVE guard on ClusterSync ping-timeout eviction (cluster-B stability)** — `ClusterSyncContext` ping-timeout handling could evict a peer that was still SWIM-ALIVE, treating a metrics ping-pong stall as a membership departure. Under chaos this triggered a self-drain cascade that collapsed cluster B: a transiently slow pong was misread as peer loss, the eviction shrank the locally-observed live set below quorum, and survivors self-drained in turn. The ping-timeout eviction path now guards on the peer's SWIM liveness — a peer that is still SWIM-ALIVE is no longer evicted on ping-timeout alone, so transient metrics-channel stalls no longer manufacture spurious departures.
- **Idempotent QUIC REMOVE emission in `disconnect` / `departurePermanent` (cluster-B stability)** — `QuicClusterNetwork` could emit a peer-removal (`REMOVE`) view-change hint more than once for the same peer when both the disconnect and permanent-departure paths fired (or either path re-fired), producing duplicate peer-removal events that fanned out into redundant view-change storms across the cluster. REMOVE emission is now idempotent per peer — the second and subsequent emissions for an already-removed peer are suppressed, eliminating the duplicate view-change churn.
- **Audit lifecycle stream right-sized to fit the 128 MiB off-heap stream budget (cluster-B stability)** — `AuditLifecycleStreams` requested a ~347 MiB off-heap allocation, overshooting the 128 MiB per-node stream budget; the over-budget request caused `CommandLifecycleEvent` publishes to be dropped at node startup, silently disabling audit-lifecycle observability. The stream is now sized to fit within the 128 MiB budget so lifecycle-event publishes succeed from startup.
- **`CommandLifecycleEvent` audit codec registered at the system level (`NodeCodecs`/`WorkerCodecs`)** — the `LifecycleReconciler` was aborting on every 10s tick because the audit `CommandLifecycleEvent` codec was never registered, so automatic node decommission/cleanup never ran. Registering the codec at node/worker startup revives the reconciler.
- **`JoiningTimeout` reconciler rule routes orphaned `JOINING`-peer cleanup through the membership FSM** — cleanup now emits a proper `swim-departed` domain event instead of an operator-forced removal; the reclaim budget is tightened from 90s to 45s while the FSM join deadline stays 60s.
- **Self-drain no longer collapses the cluster on node loss** — `SelfDrainCoordinator` computed its quorum threshold as `(topology().size()/2)+1` over the RAW topology list, which is inflated by dead/decommissioned/CTM-replacement nodes during churn; this drove the threshold above the live majority so survivors self-drained even with a healthy quorum, cascading to total collapse under chaos. It now uses the authoritative `TopologyManager.quorumSize()` (derived from the fixed `clusterSize()`, split-brain-safe) directly, so a 5-node cluster correctly tolerates 2 failures.
- **Consensus messages no longer dropped under QUIC backpressure** — `QuicClusterNetwork` previously discarded consensus-stream messages when a peer's QUIC stream hit Netty's high-watermark (relying on retransmits that also dropped), which under command bursts during cluster formation/deploy starved Rabia quorum and caused intermittent "N-1 members" formation-gate failures and 30s `cluster.apply` deploy timeouts. CONSENSUS sends now wrap an async, non-blocking short-interval `Retry` (poll until the stream drains; configurable via `QuicTransportTuning`) and the CONSENSUS stream's write-buffer watermark is raised so command bursts fit. DHT-stream fast-fail behaviour is unchanged.
- **Deploy resolution resilience under cluster formation** — the DHT read/resolve path now has bounded, configurable retry (mirroring the write path) so a transient quorum read against a still-joining peer no longer stalls `/api/blueprints/deploy` for the full 30s timeout; blueprint dependency resolution runs concurrently per topological level; and `RabiaEngine.apply` is bounded by a configurable `ProtocolConfig.applyTimeout` (default 30s) instead of hanging indefinitely. Fixes 06-deployment server-side deploy timeouts and 09-artifacts large-artifact resolve failures under parallel-bootstrap load.
- **CTM provider-owned NodeId identity** — the ComputeProvider now allocates and returns the node id via `InstanceInfo.nodeId`; CTM follows the two-phase unassigned→assigned provisioning-slot flow instead of pre-generating a guessed id. Eliminates ghost `aether-core-node-*` JOINING entries that broke cluster formation (Validation #9 00-smoke gate). Deficit math now counts `JOINING` members as in-progress capacity, preventing over-provisioning waves during cluster formation.
- **Deployment `ROLLING_BACK` → `ROLLED_BACK` terminal advance after rollback routing applied (RC1, 2026-05-23)** — `DeploymentManagerImpl.applyRollbackRouting` previously persisted the deployment with state `ROLLING_BACK` and stopped; the FSM defines `ROLLED_BACK` as the terminal sink but no caller advanced to it after the route restoration commands committed via consensus. Stuck-in-`ROLLING_BACK` deployments cascaded `06-deployment`: `deploy_cleanup` between tests tried `POST /api/deploy/rollback/{id}` and `POST /api/deploy/complete/{id}` on the same id, both returning 500 (`Invalid deployment state transition: ROLLING_BACK -> ROLLING_BACK` and `ROLLING_BACK -> COMPLETED`), and the next test's `Canary_start` then 500'd with `Deployment already in progress for blueprint:`. Fix: new `Deployment.rolledBack()` method (mirrors `complete()` / `deployed()` shape, transitions to `DeploymentState.ROLLED_BACK`), and `applyRollbackRouting` chains through it before `applyConsensus` so the same consensus batch that flips routes back to v1 also stamps the deployment terminal. `test-deploy-blue-green.sh::test_blue_green_rollback` already accepted either `ROLLING_BACK | ROLLED_BACK` ("terminal/transitional") in its assertion, so the test passes either way; `deploy_cleanup` (which already skips `ROLLED_BACK` entries) now correctly drops the rolled-back deployment from subsequent test pre-checks.
- **`07-cluster-mgmt/Config_identical_after_re-apply` filters `scaling-cooldown/*` from round-trip equality (RC1, 2026-05-23)** — the test exports config, idempotently re-applies the same `{key,value}` pair, exports a second time, and asserts canonical-form equality between the two snapshots. The `scaling-cooldown/<slice-coords>` keys returned by `/api/config` carry the timestamp of each slice's last scale-action and bump on the scheduler's own cadence, independent of any user-applied config — so a re-export taken ~3-4s later legitimately differs from the first one. These keys are runtime/observability metadata, not user-authored config, and don't belong in a round-trip identity check. Fix: both `first_canonical` and `second_canonical` pipelines now `grep -v '"scaling-cooldown/'` before sorting. The contract being tested ("user-applied config round-trips through export → apply → export untouched") is preserved; the unrelated maintenance-metadata drift is excluded.
- **`PackageSlicesMojo` now packages `META-INF/resources.toml` into per-slice JARs (RC1, 2026-05-22)** — the `jbct-maven-plugin` mojo that builds the per-slice impl JAR (e.g. `test-persistence-persistence-slice-1.0.0.jar`) previously copied only impl classes, shared code, external lib classes, the dependency file, the slice service file, and the slice manifest into the archive. The slice's `src/main/resources/resources.toml` (Maven copies it to `target/classes/resources.toml`) was NOT bundled — the slice classloader's `getResourceAsStream("META-INF/resources.toml")` returned null and the new layered composite's slice.toml layer was an empty `IntrinsicConfigProvider`. After this fix the mojo also copies `classesDirectory/resources.toml` to `META-INF/resources.toml` inside the slice JAR (mirroring the pattern the `PackageBlueprintMojo` already uses for the blueprint JAR). Resolves the `Deploy_SQL_app` regression introduced by the Batch 1 layered-config refactor: with intrinsic config empty, `@PgSql` resource provisioning hung waiting on the missing `[database]` section and `slices active >= 1` timed out at 240s. Also: `SliceStore.loadSliceIntrinsicProviderFromClassLoader` log uplift — successful intrinsic load now logs INFO with the resolved key count instead of staying silent at DEBUG.
- **TOML record-binding key naming uses snake_case (RC1, 2026-05-22)** — `ProviderBasedConfigService.collectComponentAt` calls `toSnakeCase(component.getName())` on Java record component names before looking up values in the provider (`scheduling.heartbeat.executionMode` → `scheduling.heartbeat.execution_mode`). The two integration test blueprints (`test-persistence`, `test-full`) shipped `[scheduling.heartbeat] executionMode = "SINGLE"` (camelCase), so the binder reported `Config section not found: ScheduleConfig.executionMode` and `resolveScheduleConfig` silently returned `Option.empty()`. Both `resources.toml` files switched to `execution_mode = "SINGLE"`. Sibling improvement: `NodeDeploymentState.resolveScheduleConfig` now surfaces `ScheduleConfig` bind failures via a WARN log (`Slice {} schedule config binding failed for section {}: {}`) — previously the failure was swallowed by `.option()` and the dropped scheduled-task registration was diagnostic-only via the missing `/api/scheduled-tasks` entry.
- **`SliceInvoker.findSenderBridge` uses artifact-based lookup with classloader fallback (RC1, 2026-05-22)** — the helper previously did `invocationHandler.findBridgeByClassLoader(request.getClass().getClassLoader()).unwrap()`. For request types that genuinely live in the slice's classloader the lookup worked, but for `Unit.unit()` (the `Object` payload the `scheduled-tasks/inject` route passes for zero-arg `@Scheduled` methods) the class lives in `pragmatica-core` under the parent loader — the slice's bridge wasn't registered under that loader and `.unwrap()` threw `IllegalStateException: Option is empty`. Fix: `findSenderBridge(Artifact slice, Object request)` consults `invocationHandler.localSlice(slice)` first, falling back to `findBridgeByClassLoader(...)` only when the slice has no local bridge on this node. All four call sites (`sendFireAndForget`, `sendRequestResponse`, `invokeRemoteForFailover`, `invokeViaBridge`) updated; `invokeViaBridge` simplified to use `targetBridge.encode(request)` directly since the local-invoke target bridge IS the slice's own bridge.
- **`AETHER_INSECURE_DEV_MODE=true` restored on cluster A + B compose** — the env var was removed in an earlier `feat: remove AETHER_INSECURE_DEV_MODE, require deterministic QUIC TLS via AETHER_CLUSTER_SECRET` commit (focused on the TLS handshake path), but the gate is also consulted by the dev-mode-only inject endpoints (`/api/scheduled-tasks/inject`, `/api/alerts/inject`, `/api/traces/inject`, `/api/dht/inject`, `/api/certificates/configure-short-validity`). Inject endpoints returned `500 Internal Server Error — scheduled-tasks inject requires AETHER_INSECURE_DEV_MODE=true` on the integration test cluster. `aether/tests/integration/docker-compose-a.yml` + `docker-compose-b.yml` now set the var explicitly in the `&node-env` block with an inline comment documenting that 05-security's TLS rotation tests explicitly opt OUT.
- **02-chaos S01 smoking-gun branches on race-to-ON_DUTY (F3)** — when the replacement node R "raced past JOINING into ON_DUTY before kill," `test-joining-window-kill.sh::verify_transport_unreachable_event` could no longer find the expected `reason=transport-failure` / `reason=swim-faulty` log lines on surviving nodes. The ON_DUTY cells for both reasons are gated by `gate.isConfirmedUnreachable()` (aggregator quorum); the kill landed inside the quorum-formation window and both gated cells produced `Outcome.nop`, so the decommission proceeded via the ungated `(ON_DUTY, SwimDeparted)` cell with `reason=swim-departed` — outside S01's accepted reason set. Fix: the catch-step writes a `RACE_TO_ON_DUTY_FILE` marker; the smoking-gun test now `skip_test`s in the race branch (the 25s budget assertion above carries the contract there), and asserts the strict regex only when the kill genuinely landed in the JOINING window. CHARTER §C10 entry rewritten from "PARTIAL widened-acceptance" to "SOUND-with-branch".
- **`08-resources` defensive waits + loud failure surfacing** — `Last_execution_advances` polls `/api/scheduled-tasks` for at least one task to surface (suite bootstrap deploys test-persistence async; slice instance activation lags `await_generation_quiesced` by up to 44s on cold @PgSql provisioning); `Put_KV_pair` polls the PUT route until non-404 before asserting (GET-based `route-probe` doesn't detect PUT-route propagation lag). Both tests now wrap their inject/CLI calls with `set +e` + explicit error-JSON detection so `set -euo pipefail` doesn't silently abort the sub-suite on `grep -oE` no-match in an error response — previous behavior caused test-scheduled-tasks.sh to die between `Last_execution_advances` and `Pause_task` with no log_fail emitted.
- **Artifact resolve of large artifacts (≥1MB)** — fixed three compounding defects: storage silently dropped retried chunk writes (claim not released on write-through failure), unbounded chunk fan-out saturated the DHT QUIC lane past its backpressure watermark, and the DHT lane fast-fail-dropped responses to live-but-backpressured reader nodes (cross-node resolve of 5MB now completes in <1s; was 30s-timeout).
- Membership: replaced the reconciler's NodeId-only terminal-eviction set (which permanently blocked a co-confirmed-dead id and contradicted SWIM's incarnation-fenced tombstone) with incarnation fencing — a strictly-higher SWIM incarnation now un-fences a returning node (same-id rejoin allowed, Akka-style new-incarnation-fences-old), aligning the reconciler with `SwimProtocol.supersedeOrRefuse`.
- **App-stream publish no longer fails 100% under accumulated stream count (off-heap budget exhaustion)** — once the per-node stream off-heap budget was exhausted by previously-created streams (each reserving its full retention up front), a new stream's `createStream` returned `STREAM_MEMORY_EXCEEDED` ("Total off-heap memory limit exceeded"), the management publish path (which only tolerated `STREAM_ALREADY_EXISTS`) failed the publish 100%, and the stream looked "not found". Fixed by right-sizing `system:cluster-events` (64→16 MB) and the floor-reserve + lazy-growth rework (#96) that drops per-stream create-time reservation ~8.8×. Validated: 04-streaming + 08-resources go from 100% publish failure to green.

### Security
- **Loud startup warning when `AETHER_INSECURE_DEV_MODE` is active** — the flag opens test-injection backdoors (DHT inject, scheduled-task inject, metrics backfill, short-validity certs) and is already refused at boot when operator TLS certificates are configured; it now also logs a prominent WARN banner at startup whenever it is active, so an auto-cert deployment that sets the flag is never silently insecure. The aether README gained an RC1 scope banner clarifying that security (management API-key auth + RBAC + inter-node TLS) is built in but OFF by default in this RC (default `SecurityMode.NONE`; enabling it by default is an RC2 hard gate), that dev-mode is a separate explicit opt-in, and that the RC targets a single trust domain.
- **Node refuses to start in dev-mode when operator TLS certificates are configured** — when `AETHER_INSECURE_DEV_MODE=true` is set together with operator-provided certificate/key (`TlsConfig.hasProvidedCertificates()`), the node now fails to start instead of silently running real TLS under insecure dev-mode. Dev-mode is incompatible with real operator certificates and is rejected at startup.
- **Insecure dev-mode is no longer carried into provisioned replacements via the generic env allow-list** — `AETHER_INSECURE_DEV_MODE` is propagated to replacement nodes only when it is present in the leader's own environment, threaded as an isolated value rather than via the shared cluster-identity allow-list, so a secure cluster cannot accidentally mint dev-mode replacements.
- **Missing/empty cluster name aborts node startup** — a node now refuses to start when no cluster name is resolvable (`AETHER_CLUSTER_NAME` env or bootstrap-seeded KV), preventing nodes from joining under an unintended identity; see the corresponding `### Changed` entry.

- `POST /api/nodes/promote/{id}` + `aether nodes promote <id> --role <CORE|WORKER>` CLI subcommand (P-NEW-E, 2026-05-21) — promotes a node to a new role at runtime by writing a fresh `ActivationDirectiveValue` keyed by node into the KV-Store via consensus. Downstream `ClusterDeploymentManager` consumes the `ActivationDirectivePutReceived` event and drives the role-aware machinery (`ForwardingClusterNode` / `SwitchableClusterNode`) to align runtime behavior. Request shape `{targetRole: "CORE"|"WORKER"}` (case-insensitive); response surfaces `{success, nodeId, previousRole, newRole, message}`. Route target is `LEADER` — the management plane forwards to the consensus writer automatically when the caller hits a follower. Promoting a node to the role it already carries is an idempotent no-op (no consensus write emitted). Unblocks TC-NEW-M11 (CORE↔WORKER role transition) in `aether/docs/internal/production-readiness-followup-2026-05-21.md`.
- `aether backup create` / `aether backup restore <commit>` / `aether backup list` CLI surface (P-NEW-C, 2026-05-21) — singular operator-facing alias of the existing `aether backups {trigger,list,restore}` plural form. Wraps the same REST routes (`POST /api/backups`, `POST /api/backups/restore`, `GET /api/backups`) — additional CLI surface only, no new server endpoints. `aether backup create --wait [--timeout N]` polls `GET /api/backups` until the new entry appears (default 60s). Unblocks TC-NEW-G4 (`suites/07-cluster-mgmt/test-backup-restore.sh`) in `aether/docs/internal/production-readiness-followup-2026-05-21.md`.
- `aether cluster init --non-interactive` flag (P-NEW-G, 2026-05-21) — forces batch mode and disables all interactive prompts. When set without `--target`, defaults to `--target docker`; missing required fields fail fast with a `MissingField` cause instead of dropping into the wizard. Existing batch flags (`--target`, `--name`, `--nodes`, `--hosts`, `--db-*`, `--firewall`, `--tls`, `--secret`) continue to drive config generation unchanged. Unblocks TC-07-J3 (cluster-init dry-run) in `aether/docs/internal/production-readiness-followup-2026-05-21.md`.
- `GET /api/scheduled-tasks/executions-by-node` + `aether scheduled-tasks executions-by-node <section> <artifact> <method>` CLI subcommand (P-NEW-H, 2026-05-21) — surfaces per-node execution attribution for a scheduled task. Returns `{section, artifact, method, executions:[{nodeId, count, lastExecutionMs}]}`. RC1 implementation reports the task's `registeredBy` node as the sole executor (count = `ScheduledTaskStateValue.totalExecutions`, lastExecutionMs = `lastExecutionAt`); a follow-up issue tracks adding per-node execution counters to the KV state so this endpoint can produce true per-node breakdowns for ALL-mode tasks. Operator-facing — no dev-mode gate. Unblocks TC-08-F3 (SINGLE vs ALL mode scheduled-task tests).
- `POST /api/certificates/configure-short-validity` (P-NEW-I, 2026-05-21; dev-mode only) — reconfigures the `CertificateRenewalScheduler` so the active certificate appears to expire in `validitySeconds` from now; the renewal timer reschedules at the recomputed 40%-of-remaining mark (24s for `validitySeconds=60`). Enables `Strengthen-cert-rotation-trigger` integration tests to observe automatic cert rotation in seconds rather than waiting hours. Request shape `{validitySeconds: N}` (range 1..86400); response surfaces `{status, validitySeconds, newExpiresAt, secondsUntilExpiry}`. Same dev-mode gate (`AETHER_INSECURE_DEV_MODE=true`) and rejection pattern as the other inject endpoints. Local-only route (no leader forwarding). New `CertificateRenewalScheduler.configureShortValidity(int)` public method on the scheduler exposes the hook to the route handler.
- `POST /api/dht/inject` (dev-mode only) — writes a value into the local DHT storage tier with an operator-supplied HLC timestamp, bypassing the regular `DHTClient.put` path that always advances the node's clock to `now()`. Enables TC-10-G2 (DHT versioned writes) to build deterministic version-conflict scenarios without racing the live clock. Request shape `{key, value, hlc:{physical, logical}}`; response surfaces `committedHlc` (may be advanced relative to the request when the local clock had already moved past the supplied timestamp — HLC merge rule) and `written` (`true` when storage accepted the write as newest, `false` when a stale-version write was suppressed). Same dev-mode gate (`AETHER_INSECURE_DEV_MODE=true`) and rejection-message pattern as `/api/alerts/inject` / `/api/scheduled-tasks/inject` / `/api/metrics/backfill`. Route target is `LOCAL` — tests POST directly to the node they wish to mutate (no leader forwarding). Two new `Option<DHTClient>` / `Option<DHTNode>` accessors on `ManageableNode` thread the DHT surface to the route handler. See `aether/docs/internal/production-readiness-followup-2026-05-21.md` P-NEW-B.
- `GET /api/dht/replication-map` + `aether dht replication-map` CLI subcommand — operator-facing inspection of the active DHT replication topology (which keys live on which nodes under the current replication factor). Walks the local DHT storage tier's keys, computes `ConsistentHashRing.nodesFor(key, replicationFactor)` per key, returns `{replicationFactor, totalKeys, returned, entries:[{key, nodes:[...]}]}` where `nodes[0]` is the primary and subsequent entries are replicas walking the ring clockwise. Optional `?limit=N` (default 100, capped at 10000) and `?prefix=...` (UTF-8 key prefix filter) query parameters. Operator-facing — no dev-mode gate, standard auth. New `DhtCommand` parent class in `AetherCli` with `ReplicationMapCommand` subcommand wrapping the route. See `aether/docs/internal/production-readiness-followup-2026-05-21.md` P-NEW-F.

### Fixed
- **Seed-node lifecycle write (`ClusterDeploymentState.handleNodeAdded`)** — seed nodes listed in `ctx.seedNodes()` previously skipped the `assignNodeRole` path entirely, so no `ActivationDirective` was submitted and the `MembershipFsm` never wrote their `NodeLifecycleKey`. The initial leader (typically `node-1`) was therefore missing from `/api/nodes/lifecycle`, `/api/cluster/generation` `core.members[]`, and KV-Store after fresh cluster bootstrap, even though it appeared in `/api/cluster/topology` `coreNodes[]` (computed from consensus topology, not KV). Fix: new `ensureSeedNodeLifecycleEntry(NodeId)` helper plants `NodeLifecycleValue(JOINING)` for the seed node iff no entry already exists (idempotent — existing `ON_DUTY` / `DRAINING` / `DECOMMISSIONED` states are preserved). The standard `MembershipFsm` machinery then drives `JOINING → ON_DUTY`. Runs only on the leader (the helper lives inside `Active`, the leader-scoped state). The `N-1` floor relaxations introduced in `ef5013881` are reverted in the same commit: `_cluster_is_ready` Property 1 returns to strict `== NODE_COUNT`, and the three relaxed smoke-test assertions in `00-smoke/test-cluster-formation.sh` (`test_nodes_formed`, `test_quorum_established`, `test_all_nodes_visible`) return to strict equality. Spec `aether/docs/specs/test-readiness-contract.md` §6 entry marked RESOLVED. Regression test: `ClusterDeploymentStateSeedNodeLifecycleTest` (3 cases — plant-when-absent, idempotent-when-present, non-seed-no-op).

### Changed
- **`RateLimiter`: lock-free CAS token bucket + continuous refill (RC1)** — replaces the prior `synchronized` + mutable `long[] state` token bucket with a lock-free implementation backed by a single packed `AtomicLong` (16 bits tokens, 48 bits `lastRefillNanos` rebased mod-2^48 ≈ 3.26 days; active limiters unaffected, a limiter idle longer self-corrects on next call). Implementation is a local record inside the builder's terminal `timeSource(...)` method — invisible to consumers. Removes monitor serialization on the hot path — critical for the upcoming node-input rate-limiting use case where every inbound request crosses the limiter on a node already contended by IO, slice dispatch, consensus, and gossip. New primitive API: `boolean tryAcquire()` + `TimeSpan retryAfter()`; `execute(Supplier<Promise<T>>)` retained as a default-method wrapper but now zero-allocation on the admitted path (the operation's own `Promise` IS the result — the previous `.async()` / `Promise.resolved(...)` / `.flatMap(operation)` chain is gone). Refill semantics changed from **leaky (whole-period chunks)** to **continuous (one token per `period/rate` ns)**: at rate=5/sec, a token returns at 200ms instead of waiting the full second; eliminates the 2× burst at period boundaries that the leaky version permitted. Caller adaptations: `DefaultRateGuard.guard(...)` (in `aether/resource/interceptors`) now uses `tryAcquire()` + `retryAfter()` directly, dropping the `RateLimiterError → RateGuardError` `mapError` chain — one allocation on rejection instead of two; `NettySwimTransport.isAnnounceAllowed(...)` switched from `entry.limiter().execute(Promise::unitPromise).await().isSuccess()` to `entry.limiter().tryAcquire()`, removing a `Promise` allocation + `.await()` round-trip per SWIM announce admission check. Concurrent `RateLimiterTest` migrated from wall-clock timing (CI-flaky) to a `TestTimeSource`-driven deterministic 256-vs-100 contention assertion; added explicit tests for continuous-refill granularity, quantum-bounded retry-after, and idle-window cap (no unbounded token accrual).

### Added
- `aether streams create <name> [--partitions N]` / `aether streams delete <name> [--force]` / `aether streams consumer-group {join,leave,status} <group> <stream>` CLI wrappers around the existing `POST /api/streams`, `DELETE /api/streams/{name}`, and `/api/streams/groups/{join,leave,{id}}` REST routes — closes Phase 2 P7 in `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §3.3 A; turns the stream lifecycle into a fully CLI-driven workflow.
- `aether cluster export --format json` now emits the management-API JSON envelope (`tomlContent`, `clusterName`, `configVersion`, `coreCount`) verbatim instead of silently falling back to the raw TOML body; `aether artifacts versions <g:a> --format json` projects the Maven `maven-metadata.xml` payload down to `{"groupId","artifactId","versions":[...]}`. Default (TABLE) format preserves the legacy TOML / XML output unchanged. Closes Phase 3 C7/C8 in `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §3.3 I.
- `aether artifacts get <g:a:v> [--out=<file>] [--file=<filename>]` CLI command — wraps the existing `GET /repository/{g}/{a}/{v}/{file}` byte-stream endpoint. Default filename is `<artifactId>-<version>.jar`; `--out` writes to a file, otherwise bytes stream to stdout. Closes Phase 3 C5 in `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §3.3 G — `09-artifacts/test_resolve_artifact` can now drop the raw `curl -s -o ... "${CLUSTER_ENDPOINT}${ARTIFACT_PATH}"` form for `aether artifacts get`.
- `aether blueprints publish <g:a:v>` CLI command — wraps the `POST /api/blueprints/publish` route (`BLUEPRINT_PUBLISH_ARTIFACT`) that was already wired server-side but had no CLI surface. Mirrors the `blueprints deploy` body shape (`{"artifact":"g:a:v:blueprint"}`); the `:blueprint` qualifier is appended automatically. Closes Phase 3 C6 in `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §3.3 H — turns the previously dead enum entry into an operator-callable command orthogonal to `blueprints apply <file>` (raw content body).
- `aether slices status` / `aether slices topology` / `aether cluster governors` / `aether ttm status` / `aether ttm training-data` CLI wrappers around the matching `/api/slices/status`, `/api/slices/topology`, `/api/cluster/governors`, `/api/ttm/status`, `/api/ttm/training-data` REST routes (closes integration-test-audit-2026-05-21 §3.3 B/C/D/E — Phase 3 items C2/C3/C4). New `aether/cli/src/main/java/org/pragmatica/aether/cli/ttm/` package mirrors the `cluster/` and `storage/` sibling-class layout (parent `TtmCommand` + per-subcommand classes consuming `ClusterHttpClient.fetch`). `SlicesCommand` in `AetherCli` gains nested `StatusCommand` / `TopologyCommand` subclasses while retaining the existing `aether slices [--state]` LIST behavior on the parent. `ClusterGovernorsCommand` joins the existing `cluster/` package sibling layout. Picocli wiring regression test `CliRouteWrapperTest` pins subcommand resolution for all five paths.
### Added
- `GET /api/metrics/timeouts` + `aether metrics timeouts` CLI subcommand — per-subsystem timeout-fired counters (one entry per `TimeoutsConfig` subsystem; 14 subsystems mirroring `[timeouts.*]` TOML sections). New `TimeoutMetricsRegistry` (LongAdder-backed, per-subsystem) wired into `MetricsRoutes`; counters start at 0 and remain so until the 14 subsystem timeout-fire sites are instrumented with `recordTimeout(...)` calls in a follow-up. Endpoint shape + registry semantics + CLI wiring are RC1; per-site instrumentation hooks are a tracked follow-up. Unblocks observability gap for TC-07-G3 (`[timeouts.*]` taking effect). See `aether/docs/internal/production-readiness-followup-2026-05-21.md` P-NEW-A.
- `POST /api/metrics/backfill` (dev-mode only) — seeds synthetic historical-metric samples into the local node's `ClusterSyncCollector` ring buffer via a new test-only `injectHistoricalSnapshot(NodeId, MetricsSnapshot)` interface hook on `ClusterSyncCollector`. Accepts `{metric, startTimeMs, endTimeMs, intervalMs, valueFn}` where `valueFn` is one of `constant:<double>` / `linear` / `sine` (unknown falls back to `constant:0.0`). Same dev-mode gate (`AETHER_INSECURE_DEV_MODE=true`) and rejection-message pattern as `/api/scheduled-tasks/inject` and `/api/alerts/inject`. Unblocks TC-11-H1 (historical-metrics 5m/15m/1h/2h range queries) — backfill makes the range assertions deterministic instead of requiring hours of organic accumulation. See `aether/docs/internal/production-readiness-followup-2026-05-21.md` P-NEW-D.
- `aether metrics {prometheus,transport,comprehensive,derived,history}` CLI variants wrapping the matching `/api/metrics/*` REST routes (history supports `--range`/`--since`; unblocks integration-test-audit-2026-05-21 §3.3 B / Phase 3 item C1)
- `POST /api/scheduled-tasks/inject` (dev-mode only) + CLI for deterministic test triggering (unblocks 08-resources scheduled-task assertions)
- `aether streams read <name> <partition>` CLI command (wraps existing REST `GET /api/streams/read/{name}/{partition}`; supports `--since <offset>` → `?from=` and `--limit <N>` → `?max=`; unblocks 04-streaming publish→read invariant test, RC1-blocker #1 in `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §2.2)
- `tlsEnabled` boolean on the `/api/certificates` response — replaces the `test-cert-rotation.sh::test_tls_active` tautology (RC1-blocker #3 in `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §2.2) with a directly assertable field reflecting the node's runtime TLS posture (`AetherNodeConfig.tls().isPresent()`). Surfaced on `ManageableNode.tlsEnabled()`; `ClusterConfigRoutes` also threads the flag through when assembling `ClusterStatusResponse.certificateStatus`. CLI (`aether certs status`) emits the new field unchanged.
- Principal introspection via /api/whoami and aether whoami (unblocks 05-security identity assertions)
- **`SELF_DRAIN_INITIATED` cluster event (T3.1)** — new `EventType` surfaced by the draining node itself when its `SelfDrainCoordinator` flips from `ACTIVE` to `DRAINING` (membership-architecture-spec.md §16.1, S19/S20). Severity `WARNING`; `details` carries `nodeId`, `reason` (one of `sustained-below-quorum`, `quorum-disappeared`, `rabia-paused`), and `graceMs`. Wired via a new narrow `SelfDrainEventPublisher` functional interface in the deployment module (kept loose-coupled because `aether-deployment` does NOT depend on `aether-node`, where `ClusterEventLogPublisher` lives — production adapter in `AetherNode` uses a forward-declared `AtomicReference<ClusterEventLogPublisher>` resolved lazily inside the lambda). Intentionally NOT leader-gated: the draining node is the only authoritative source for "I'm self-draining" — a partition victim may not even be able to reach the leader. Publish is best-effort (the node halts immediately after) and exception-safe (a throwing publisher logs at WARN but never interrupts the drain). Eliminates the `docker logs | grep 'Self-drain: DRAINING on'` workaround in `02-chaos/test-self-drain-quorum-loss.sh` — the test now consumes the event from `/api/events` via the unioned-multi-node `topology_events_since` helper. Resolves the T3.1 §6 Future-Work item in `test-readiness-contract.md`.
- **`aether nodes lifecycle --state <STATE>` + `?state=` query parameter on `/api/nodes/lifecycle` (RC1)** — mirrors the just-landed `aether slices --state` multi-state pattern (single state or `+`-separated union, case-insensitive, uppercase-normalised server-side). The CLI option is ignored when `[id]` is supplied (single-node lookup uses the unfiltered per-id route). Server-side parser shared with `SliceRoutes` via a new `RouteFilters.parseStateFilter` helper — two callsites have crossed the inline-duplication threshold. Integration test helper `pick_non_leader` in `aether/tests/integration/lib/cluster.sh` migrated from `/api/nodes/status` ON_DUTY grep to `aether nodes lifecycle --state ON_DUTY --format json`; the helper's existing leader re-derive (race-fix for MGMT_ENTRY_POINT round-robin) and Docker-mode liveness skip are preserved unchanged. Docs: `aether/docs/reference/management-api.md` documents the new query parameter; `aether/docs/reference/cli.md` documents `--state` with single and multi-state examples.
- **`aether slices --state <STATE>` supports `+`-separated multi-state union (RC1)** — operators can now pass `--state LOADED+ACTIVE` (or any `+`-joined combination) and the server filters instances to those whose `state` is a member of the resulting set. Single-state queries continue to work unchanged. Server splits on `+`, trims, uppercases, drops empties; empty filter (`+` alone) matches no instance. Integration test helper `slices_total_instances` migrated from raw-JSON `(LOADED\|ACTIVE)` grep to `aether slices --state LOADED+ACTIVE`, eliminating the last LOADED+ACTIVE union grep on the test side.
- **`aether nodes health [id] [--liveness]` + per-node `/health/ready/{id}` & `/health/live/{id}` forwarded variants (RC1)** — completes the per-node introspection set under `aether nodes`. Mirrors the Phase B forwarding pattern (`NODE_STATUS_GET` / `NODE_INFLIGHT_GET` / `NODE_SLICES_GET` / `NODE_ROUTES_GET` / `NODE_METRICS_GET`): two new `ManagementRoute` enum entries `HEALTH_READY_GET` and `HEALTH_LIVE_GET` use `RouteTarget.nodeIdParam(0)` so the management plane forwards by node ID without the handler needing to know about routing. Handlers are reused from the existing local-only `HEALTH_READY` / `HEALTH_LIVE` (the path parameter is discarded at handler time because forwarding has already occurred). CLI: new `HealthCommand` inner class on `NodesCommand` — defaults to `/health/ready` on the connected node, `[id]` arg selects another node, `--liveness` flag switches to `/health/live`. The legacy top-level `aether health` (cluster-aggregated `/api/health`) is unchanged. Docs: `aether/docs/reference/management-api.md` documents both `/{id}` variants alongside the existing endpoints + endpoint-summary table; `aether/docs/reference/cli.md` adds the `nodes health` reference section. Route invariant: the round-trip `RouteAssemblerTest.roundTrip_assembleThenMatch_preservesParams` iterates `ManagementRoute.values()` and automatically covers the new entries.
- **`GET /api/slices?state=<STATE>` + `aether slices --state <STATE>` server-side instance-state filter (RC1)** — operators (and integration test helpers) can filter slice instances by state without grepping raw JSON. The query parameter is case-insensitive (uppercase-normalised server-side); the filter is instance-level — each slice's `instances[]` is restricted to entries whose `state` matches, and slices with no matching instances are dropped from the response. Wired via `QueryParameter.aString("state")` on `SLICES_LIST` and a `withQuery(...).toValue(buildClusterSlicesResponse)` chain in `SliceRoutes`; CLI gains an `@Option(--state)` on `SlicesCommand` that builds a `state=<value>` query string via the same pattern as `EventsCommand`. Integration test helper `slices_active_instances` in `aether/tests/integration/lib/cluster.sh` migrated to `aether slices --state ACTIVE` — the helper now consumes the authoritative server-side contract instead of grepping the unfiltered list. Docs: `aether/docs/reference/management-api.md#get-apislices` documents the new query parameter; `aether/docs/reference/cli.md#slices` documents `--state` with a filtered example. `slices_total_instances` continues to use the unfiltered response since it counts the union of LOADED+ACTIVE (the single-state filter cannot express set-union in one call; out-of-scope client-side aggregation kept).
- **CLI `aether cluster tasks list` + `aether cluster tasks status <group>` subcommands** — `list` makes the existing default-behavior explicit (the bare `aether cluster tasks` invocation still works for backward compat); `status <group>` fetches the full assignments JSON from `/api/cluster/tasks` and filters client-side to the single matching group's record, re-wrapping it as `{"assignments":[<record>]}` so the existing `TASKS_TABLE` rendering and `--format value --field assignments.0.<col>` extraction keep working unchanged. Group lookup is case-insensitive (input upper-cased to match the server enum names). Hand-rolled brace-depth extractor (no Jackson dependency added to the CLI for this) mirrors the `parsePushStatus` precedent. Missing group returns `Error: task group '<input>' not found` on stderr with exit code ERROR. Integration test helper `task_group_status` in `aether/tests/integration/lib/cluster.sh` migrated from raw-JSON grep to `aether cluster tasks status <group> --format value --field assignments.0.status` — single source of truth via the CLI surface.

- **Topology-observation refactor: aggregator-quorum gate + self-drain (RC1)** — closes the long-running cluster-B integration-test flakes (`pick_non_leader` finding stale ON_DUTY for killed nodes within the SWIM detection window, ~10-15s). Two-layer fix: (1) faster event source — QUIC transport disconnect now feeds `TransportReachable`/`TransportUnreachable` events into `MembershipFsm` via the leader-gated `onTransportSnapshot`, sub-second observable vs SWIM's 10-15s floor; (2) reliable aggregator — `ClusterSyncScheduler` emits periodic `PeerConnectivityObservation` every 5s for every connected peer (TTL=15s, buffer cap `peers × 4`) replacing transition-only emissions, giving the aggregator constant fresh evidence in steady state. New `ReachabilityGate` functional interface aggregator-gates the `(OnDuty, SwimFaulty)` and `(OnDuty, TransportUnreachable)` reducer cells: decommission requires aggregator quorum-confirmed UNREACHABLE (⌈N/2⌉+1 observers all UNREACHABLE, 0 REACHABLE) — partition-safe because in a 2-vs-3 split for N=5, the 3 majority observers cannot reach the threshold of 4, so the gate structurally blocks premature decommission writes. Cold-start fallback (no snapshot yet → SWIM cell allowed) preserves the legacy behaviour during the ~1-2 tick warmup. New `SelfDrainCoordinator` lives at `aether/aether-deployment/.../drain/`: a node monitors its own quorum visibility and on sustained loss (8s threshold, `(N/2)+1` arithmetic) atomically CAS-transitions ACTIVE → DRAINING (closing `InFlightRequestTracker`'s `setAcceptingNewWork(false)` gate) → after 30s grace → `Runtime.halt(2)`. Uninterruptible once started — re-triggers during DRAINING are no-ops. ZERO consensus/KV imports (asserted in a static-import test): the partition-victim path does not require the cluster to agree on its fate. Triggers: `QuorumStateNotification.DISAPPEARED`, Rabia `Paused`, and 1Hz periodic connectivity check. `MembershipView.resolveOnDutyStatus` simplified — UNKNOWN now downgrades to UNTRACKED explicitly (no longer ambiguous after periodic emissions eliminate the steady-state UNKNOWN). Spec §16 in `aether/docs/specs/membership-architecture-spec.md` captures the 20-scenario acceptance contract (S01..S20) the FSM + aggregator must satisfy. Three new integration tests pin the contract: `02-chaos/test-joining-window-kill.sh` (S01 — JOINING-window kill produces DECOMMISSIONED within 15s via transport-failure, smoking-gun `reason=transport-failure` log assertion); `12-network/test-partition-quorum-gate.sh` (S05+S06 — 2-vs-3 partition for 5s does NOT decommission minority, gate blocks; heal returns 5 ON_DUTY within 30s); `02-chaos/test-self-drain-quorum-loss.sh` (S19+S20 — kill 3 of 5 → 2 survivors self-drain with exit code 2 → restart → 5 ON_DUTY within 60s). Preserves all sensitive invariants: `(Decommissioned, *) → nop` chaos-revival defense, incarnation gate, I1 (only mutate fsmStates after consensus apply), single-writer rule on `NodeLifecycleKey`. Full plan + status in `aether/docs/internal/progress/session-handover-2026-05-19.md`

- **Reachability aggregator: cluster-canonical transport view via metrics ping-pong (RC1)** — closes the per-reader-variance window that caused `02-chaos/Kill_2_nodes` to see only 1 of 2 non-leader candidates (and the cascade through 03-scaling / 05-security / 13-edge-cases). Root cause: `MembershipView.mapKvState` downgraded `kvState == ON_DUTY` to `UNTRACKED` whenever the entry-point's local SWIM hadn't yet probe-acked HEALTHY, even though KV agreed cluster-wide. New architecture: a leader-side `ReachabilityAggregator` ingests `PeerConnectivityObservation` (QUIC) + `PeerHealthObservation` (SWIM) from incoming `ClusterSyncPong`s, applies TTL + ⌈N/2⌉+1 quorum, and broadcasts an `AggregatedReachabilitySnapshot` in every outbound `ClusterSyncPing`. `MembershipView.strict` accepts the snapshot as a SECOND confirmation source for KV-ON_DUTY peers — when local SWIM hasn't acked HEALTHY but a cluster-wide quorum of observers sees the peer as REACHABLE, the view returns `ON_DUTY`. Snapshot is consulted ONLY for the ON_DUTY case; non-ON_DUTY lifecycle states (JOINING / DRAINING / etc.) are unaffected. Snapshot UNREACHABLE preserves the existing transport-honest downgrade. Cold-start (no snapshot yet): legacy strict behaviour preserved (~1-2 tick window, bounded). Three new types in `integrations/cluster/.../metrics/`: `AggregatedReachabilitySnapshot` (top-level + `ReachabilityState` record + `ReachabilityKind` enum {REACHABLE / UNREACHABLE / UNKNOWN}). Wire format extends `ClusterSyncPing.aggregatedReachability: Option<AggregatedReachabilitySnapshot>` (defensive null-handling, backward compatible). `CommunityReport.communityReachability: Option<AggregatedReachabilitySnapshot>` extends Tier-2 spokesman → cluster-leader propagation. Producer side: extended `PeerConnectivityReporter` with symmetric `onPeerConnected` (was DISCONNECTED-only) so the QUIC stack feeds both directions of transport transitions through the buffer; `QuicClusterNetwork.processViewChange` ADD/RECONNECT cases now route through new `reportPeerConnection`. Consumer side: `ClusterSyncCollector.lastReachabilitySnapshot()` exposes the most-recent received snapshot; `AetherNode` wires leader-OR-spokesman-gated ingest into pong listener, with on-leader-gain reset + seed-from-cache to shorten warmup (~1-2 ticks). Single-writer rule preserved: the aggregator does NOT write KV; `HealthReconciler` remains the sole `NodeLifecycleKey` writer. Tier-2: `SpokesmanPingLoop` carries the same snapshot in outbound governor pings (so governors cache it for warm-takeover) and attaches it to every `CommunityReport` sent up to the cluster leader. New `ReachabilityAggregator` class lives in `aether/aether-deployment/.../membership/`; 11 unit tests cover quorum threshold, TTL eviction, self-fold from `connectedPeers`, latest-wins per (target, observer) pair, seed-from-cache, reset, and self-targeting filter. Full architecture in [`aether/docs/specs/reachability-aggregator-spec.md`](aether/docs/specs/reachability-aggregator-spec.md)
- **ArtifactStore: bounded retry on transient DHT backpressure** — the DHT-resilience layer (which shipped in the previous wave) converts a transient QUIC `BackpressureRefused` from a metadata-key replica into a synchronous `QuorumCollector.onFailure`. This is correct for Rabia (built-in retransmit) but wrong for `ArtifactStore.deploy`, a one-shot user-facing operation with no retry cycle of its own — one backpressured replica = HTTP 500 surfaced from a 1MB push. `dhtPutWithRetry` (3 attempts with 100/250/500ms backoff) wraps the two DHT writes inside `storeMetadataAndVersions` (metadata write + versions-list write). Selective retry: only `DHTError.PeerUnreachable` and `DHTError.QuorumNotReached` are retried — `NO_AVAILABLE_NODES` and other causes propagate immediately so genuine failures aren't masked. The 30s outer `DEPLOY_TIMEOUT` is the safety net. Closes 09-artifacts 1MB/5MB push HTTP 500 surfaced after the DHT-resilience layer landed
- **Integration test fixes: #3 disable_auto_heal + #4 EchoSlice probe + #6 BootstrapModuleTest stale assertion**
  - `lib/cluster.sh` `disable_auto_heal` / `enable_auto_heal` / `auto_heal_enabled` rewritten: CLI-based (canonical management surface; consistent with the strict "prefer aether CLI over curl" rule) + idempotency short-circuit + verify-after — re-reads the state after the toggle and fails if the post-state isn't the expected one (defence-in-depth: CLI may exit 0 while a transient leader change leaves state unchanged)
  - `00-smoke/test-slice-deployment.sh` + `13-edge-cases/test-stale-route-cleanup.sh` probe `${APP_ENDPOINT}/api/echo/health` instead of bare `${APP_ENDPOINT}/health`. The bare path hit `AppHttpServer`'s synthetic liveness intercept (returns 200 unconditionally — designed for LB health checks) which made the probe pass regardless of slice deployment. The prefixed path goes through the route table and actually verifies the EchoSlice handler is wired
  - `BootstrapModuleTest.initialCoreSizeAtQuorum_seedDeferred` was stale after commit `62ae7b19f` ("drop seed grace period") removed the lifecycle-count + grace-window gate from `BootstrapModule.planClusterConfigSeed`. The test asserted the seed was deferred when `lifecycleCount < initialSize`; the new behaviour seeds immediately when `initialSize >= 3`. Replaced with `initialCoreSizeAtQuorum_seedEmitted` covering the post-grace contract
- **CLI option-collision fixes** — two pre-existing picocli `DuplicateOptionAnnotationsException` failures at CLI startup: (a) `TracesCommand.InjectCommand` claimed `-o` for `--operation`, which collided with the `-o` short flag on `OutputOptions.format` mixed in via `--format` long; the conflicting short form removed (kept `--operation` long form). (b) `ClusterScaffoldCommand` claimed `--format` directly, colliding with the same `OutputOptions.format`; renamed to `--template` (semantically clearer — the flag selects which deployment-manifest template to emit). Docs (`cluster-label-scoping-spec.md` + `operator/multi-cluster-deployment.md`) updated to reference the new flag name. Without these fixes the CLI failed at every invocation with a stack trace — the older installed CLI (built before the conflicting options landed) masked the breakage until a fresh rebuild

### Added
- **DHT resilience: layered architecture for chaos-safe writes (RC1)** — full architectural fix for the long-standing 1MB-push hang (49+ minutes observed) AND the 08-resources slice-deploy slowdown (4s → 196s under transport degradation). Replaces the prior wait-and-mask pipeline (every layer waits its full timeout before learning of failure) with a signal-and-route-around pipeline (failures propagate synchronously, DHT routes around unreachable replicas). Three layers shipped together: **Layer 1** — `WriteOutcome` sealed interface (`Sent` / `BackpressureRefused` / `ConnectionDead` / `NoPeerState`) at `integrations/consensus/.../WriteOutcome.java`; `QuicClusterNetwork.writeIfWritable` returns it, propagated through `writeToStream` → `dispatchSerialized` → new `dispatchPayloadWithOutcome`; new `ClusterNetwork.sendOutcome(NodeId, M) → Promise<WriteOutcome>` API (additive — existing `send` unchanged, only DHT consumes the new surface). **Layer 2** — `DistributedDHTClient.targetNodes` filters static consistent-hash owners by `network.livePeers()` so quorum is computed against currently-reachable replicas; the ring continues to describe ownership, runtime reachability determines actual targets; `AetherNode` adapter exposes `connectedPeers ∪ {self}` as the live set. **Layer 3** — DHT `sendRemote{Get,Put,Remove,Exists}` route through `dispatchTracked` which calls `network.sendOutcome` and on any non-`Sent` outcome immediately removes the pending op and invokes `collector.onFailure(DHTError.peerUnreachable(peerId, reason))`. The `QuorumCollector`'s existing fast-fail logic (`failures > total - quorum` → `promise.fail`) then short-circuits before the per-op 10s timeout. New `DHTError.PeerUnreachable(NodeId, String reason)` cause. Net integration impact: 02-chaos 1713s → 110s (~15× faster), 03-scaling 795s → 9s, 05-security 530s → 11s, 12-network 793s → 65s, 13-edge-cases 795s → 89s; 09-artifacts no longer hangs (49 min → 38s bounded fail with HTTP 500 surfaced from server). Full spec at [`aether/docs/specs/dht-resilience-spec.md`](aether/docs/specs/dht-resilience-spec.md)

### Added
- **First-boot Docker-label consistency check** — new `ContainerLabelInspector` reads the running container's `aether.cluster` label via the Docker daemon's Unix-domain socket (`/var/run/docker.sock`, queried with JDK-native `UnixDomainSocketAddress` + raw HTTP/1.1 — no third-party HTTP-over-Unix-socket dependency). At node startup, `Main.verifyClusterLabelConsistency` compares the label against `AETHER_CLUSTER_NAME` env; on mismatch the node fails-closed via `System.exit(1)` with an explicit error message naming both sides. Detection is conservative: no `/.dockerenv` marker, no socket mount, or any I/O failure → skip silently with DEBUG-logged reason. Both sides empty → skip (no information to disagree on). 9 unit tests cover parseLabels, compareWithConfigured (match / mismatch / empty-configured / empty-label), and inspectSelfLabels-when-not-in-container. Closes the operator footgun where editing `AETHER_CLUSTER_NAME` without updating the compose/k8s label (or vice versa) would silently send the node to the wrong cluster
- **`aether cluster scaffold` — correct-by-construction deployment-manifest templates** — new CLI subcommand that emits a ready-to-use deployment manifest with both `aether.cluster=<name>` and `aether.node-id=node-N` labels pre-set on every service. Removes the "forgot to set the cluster label" class of misconfiguration that otherwise leaves CTM container reaping and cross-cluster tooling unable to distinguish two clusters sharing infrastructure. Currently emits docker-compose; kubernetes / hetzner-terraform tracked as follow-ups in [`aether/docs/specs/cluster-label-scoping-spec.md`](aether/docs/specs/cluster-label-scoping-spec.md). Usage: `aether cluster scaffold --name us-prod --format docker-compose --nodes 5 > compose.yml`. Validation: `--name` regex `^[a-z][a-z0-9-]{0,62}$` via existing `ClusterIdentity.NAME_PATTERN`; `--nodes >= 3`. Implementation: `ClusterScaffoldCommand` + `DockerComposeTemplate` (string-builder template emitting common env + per-node services + cluster-scoped bridge network). 6 unit tests in `DockerComposeTemplateTest` cover label per-node coverage, peer-list format, port-base honouring, restart-no contract surfacing
- **Operator doc + RFC-0015 for cluster-label scoping** — `aether/docs/operator/multi-cluster-deployment.md` is the operator playbook for running multiple Aether clusters on shared infrastructure (Docker host, HCloud project, K8s cluster). Documents the orthogonal-labels model (`aether.cluster` for deployment scope, `aether.node-id` for in-cluster identity), why NodeId is explicitly NOT cluster-scoped (KV/consensus payload bloat + topology leakage into internal data structures), and label-setting recipes per deployment target. `docs/rfc/RFC-0015-cluster-label-scoping.md` is the architecture record — alternatives considered (cluster-prefixed NodeId, docker-network-name, hostname-derived identity) and implementation status snapshot. `aether/docs/reference/cli.md` adds the `aether cluster scaffold` reference section

### Changed
- **Integration test helpers: canonical `wait_for_cluster_ready` + node-count rename (RC1)** — codified by [`aether/docs/specs/test-readiness-contract.md`](aether/docs/specs/test-readiness-contract.md). Three diverging "cluster is ready" helpers (`is_cluster_ready` snapshot predicate, `wait_for_cluster` polling wrapper, `wait_for_all_nodes_ready` per-node `/health/ready` loop) folded into one canonical helper `wait_for_cluster_ready [timeout]` whose composite predicate `_cluster_is_ready` enforces ALL four properties of the spec §1.1 simultaneously: generation members ≥ NODE_COUNT, leader elected (non-`none`), active cores ≥ N-1, every node port `/health/ready` body `"status":"UP"`. Old names kept as `@deprecated alias` shims that delegate to the new helper (callable but flagged for RC2 removal). Node-count helpers renamed: `cluster_node_count` → `cluster_member_count` (generation snapshot, includes JOINING — §2.1) and `cluster_node_count_on_duty_healthy` → `cluster_active_core_count` (topology snapshot, ON_DUTY+reachable — §2.2). All call-sites across `aether/tests/integration/lib/*.sh` + `suites/**/*.sh` migrated; grep verifications return 0 hits for both legacy names.
- **CLI: `aether topology` → `aether cluster topology` namespace move (RC1)** — the top-level `aether topology` command is removed and folded under the existing `aether cluster` parent. New shape: `aether cluster topology` shows topology (existing default behaviour), `aether cluster topology circuit-breaker {status,reset}` and `aether cluster topology auto-heal {status,enable,disable}` carry over unchanged. Hard cut, no aliases — fits the post-RC1 namespace consolidation already applied to `aether nodes …` per-node subcommands. The bare topology table is unaffected: still queries `CLUSTER_TOPOLOGY` and renders via the existing `TOPOLOGY_TABLE` spec. `ClusterTopologyCommand` now lives in `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/`, matching the sibling `ClusterTasksCommand` pattern (top-level class file under `cluster/`, `@ParentCommand ClusterCommand`, `ClusterTargetMixin` overrides, `ClusterHttpClient.fetch/post` instead of the deleted `AetherCli.fetch/post` path). Docs (`aether/docs/reference/cli.md`) updated to the new command paths; integration helper `aether/tests/integration/suites/13-edge-cases/test-disruption-budget.sh` EXIT-trap log message updated.
- **`PUT /repository/{groupPath}/{artifactId}/{version}/{file}` is now idempotent (RC1)** — replaces the prior "201 on first push, ambiguous behaviour on duplicate" contract that forced the integration-test helper `lib/cluster.sh::push_blueprint` to defensively grep stderr for `already exists | 409 | conflict | duplicate artifact`. Both a fresh upload and a duplicate upload now return **HTTP 200 OK** with a structured JSON body whose `status` field is either `"uploaded"` (fresh) or `"already-present"` (duplicate); the body also carries `coords`, `size`, `md5`, `sha1`. For duplicates the size/hashes are read from the persisted KV metadata (single DHT round-trip via a new `ArtifactStore.metadata(Artifact) → Promise<Option<ArtifactMetadata>>` accessor) — we deliberately do NOT re-read the chunks or re-verify SHA1, that's the GET path's job. New `ArtifactPushResponse` record in `ManagementApiResponses` documents the wire shape; the JSON is rendered inline in `MavenProtocolHandlerImpl` to keep the artifact-repo module free of Jackson. `MavenProtocolRoutes` now honours the response's `contentType` field (JSON / XML / TEXT / BINARY) when wiring the HTTP response, instead of hard-coding BINARY. CLI: `aether artifacts push --format json` emits a single aggregate object `{"status":"uploaded|already-present|mixed", "blueprint":"...", "artifacts":[{"coords":"...","status":"..."}]}` — a single `jq '.status'` lookup tells the caller the outcome. CLI table/default output prefixes duplicate slices with `(already present)` next to the size hint. `push_blueprint` now relies on the JSON status field instead of stderr-grepping — the legacy `already exists|409|conflict|duplicate artifact` regex is gone from `cluster.sh`. Failure path unchanged (4xx/5xx + `application/problem+json`).
- **CLI: per-node introspection subcommands folded under `aether nodes` namespace** — the kebab top-level commands `aether node-slices`, `aether node-routes`, `aether node-inflight`, `aether node-metrics` are removed; the per-node forms now live as subcommands under the existing `aether nodes` parent: `aether nodes slices [id]`, `aether nodes routes [id]`, `aether nodes inflight [id]`, `aether nodes metrics [id]`. Also resolves a latent picocli class clash: two top-level classes (`NodesCommand` and `NodeCommand`) were both registered with `@Command(name = "nodes")`, with picocli silently shadowing one. Merging them into a single `NodesCommand` (with `LifecycleCommand` / `DrainCommand` / `ActivateCommand` / `ShutdownCommand` / `SlicesCommand` / `RoutesCommand` / `InflightCommand` / `MetricsCommand` inner subcommands) eliminates the collision. Default behavior preserved: `aether nodes` with no subcommand still lists active nodes (calls `NODES_LIST`). VIEWER role unchanged — same read-only routes, just reached via the consolidated namespace
- **`/api/cluster/status` wire format: per-node `lifecycleState` → `kvState` + `derivedStatus` split, and field actually populated** — `ClusterStatusNodeInfo` in the `/api/cluster/status` `nodes[]` array previously had a single `lifecycleState` field hardcoded to `"ON_DUTY"` for every node (a long-standing stub). The field is replaced by `kvState` (authoritative FSM state read directly from KV-Store via `NodeLifecycleKey`; `SHUTTING_DOWN` normalized to `DRAINING`; empty string when no KV entry exists yet) and `derivedStatus` (operator-visible projection of KV ∪ SWIM ∪ aggregated reachability ∪ quorum, with route-layer downgrade to `UNKNOWN` when a quorum of observers reports UNREACHABLE). Mirrors the `NodeInfo.kvState`/`derivedStatus` split applied to `/api/nodes/status` in RC1. Follow-up: `ClusterStatusNodeInfo.role` is still hardcoded `"core"` — should read from `ActivationDirectiveValue` like `NodesResponse.EnrichedNodeInfo.role`
- **`/api/nodes/status` wire format: split JVM-runtime state from cluster-FSM state** — the top-level `lifecycleState` field on `StatusResponse` previously carried the JVM/process state machine (`NodeState`: `STARTING` / `JOINING` / `ACTIVE` / `DRAINING` / `STOPPED`). It is renamed to `runtimeState` and a NEW `lifecycleState` field is added one position later carrying the cluster-level FSM intent from KV-Store (`NodeLifecycleState`: `JOINING` / `ON_DUTY` / `DRAINING` / `DECOMMISSIONED` / `FAILED_DRAIN`; `SHUTTING_DOWN` is normalized to `DRAINING`). Empty string when no KV entry exists yet (cold-start transient window). The two fields are orthogonal: `runtimeState` answers "is this JVM up and serving"; `lifecycleState` answers "what role does the cluster expect this node to play". Per-node fields inside `cluster.nodes[]` (`kvState` / `derivedStatus`) are unchanged. Consumers of the old top-level `lifecycleState` field that expected FSM semantics (`ON_DUTY`) continue to work; consumers that expected `NodeState` semantics (`ACTIVE`) must migrate to `runtimeState`. Dashboard already consumes per-node `kvState`/`derivedStatus`, unaffected.
- **All ComputeProviders (Docker / Hetzner / AWS / GCP / Azure) honour `AETHER_CLUSTER_NAME` env when `ProvisionContext.clusterName()` is empty** — closes the pre-bootstrap gap where CTM-provisioned replacement containers/VMs received `aether.cluster=default` / `aether-cluster=unknown` labels because the KV-Store `ClusterConfigValue` wasn't yet seeded. Each provider now falls back through the same precedence: `ProvisionContext.clusterName()` → `AETHER_CLUSTER_NAME` env → provider-specific default (`"default"` for Docker, `config.clusterName().or("unknown")` for Hetzner, empty for AWS/GCP/Azure). Integration test compose YAMLs set `AETHER_CLUSTER_NAME: "a"` / `"b"` in their shared env block, so cluster A's CTM replacements now carry `aether.cluster=a` matching the compose-fixed nodes — closes spec caveat-c
- **Integration test infra: `aether.cluster` label across compose + helper filter (RC1)** — `docker-compose-{a,b}.yml` now set both `aether.cluster: "a"`/`"b"` and `aether.node-id: "node-N"` on every node service. `_docker_container_by_node_id_label` in `lib/cluster.sh` now filters on `--filter label=aether.cluster=${CLUSTER_ID}` as the primary cluster scope, keeping the docker-network filter as defence-in-depth (catches the case where a hand-rolled fixture omits the label). Architecturally cleaner than the previous network-only filter — works for k8s pods, bare-metal Docker, Hetzner servers where docker-network-name is not meaningful

### Added
- **Stream configuration replicated through KV-Store consensus (issue #215)** — `StreamConfig` metadata created by `createStream` on the governor is now replicated to every node via the consensus channel, fixing the long-standing gap where `streamInfo` and `listStreams` on a non-governor returned empty even though the stream existed cluster-wide. New `AetherKey.StreamConfigKey(streamName)` (`stream-config/<name>`) + `AetherValue.StreamConfigValue(StreamConfig config, long createdAt)` pair carries the full config (partitions, retention policy fields including `maxCount`/`maxBytes`/`maxAgeMs`/`RetentionMode`, autoOffsetReset, maxEventSizeBytes, ConsistencyMode, minSyncReplicas, StreamCompression, optional encryptionKeyId) with a 12-field pipe-delimited `KVStoreSerializer` round-trip so warm-start snapshots replay identically. `StreamPartitionManager.createStream` issues a `KVCommand.Put` after the local allocation succeeds; `destroyStream` issues a `KVCommand.Remove` after the in-memory release. `AetherNode` wires the inverse direction: a new `KVNotificationRouter<AetherKey, AetherValue>` subscribes `StreamPartitionManager::onStreamConfigPut` and `::onStreamConfigRemove` so every node hydrates its local `streams` map on `ValuePut` (computeIfAbsent semantics — duplicate replays don't double-allocate or clobber existing partition state) and drops the entry on `ValueRemove`. New `StreamConfigReplicationTest` (8 cases across `WritePath` + `ReadPath` `@Nested` groups) verifies the write path issues the right commands, a receiver that never ran `createStream` answers `streamInfo`/`listStreams` after observing the `ValuePut`, duplicate replays preserve the existing allocation, removes drop the entry, and missing-key removes are no-ops
- **Trace injection endpoint (REST + CLI)** — `POST /api/traces/inject` inserts a synthetic invocation trace entry directly into the node-local `InvocationTraceStore`, bypassing the runtime invocation pipeline. The entry is visible via `GET /api/traces` immediately after this call returns, indistinguishable in shape from runtime-emitted traces except for the synthetic `nodeId=@injected` / `caller=@injected` markers. Closes the `11-observability/test-invocation-traces.sh` `test_traces_contain_request_id` / `test_traces_contain_duration` / `test_traces_contain_depth` TODOs — tracing is not auto-enabled in the integration cluster and there is no runtime path to deterministically produce a trace for shape assertions, so the field-presence tests were structurally untestable end-to-end without an injection knob. Per the REST/CLI/docs triad: route registered in `ManagementRoute.TRACES_INJECT` (POST, `/api/traces/inject`, target `ANY` — `InvocationTraceStore` is node-local in-memory state, NOT consensus-replicated, so the inject lands on the receiving node and the read-back must hit the same node, which `_resolve_live_endpoint` in the integration test harness does naturally); body shape `{operation, durationMs?, depth?, requestId?, traceId?}` deserialised into `ObservabilityRoutes.InjectTraceRequest`; handler delegates to new `InvocationTraceStore.inject(operation, durationMs, depth, requestId, traceId)` which validates (non-blank operation; failure maps to a typed `InjectionError` enum implementing `Cause`), resolves the trace correlator by precedence (client `requestId` → client `traceId` → freshly generated UUID), applies defaults `durationMs=10` (translated to `durationNs=10_000_000`) and `depth=0`, stamps `nodeId=@injected` / `caller=@injected` to distinguish synthetic traces from runtime-emitted ones at the data layer, builds an `InvocationNode` with `outcome=SUCCESS` / `local=true` / `hops=0`, and writes it through the existing `record(node)` ring buffer so the new entry shares the same read path as production traces. Response is a new `TraceInjectResponse(traceId, requestId, operation, durationMs, depth, timestamp)` where `traceId == requestId` (the trace store keys by requestId; the response exposes both names so callers using either correlator can grep). CLI subcommand `aether traces inject --operation <op> [--duration-ms <ms>] [--depth <d>] [--request-id <id>] [--trace-id <id>]` with manual JSON body construction (parity with `aether alerts inject` shape); docs added to `aether/docs/reference/management-api.md` + `aether/docs/reference/cli.md`; unit tests in new `InvocationTraceStoreInjectTest` cover happy path (stamped response shape with client-provided ids), read-back via `all()` and `forRequest()` (entry visible by requestId), validation failures (blank operation, whitespace-only operation), default stamping (UUID generation when ids omitted, `durationMs=10` / `depth=0` defaults applied, `traceId` falls back into `requestId` slot when requestId alone is missing); integration test `11-observability/test-invocation-traces.sh` rewritten — `test_generate_traceable_requests` now POSTs three synthetic traces via `/api/traces/inject` with known operation names and captures the returned `requestId`s, `test_traces_contain_request_id` asserts each captured requestId is present in `GET /api/traces`, `test_traces_contain_duration` asserts `durationMs` is non-zero on the entries, `test_traces_contain_depth` asserts the `depth` field is present
- **Alert injection endpoint (REST + CLI)** — `POST /api/alerts/inject` inserts a synthetic alert entry directly, bypassing threshold evaluation. The entry is visible via `GET /api/alerts` (active list) immediately after the call returns and is also written to alert history with status `INJECTED`. Closes the `11-observability/test-alerts.sh` `test_check_alerts_fired` + `test_alerts_have_fields` TODOs — the runtime publishes no test-only metric so threshold-driven firing was structurally untestable end-to-end without either a synthetic metric pipeline or this injection knob. Per the REST/CLI/docs triad: route registered in `ManagementRoute.ALERTS_INJECT` (POST, `/api/alerts/inject`, target `ANY` — alerts are node-local in-memory state, NOT KV-replicated like thresholds are, so leader routing would be incorrect; the inject lands on the receiving node and the read-back must hit the same node, which `_resolve_live_endpoint` in the integration test harness does naturally); body shape `{name, severity, message, metric?, value?}` deserialised into `AlertRoutes.InjectRequest`; handler delegates to new `AlertManager.inject(name, severity, message, metric, value)` which validates (non-blank name + non-blank message + severity ∈ {INFO, WARNING, CRITICAL}; failures map to a typed `InjectionError` enum implementing `Cause`), stamps a monotonic `alertId` of the form `injected-<timestamp>-<seq>` via an `AtomicLong` counter, stores the entry in a parallel `injectedAlerts` `ConcurrentHashMap` keyed by alertId, and writes an `AlertHistoryEntry` with status `INJECTED`. `activeAlertsAsJson` renders injected entries inline with threshold-driven ones (marked `source=injected`), so a single `GET /api/alerts` surface presents both. `clearAlerts` also clears the injected map for parity with the existing semantics. CLI subcommand `aether alerts inject --name <n> --severity <s> --message <m> [--metric <name>] [--value <v>]` with manual JSON body construction (parity with `aether thresholds set` shape); docs added to `aether/docs/reference/management-api.md` + `aether/docs/reference/cli.md`; unit tests in new `AlertManagerInjectTest` cover happy path (stamped response shape), read-back via `activeAlertsAsJson` (alertId/name/severity/source fields present), history entry presence (status=INJECTED), and three validation failures (blank name, blank message, invalid severity); integration test `11-observability/test-alerts.sh` rewritten — `test_trigger_alert_condition` now POSTs to `/api/alerts/inject` and captures the returned `alertId`, `test_check_alerts_fired` asserts the alertId is present in `GET /api/alerts`, and `test_alerts_have_fields` asserts the entry exposes `name`, `severity`, `message`, and `source=injected` fields with expected values
- **CTM auto-heal disable/enable toggle (REST + CLI)** — operator-controlled gate on deficit-driven replacement provisioning, distinct from the failure-driven circuit breaker. `GET /api/cluster/topology/auto-heal` returns `{enabled}`; `POST /api/cluster/topology/auto-heal/{enable,disable}` flips the gate and returns `{enabled, previousState}` for the audit log. All three leader-routed. When disabled, `handleDeficit` short-circuits to a debug-level log message — already-in-flight provisioning attempts continue (separate cancel paths). Closes `13-edge-cases/test-disruption-budget` which previously raced the budget check against CTM auto-heal; the test can now disable auto-heal, drain N nodes, verify the Nth drain is rejected by the disruption budget, and re-enable. Per the REST/CLI/docs triad: route entries in `ManagementRoute` (`CLUSTER_AUTO_HEAL_{STATUS,ENABLE,DISABLE}`), handler in `ClusterTopologyRoutes` (delegates through `ClusterTopologyManager.{isAutoHealEnabled,setAutoHealEnabled}`), CLI subcommands `aether topology auto-heal {status,enable,disable}`, docs in `management-api.md` + `cli.md`, shell helpers `disable_auto_heal`/`enable_auto_heal`/`auto_heal_enabled` in `aether/tests/integration/lib/cluster.sh` that drive the CLI rather than raw curl
- **CTM provisioning circuit breaker auto-reset after 1h quiescence** — when the breaker has been tripped but no provisioning failure has occurred for `PROVISIONING_AUTO_RESET_QUIESCENCE_MS` (1 hour), `provisioningCircuitTripped()` self-clears the failure counter and lets `handleDeficit` resume. Backstop for unattended clusters where none of the explicit reset triggers (`setDesiredSize`, `onNodeReady`, phase NORMAL transition, leader handoff, operator reset) ever fires. The window is conservative — long enough for paged operators to investigate before self-heal kicks in, short enough that an idle cluster eventually retries
- **CTM provisioning circuit breaker reset endpoint + CLI** — `GET /api/cluster/topology/circuit-breaker` returns the breaker state (`consecutiveFailures`, `trippedAt`, `nextAllowedMs`, `tripped`); `POST /api/cluster/topology/circuit-breaker/reset` clears the failure counter and forces a reconcile attempt. Both are leader-routed. The breaker auto-resets on `setDesiredSize`, `onNodeReady`, phase NORMAL transition, and leader handoff (`activate`) — but those triggers don't fire when the cluster gets stuck in BOOTING after sustained chaos (no scale operation pending, no successful node arrival). The new explicit reset is the operator's knob for that scenario. Per the REST/CLI/docs triad: route registered in `ManagementRoute` enum, handler wired in `ClusterTopologyRoutes` (delegates through `ClusterTopologyManager.resetCircuitBreaker(reason)`), CLI subcommands `aether topology circuit-breaker {status,reset}`, docs in `management-api.md` + `cli.md`. Test infrastructure also gets a `reset_provisioning_circuit` shell helper that `restart_all_nodes` invokes when phase=NORMAL doesn't converge — prevents cascade failures across destructive suites where CTM stays tripped from prior provisioning attempts

### Changed
- **Integration test infra: round 2 — chaos SWIM cold-boot bypass + CTM scale-up timeout + 06-deployment isolation** — follow-on to the green-sticker remediation campaign that pushed docker-remote from 8/15 to **10/15** (with the remaining 5 suite failures all carrying explicit `TODO:` markers naming exactly what product/fixture work is needed). Three structural fixes:
  - **`restart_all_nodes` waits for `phase=NORMAL`** (non-fatal) — `SwimProtocol.emitFaultyOrUnknown` suppresses `FaultyObserved` in `phase=BOOTING` for any peer not yet in `everSeenHealthy`, so `restart_all_nodes` returning before NORMAL caused the next chaos test's kill to produce `UnknownObserved` instead of `FaultyObserved` — no `TransportObservation.PeerObservedFaulty` propagated, `HealthReconciler` never decided DECOMMISSIONED, no `NODE_LEFT/NODE_FAILED` event reached `/api/events`. The same wait now also runs at the start of every chaos test (`02-chaos/test-kill-*`) and `12-network/test-swim-detection`. Wait is non-fatal (warn + continue) because cluster A+B concurrent load can push first-restart NORMAL transition past 180s while subsequent restarts settle in 0s; gating fail-closed cascaded into broken-cleanup-state for every downstream suite. Closes 02-chaos `test-kill-node` + `test-kill-under-load` 60s detection-timeout pattern: 02-chaos went **2p/2f → 4p/0f** in v6
  - **`scale_cluster` hard 30s timeout** — bypasses `leader_api_post`'s `_resolve_live_endpoint` round-trip and posts `/api/cluster/scale` directly with `curl -m 30`. Previously a non-responsive leader (e.g., when CTM circuit breaker had tripped) hung the entire suite for the full `wait_for_node_count_fast` budget (300s × `TIMEOUT_SCALE=2` = 600s wall time per scale call). Test now fails fast with a real diagnostic ("CTM circuit breaker may be tripped") instead of stalling. Combined with the existing `cluster_node_count` `max(members, desiredSize)` heuristic, the scale tests pass when the scale POST commits `desiredSize=N` to KV — actual node provisioning success is gated by the existing CTM behaviour, which is the documented test contract. Closes 03-scaling 600s hang: 03-scaling went **HUNG → 3p/0f** in v6
  - **06-deployment cleanup restores baseline `v1.0.0`** — `test-deploy-canary`, `test-deploy-blue-green`, and `test-deploy-rolling` each share `BLUEPRINT_V1=1.0.0` / `BLUEPRINT_V2=1.0.1` and end by calling `deploy_complete` which leaves `1.0.1` ACTIVE in the cluster. The next test then issues `deploy_start v1.0.1 <strategy>` and the `/api/deploy` endpoint returns 500 "Cannot deploy version 1.0.1 — already active" (the strategy-based endpoint correctly refuses same-version redeploy; only `/api/blueprint/deploy` allows it). Each test's `cleanup()` now calls `deploy_blueprint $BLUEPRINT_V1` after `deploy_cleanup` to restore `1.0.0` ACTIVE. Closes the rolling-deploy "already active" cascade
  - **`deploy_cleanup` prefers ROLLBACK over COMPLETE** — for in-progress deployments cleanup now tries `deploy_rollback` first and falls back to `deploy_complete` only on rollback failure. Restoring to baseline matches test-isolation intent, where COMPLETE leaves whatever was being deployed as the new ACTIVE version
  - **Schema endpoint TODOs eliminated via runtime datasource discovery** — `06-deployment/test-schema-migration` and all 3 `10-database/test-schema-*` tests now discover the registered schema-tracked datasource at runtime by querying `/api/schema/status` (the list endpoint), then run their per-datasource assertions against the discovered name. The `test-persistence` blueprint ships `schema/V900__create_kv.sql`, which `BlueprintService.buildSchemaMigrationCommands` writes as a `SchemaVersionKey` for the `database` datasource — discoverable instantly post-deploy. With the actual datasource now addressable, the prior "needs real datasource fixture" TODOs are converted to strict assertions: `Migrations applied` checks `currentVersion ≥ 900` (proves the migration ran), `Schema history entries` checks `lastMigration` is non-empty (proves the V900 script name is recorded), `Schema status after baseline` accepts any non-FAILED/UNKNOWN status (proves the baseline call was acknowledged), `Schema status after retry` documents the orchestrator contract (retry against a healthy datasource transitions to FAILED — not a bug, the operator-forced-retry signal). Result: **06-deployment 5p/0f, 10-database 3p/0f** in focused validation
  - **Three TODO assertions converted to honest runtime verifications** — `12-network/test-quic-connectivity:All_nodes_connected` now reads `connectedPeerCount` from `/api/cluster/topology` and asserts `≥ 4` for a 5-node cluster (replaces the prior `connectionCount` metric fishing-expedition; `connectedPeerCount` IS exposed via `ClusterTopologyRoutes`). `12-network/test-gossip-encryption:active_via_config` and `:via_transport` now read `quic_handshake_total` and `quic_handshake_failures_total` from `/api/metrics/transport` and assert handshakes occurred (>0) and the failure ratio is ≤ 50% — handshake count > 0 is a deterministic positive signal of TLS-encrypted traffic since QUIC mandates `QuicSslContext` at the transport layer. `13-edge-cases/test-concurrent-deploys:Artifact_isolation` now deploys `test-persistence` as a second blueprint alongside `test-echo` and asserts both artifact identifiers appear in `/api/slices` (replaces the prior single-blueprint fixture which was structurally incapable of proving isolation)
  - **`_docker_container_name` passes through `aether-core-*` CTM replacements unchanged** — CTM-provisioned replacement containers carry their own prefix from `DockerComputeProvider`; their name IS the node id. Previously `kill_node` synthesized `aether-b-aether-core-node-X-<uuid>` which doesn't exist; `docker kill` returned "No such container" and the test silently believed the kill landed. Closes `13-edge-cases/test-stale-route-cleanup` recovery timeout
- **`AppHttpServer.sendNoRouteFound` returns 503 (not 404) when route table snapshot lags `HttpRouteRegistry`** — disambiguates two structurally distinct 404 sources that previously collided at the wire: (a) "no route registered for this method+path" (404, terminal) vs (b) "route IS registered in `HttpRouteRegistry` but the local `RouteTable` snapshot hasn't been republished yet" (now 503, retryable). The non-owning node receives a `NodeRoutesKey` `ValuePut` from consensus, but `HttpRouteRegistry::onNodeRoutesPut` and `AppHttpServer::onNodeRoutesPut` are two separate KV listeners on the same event — there is a brief window between registry update and snapshot rebuild where a request arrives, the snapshot lookup misses, and the prior 404 was indistinguishable from "no such route." `sendNoRouteFound` now consults `routeRegistry.findRoute(method, path)` before deciding the status: present → 503 + "Route table propagating; retry after a moment"; absent → 404 (existing behaviour). Closes the docker-remote `08-resources/test-sql-connector` PUT 404 race where `wait_for_slices_active` reported owner-side `state=ACTIVE` but node-1's snapshot hadn't yet absorbed the route. No downstream consumers distinguish 5xx vs 4xx in retry logic (verified across HttpForwarder paths and LB), so the status change is backward-compatible
- **SWIM detection floor lowered from ~28s nominal / ~50-60s p95 to ~17s / ~30-40s p95** — `SwimConfig.DEFAULT.suspectTimeout` 15s → 10s and `HealthReconcilerConfig.DEFAULT.cooldownMs` 10s → 5s. The 15s suspectTimeout was the dominant hop in the SWIM detection chain (`SUSPECT → FAULTY → HealthReconciler → DECOMMISSIONED → onNodeLifecyclePut → emit event`); compounded with the 10s reconciler cooldown that suppresses FAULTY commits when the leader recently wrote anything for the victim, p95 detection sat right on the integration-test 60s SLO wall. Lowering both removes the dominant suppression collisions without inviting false-positive FAULTY transitions: SWIM still requires a successful indirect-probe round to fail before SUSPECT is entered, and the reconciler still flap-protects via the (unchanged) 5s aggregation/stable windows. `TimeoutsConfig.SwimTimeouts.swimTimeouts()` toml default updated in lockstep so `CoreSwimHealthDetectorConfigTest.fromTimeouts_swimTimeoutsDefaults_matchLegacySwimConfigDefault` continues to pass. Operators can override via `[timeouts.swim] suspect_timeout = "Xs"` for environments with higher transient packet loss
- **Integration test infra: green-sticker remediation campaign** — broad sweep of the integration test suite to remove "passes regardless of system state" assertions following an audit that found ~45 such instances. Replaces the institutional warn-then-pass demotion pattern (every observability/database test ended `log_warn "metric not found"; log_pass "endpoint responds"` so the test couldn't fail unless HTTP itself 5xx'd) with strict assertions OR explicit `log_fail "TODO: <missing capability>"` markers that fail-closed until the underlying gap is closed. Highlights:
  - `lib/cluster.sh` — `retarget_app_endpoint_to_active_slice` probe replaced with new `app_route_wired` positive-readiness helper (distinguishes route-missing 404 from slice-NotFound 404 via response body); `pick_non_leader` no longer falls back to a hardcoded `node-1..5` list when `/api/nodes/lifecycle` returns empty (was silently picking DECOMMISSIONED victims); `slices_total_instances` regex tightened from over-permissive `[LA][CO][AT][DI][EV][DE]*` character class to strict `(LOADED\|ACTIVE)` alternation; `task_assignment_count` requires a value pair instead of bare `"group"` token (no more nested-object overcounts); `wait_for_node_count_on` / `wait_for_all_tasks_active` use `-1` sentinel on parse error instead of `\|\| echo 0` (which was equivalent to "0 found so far"); `wait_for_leader_on` checks `leaderId` field instead of `role:ACTIVE` (a per-node attribute, not a leadership signal); `container_running` adds `/health/live` probe (docker `status=running` ≠ JVM responsive); `is_cluster_healthy` pinned to single canonical value; `restart_all_nodes` cloud loop aggregates per-node failures (was `\|\| true` per node, masking 4-of-5 unreachable as success); `start_node` docker fallback captures stderr; `deploy_cleanup` captures both complete-and-rollback stderr; `publish_blueprint` blueprint-visible wait fail-closes (was warn-and-continue)
  - `lib/common.sh` — `api_delete`, `app_get`, `app_post`, `node_api_get`, `node_api_post` routed through `_api_call` so HTTP error bodies surface as warn diagnostics rather than being silently dropped by `curl -sf`. `wait_for` predicate eval now distinguishes bash parse errors (rc=2) and command-not-found (rc=127) from genuine predicate-false (rc=1) — surfaces test-author bugs that previously waited the full timeout and reported "not satisfied"
  - `lib/topology.sh` — `observe_quorum_window` fail-closes by default when the event window is empty (was "ok (no events in window)" — masked dropped events); callers can opt-in to allow-empty via 3rd arg
  - `lib/load.sh` — load helpers tightened from `2xx + 3xx` to strict `2xx` (was counting 304 cached and 3xx redirects as success against API endpoints that should never return them)
  - `lib/json.sh` — `json_value` and `json_array_length` carry prominent doc warnings about their known limitations (nesting blindness, comma-in-string overcount); recommend `aether_field` CLI for correctness-critical lookups
  - `run-tests.sh` — `TIMEOUT_SCALE=2` for `--env remote` (was 1, while `cloud` is 3) — Hetzner-class jitter on the docker-remote path no longer hard-bumps the suite's 60s SWIM detection wall
  - **Suite test fixes (30 files modified across 11-observability, 10-database, 06-deployment, 12-network, 04-streaming, 02-chaos, 13-edge-cases, 15-delegation, 07-cluster-mgmt, 08-resources, 09-artifacts, 00-smoke, 01-stability):** strict positive HTTP status assertions in place of `< 500` / `< 400` predicates; exact JSON-field grep in place of substring `grep -q`; `log_fail "TODO: …"` for tests structurally untestable without product fixtures (alert firing without a metric injection mechanism, trace shape without a deterministic invocation path, schema migrations without a real datasource binding); event-driven barriers (`wait_for_node_departure` / `wait_for_replacement_of`) replacing load-bearing `sleep N # let SWIM detect` patterns in 02-chaos kill tests
  - **Net:** ~17 test functions now intentionally fail with `TODO` markers naming the missing capability (alert injection, trace shape, schema fixtures, QUIC connection-count metric, gossip-encryption introspection); ~30 tests tightened to pass with strict assertions. The TODOs are the honest accounting of what the suite cannot prove today — the prior warn-then-pass made these failures invisible. CI-visible failures with named blockers are the desired state; subsequent product work can close each TODO incrementally
- **Membership notification split: typed observation/decision streams (D2 structural fix)** — replaces the unified `TopologyChangeNotification` with two type-distinct sealed interfaces in `integrations/consensus/topology/`: `TransportObservation` (local fast-path: `PeerJoined`, `PeerDisconnected`, `PeerReconnected`, `PeerObservedFaulty`, `SelfShutdown`, with `ObservationSource` enum {QUIC, NETTY, SWIM}) and `MembershipDecision` (cluster-canonical: `NodeJoined`, `NodeRemoved`, `NodeDecommissioned`). Fixes the long-standing dual-emission problem structurally — the previous unified type conflated two epistemically different facts ("I observed peer X disconnect" vs "the cluster has agreed peer X is no longer a member"), causing subscribers to receive duplicate emissions for the same conceptual event from two different paths under different timing conditions. The compiler now enforces non-confusion: subscribers' type signatures declare which stream they consume; sealed-exhaustive checking prevents accidental cross-consumption. Bootstrap chicken-egg (leader-election needs a topology view, snapshot only exists after consensus commits) resolved naturally — `LeaderManager`/`ClusterFsmRouter`/`RabiaNode` consume `TransportObservation` (the partial-view fast-path stream), all other 11+ subscribers (CDM, CTM, LB, HttpForwarder, SliceInvoker, TaskAssignmentCoordinator, ClusterSyncCollector/Scheduler, DeploymentMetricsCollector/Scheduler, ControlLoop, AppHttpServer, DHTTopologyListener) consume `MembershipDecision`. `TopologyObserver.publishMembershipDeltas` is the exclusive emitter of `MembershipDecision`. `SwimProtocol` now also emits `TransportObservation.PeerObservedFaulty` to the cluster-wide router (alongside its existing internal `SwimObservation` flow which is SWIM-protocol-internal). Single-writer rule refined: applies to `MembershipDecision` (consensus-driven decisions); `TransportObservation` has no single writer by design (every node observes locally). Spec rewrite at [`aether/docs/specs/membership-architecture-spec.md`](aether/docs/specs/membership-architecture-spec.md) v2 documents the new model. Follow-up for RC2: `PeerObservationStore` cross-node observation aggregator (audit Step 7) lands cleanly as a transducer between the two streams; ~2-3 days focused effort. 22 production sites + 29 test fixtures migrated atomically; legacy `TopologyChangeNotification` deleted; full reactor `mvn test` green (no semantic regressions)
- **Tighten chaos test timeouts** — five callsite reductions in `aether/tests/integration/`: `wait_for_node_count 5 180` → `90` (12-network/quic, 12-network/swim, 13-edge-cases/stale-routes), `wait_for_replacement_of ... 180` → `90` (12-network/quic), `wait_for_cluster 180` → `90` (07-cluster-mgmt/bootstrap), inter-suite `await_generation_quiesced` barrier `120s` → `60s` in `run-tests.sh`. With the existing `TIMEOUT_SCALE=3` cloud multiplier these become `270s` / `180s` on cloud — comfortable margin once VM snapshot work eliminates apt-update + image-pull cloud-init time. Snapshot-quiesce + SWIM-FAULTY-leader fixes from earlier in this RC make the underlying ops deterministically faster, so the prior generous timeouts were no longer needed
- **`TopologyObserver` explicit `BOOTING` / `NORMAL` mode** — replaces the implicit cold-boot-vs-steady-state distinction left by audit Step 5's partial revert. New `TopologyMode` enum + `AtomicReference<TopologyMode> mode` field initialised to `BOOTING`. `BOOTING` reads `healthyActiveNodeCount`, `readyNodeCount`, and quorum-eval counts from the legacy `nodeStatesById` fallback; `NORMAL` reads snapshot-only and returns 0 on empty snapshot. Transition `BOOTING → NORMAL` is one-way, triggered by the first `MembershipView` observation with `coreMemberIds().size() >= clusterSize/2+1` (quorum reached in the projected snapshot). Mode is checked on every read path AND on snapshot publish, so admin / CTM reads pre-`start()` can still flip the latch as soon as a quorum-projected snapshot is available. Exposed via `TopologyObserver.topologyMode()` and surfaced in `aether status --format json` (`topology.mode`)
- **Phase-aware SWIM cold-boot suppression (audit Step 6)** — `SwimProtocol.emitFaultyOrUnknown` no longer suppresses `FaultyObserved` based solely on the per-peer `everSeenHealthy` flag. New `BooleanSupplier isBooting` injected via `SwimHealthContext`; in `BOOTING` phase the legacy cold-boot suppression preserves today's behaviour (peer never observed healthy → emit `UnknownObserved`), but in `NORMAL` phase a `FAULTY` transition always emits `FaultyObserved` regardless of `everSeenHealthy`. Closes the cloud-only failure mode where a peer killed before its first successful Ping ack would emit `UnknownObserved` (which `HealthReconciler.aggregator` doesn't aggregate), the leader never wrote `DECOMMISSIONED`, and `NODE_LEFT` / `NODE_FAILED` events never fired. Wiring uses a generic `BooleanSupplier` so `integrations/swim` doesn't gain a dependency on `aether/slice` — `AetherNode` translates `() -> healthReconciler.phase() == ClusterPhase.BOOTING` at the boundary
- **`ClusterIdentity` validates at construction (parse-don't-validate)** — name regex `^[a-z][a-z0-9-]{0,62}$` moved from `ClusterBootstrapCommand` (CLI override path only) into the `ClusterIdentity` factory and `withName` mutator. Both now return `Result<ClusterIdentity>` with a typed `ClusterIdentity.InvalidName` cause; downstream readers can trust the invariant. Parser (`ClusterBootstrapConfigParser.parseClusterIdentity`), CLI (`ClusterBootstrapCommand.applyClusterNameOverride`), and config-record helper (`ClusterBootstrapConfig.withClusterName`) all chain through `Result`. Closes the silent path where `parseClusterIdentity` could accept an uppercase / leading-digit / overlong name in TOML and propagate it into Hetzner labels (where it would 422), DNS labels, and operator-facing env-var names. New `ClusterIdentityTest` (14 cases) exercises the boundary conditions — single letter, max length 63, blank, uppercase, leading hyphen, leading digit, underscore, and special chars
- **Membership state-tracker consolidation, audit Steps 1–5** — partial implementation of [`aether/docs/.internal/audits/membership-state-tracker-audit-2026-05-07.md`](aether/docs/.internal/audits/membership-state-tracker-audit-2026-05-07.md). Goal: collapse the 4 parallel state trackers + 6 debounce sidecars into a single canonical `MembershipView` + projections. Steps 6 (phase-aware SWIM cold-boot suppression), 7 (cross-node quorum aggregation, HIGH risk — needs `PeerObservationStore` reducer), and 8 (cleanup) deferred:
  - **Step 1:** new `MembershipDelta` record + `TopologyObserver.publishMembershipDeltas()` diff publisher hooked into `evaluateQuorumState`. Emits one `TopologyChangeNotification.NodeAdded`/`NodeRemoved` per snapshot edge
  - **Step 2:** `QuicClusterNetwork.processViewChange` no longer emits `TopologyChangeNotification.NodeAdded`/`NodeRemoved` upward — `TopologyObserver` is the canonical emitter. SHUTDOWN's `NodeDown` retained for self-shutdown semantics. DHT routing gap closed: `DHTTopologyListener.onNodeDown` mirrors `onNodeRemoved` (was missing — pre-existing routing gap)
  - **Step 3:** AetherNode's SWIM-FAULTY-to-disconnect lambda removed. `SwimHealthContext.routeFaulty` no longer calls `routeDisconnect`. QUIC eviction now flows post-consensus via `TopologyChangeNotification.NodeRemoved` → `clusterNetwork.disconnect`. Eviction trades sub-ms local-SWIM latency for a Rabia round-trip + projection (~200-500ms cloud RTT), but eliminates the N+1 fan-out cascade across every survivor's local SWIM listener
  - **Step 4:** `ClusterEventAggregator` no longer subscribes to `SwimObservation`. `NODE_FAILED`/`NODE_LEFT` events emit only via `onNodeLifecyclePut` (KV-Store source-of-truth) eliminating the duplicate witness emit
  - **Step 5:** `TopologyObserver.healthyActiveNodeCount`/`readyNodeCount` are snapshot-only — legacy `nodeStatesById`-derived fallback removed. Cold-boot windows where the snapshot is empty conservatively report `0` instead of leaking a transport-derived count that disagrees with the leader's view. `legacyHealthyActivePeerCount` and `activeTopologySize` private methods deleted
  - **Step 8 partial:** `SwimHealthContext.routeDisconnect` deleted (no callers post-Step 3); unused `NetworkServiceMessage` import dropped
- **JBCT single-pass processing** — new `jbct:process` Maven goal that runs lint and format in a single pass per file: parse once, lint the CST, format the CST, write if changed, discard. Replaces the prior `format` + `lint` binding pair (each file was parsed twice). Constant memory (one CST live at a time), one parse per file. File-selection policy (size cap and excludes) consolidated to a single source of truth: `[files] maxFileSize` and `[files] excludes` in `jbct.toml` enforced by `FileCollector` upstream. Generated parsers excluded explicitly via `**/aether/pg/parser/PgSqlParser.java` and `**/jbct/parser/Java25Parser.java` patterns rather than the size heuristic — robust against future grammar/parser-size changes. Removed three redundant size guards (`ProcessMojo.sizeLimit` parameter, `FlowFormatter.MAX_SOURCE_SIZE` constant, and the duplicate per-Mojo size check). Standalone `format` and `lint` goals retained for direct invocation. Library entry points `JbctFormatter.formatParsed(CstNode, SourceFile)` and `JbctLinter.lintParsed(CstNode, SourceFile)` exposed for orchestrators that already hold a parse tree. Root pom binding switched from `format,lint` to `process`. Spec at [`jbct/docs/single-pass-processing-spec.md`](jbct/docs/single-pass-processing-spec.md)

### Changed
- **JBCT-RET-01 cleanup: `@Contract` on six `TargetRunner` void methods** — closes the six pre-existing `JBCT-RET-01` violations in `aether/forge/forge-load/.../ConfigurableLoadRunner.java` that had been blocking `./build.sh` Step 2 (`mvn jbct:check`) in the forge-load module. Added `@Contract` to `setStartTime`, `stop`, `pause`, `resume`, `updateLatencyEma`, and `run` — all intentional side-effect void methods on the inner `TargetRunner` record (load-generation lifecycle and atomic-state mutators). Lint output: `Check results: 12 format issue(s), 0 lint error(s), 1 warning(s)` — RET-01 count goes to zero; the 12 remaining items are pre-existing format-only issues across other files in the module (cleared by `mvn jbct:format` / `./build.sh` Step 1) and the surviving warning is `JBCT-RET-06` in a sibling `LoadConfigLoader.java:165:51` (parameter null-check, separate refactor concern). Closes task #16 from the RC1 wave-tracking. Module `aether/forge/forge-load` now lint-clean
- **JBCT v6 parser migration (spike)** — jbct-parser, jbct-format, jbct-lint, jbct-maven-plugin internals migrated from the legacy `peglib:generate` emit (40K-line `Java25Parser` with `CstNode`/`RuleId` tree types) to the v6 emit (`peglib:generate-v6` producing `Java25Lexer` + `Java25ParserV6` + `Java25Visitor` over `peglib-runtime`'s flat `CstArray`/`TokenArray`/`ParseResult`). New abstractions: sealed `Cursor` interface (Leaf/Branch/ErrorNode variants, 16 bytes per cursor, no tree materialization), `RuleKind` enum with 107 values matching v6 rule-kind ids, `TriviaToken` record over TokenArray entries, `LineIndex` for source-offset → line/column. Public APIs unchanged (`JbctFormatter.format(SourceFile)`, `JbctLinter.lint(SourceFile)`, maven goals). Internal SPI changed: `CstLintRule.check(CstNode)` → `check(Cursor)`; `CstNodes` utility (30 nav methods) retyped to take `Cursor` + `RuleKind`. The 41 lint rule implementations migrated mechanically (RuleId.X.class → RuleKind.X; legacy `RuleId.Identifier.class` lookups for method/class names replaced with regex extraction since v6 emits identifiers as tokens not CST nodes). The formatter's `FlowFormatter.flattenZomWrappers` and `printStmt` brace-shape detection — both added during the rc1 B1-B4 fix work to compensate for legacy-emit quirks — are gone under v6 because the new emit produces clean per-node trivia attribution and unified `Stmt[Block]` / `Stmt[Expr]` body shapes (see `jbct/docs/v6-cst-shape-probe.md` for the empirical Stage 0 findings). Deleted: legacy `Java25Parser.java` (~40K lines), vestigial `CstFormatter`/`CstPrinter`/`SpacingRules`/`CstFormatterTest`. Branch: `spike/jbct-v6-migration`. Test status: 241/244 jbct tests pass (174/174 jbct-lint, 31/31 jbct-parser, 7/7 jbct-maven-plugin, 29/32 jbct-format — 3 remaining format failures are nested-chain alignment column offsets in ChainAlignment.java / TernaryOperators.java fixtures, ≤2-column diffs).

### Changed
- **JBCT formatter v2 rules — comprehensive style spec with 452/452 tests** — complete overhaul of `FlowPrinter` formatting rules replacing the old width-heuristic approach with a uniform, locally-decidable rule set: (1) **Annotations** always on own line, except on parameters and type-uses (inline). (2) **Ternaries** always break — `?`/`:` lead the continuation, aligned under the condition's first non-space character; `TernaryCondScope` added to `AlignmentContext` to prevent `&&`/`||` inside the condition from also breaking. (3) **Method chains** with 2+ calls always break (Sequencer-as-steps — every `.method()` on its own line aligned to the chain anchor), even 2-call chains. (4) **`&&` chains** in return/throw context break at 2+ operators; suppressed inside ternary conditions and inline expressions. (5) **Block-lambda body** alignment: body indented one tab past the lambda-param column; closing `})` aligns to the param. `forcedIndentCol` propagated through `emitLeadingComments` to avoid integer-division rounding on non-multiple-of-tab body columns. (6) **Trailing line comments** (`// ...` after `;`) preserved on the same source line — class body, block stmts, and chain postOps each call `emitTrailingCommentsFrom` before the line separator. (7) **Array initializer `{a, b, c}`** wraps when inline would exceed 120 chars; `LONG_LIST`/`LONG_MAP`/`extends`/`throws` stay inline (fit comfortably under 120). (8) **Records and `switch (x) {`** always emit a space before `{`. (9) **Blank line before final `return`/`throw`** when the method has at least one prior simple statement; lambda bodies with 4+ stmts also get the separator. (10) **Unary minus/plus** after any binary-op char (`=`, `+`, `?`, `:`, …) emits without a space. 21 existing golden fixtures overhauled + 6 new fixtures added (`FlowEdgeCases`, `NoArgChains`, `TernaryInArg`, `ChainArgWraps`, `AnnotationLocations`, `BlankLineEdges`). All 452 jbct tests pass (97 parser + 38 format (including 24 golden idempotency) + 174 lint + 7 plugin + 136 other format).

### Fixed
- **DHT ring prune on transport disconnect (RC1 — 09-artifacts 1MB push regression)** — `DHTTopologyListener` lost its `TransportObservation.PeerDisconnected` subscription during the audit-step-2 membership-notification refactor (`5fdffe967`). Consequence: the consistent hash ring kept stale peers as replica owners after transport-level disconnect, so `DistributedDHTClient.put` targeted unreachable nodes; `network.send` silently dropped the message; `QuorumCollector` stalled until the per-chunk 10s timeout. For 1MB artifact pushes (16 chunks × 64KB), the slowest stuck chunk dominated `Promise.allOf` and the test client observed an indefinite hang. Fix: new `DHTTopologyListener.onPeerDisconnected(TransportObservation.PeerDisconnected)` handler removes the disconnected NodeId from the ring and notifies the rebalancer; `AetherNode` re-routes the event into the listener alongside the existing `MembershipDecision` subscriptions. The transport-fast path now keeps the DHT ring consistent ahead of consensus-driven membership decisions, matching the pre-refactor behaviour
- **`ArtifactStore.deploy` aggregate 30s timeout on chunk fan-out (RC1 — defense in depth)** — chunked artifact upload uses `Promise.allOf(blockIdPromises)` to fan out per-chunk DHT puts. Without an aggregate timeout, a single stuck chunk (e.g., stale-replica DHT routing) holds the whole deploy open indefinitely. New `DEPLOY_TIMEOUT = timeSpan(30).seconds()` wraps the `allOf` so the entire upload bounds out instead of inheriting the longest stuck chunk's wait. Pairs with the DHT ring prune fix above
- **Integration test infra: remove nginx mgmt-gateway; CLUSTER_*_MGMT direct to node-1 (RC1)** — the nginx mgmt-gateway service introduced in `472b529ad` (six days ago) added `proxy_request_buffering on` + `proxy_next_upstream` retry-on-error which caused 09-artifacts 1MB pushes to 504 (nginx re-sent the entire 1MB body on every retry until upstream timeout). It was originally added to give MGMT_ENTRY_POINT survivability across single-core failures — but `_resolve_live_endpoint` (`lib/common.sh:145`) already provides that at the test-client layer by rotating MGMT_PORT..MGMT_PORT+N-1 on failure. nginx was operationally redundant. Removed: `aether-{a,b}-mgmt-gateway` compose service blocks (39 lines each), `nginx-mgmt-gateway-{a,b}.conf` files, `remote_scp` lines in `run-tests.sh`, gateway short-circuit in `rotate_mgmt_entry_point`. CLUSTER_A_MGMT now `5151`, CLUSTER_B_MGMT `5161` (node-1 direct). `_resolve_live_endpoint` handles failover. Architecturally cleaner: aether-node's mgmt API already enforces auth + leader-forwarding at the handler layer
- **Integration test infra: cluster-network-scoped label lookup (RC1 — 15-delegation cross-cluster contamination)** — Wave 4's universal `aether.node-id` label coverage made the label value identical for both clusters' `node-2` containers. `_docker_container_by_node_id_label` returned whichever Docker enumerated first; 15-delegation running on cluster A intermittently killed `aether-b-node-2`. Fix: filter by `--filter network=aether-${CLUSTER_ID}-network` when CLUSTER_ID is set. Plus `15-delegation/suite.conf` reclassified from `cluster=non-destructive destructive=false` to `cluster=destructive destructive=true` (the suite calls `kill_node`). Net: 15-delegation 1p/1f → 2p/0f. Architecturally cleaner fix (orthogonal `aether.cluster` label) specced in [`aether/docs/specs/cluster-label-scoping-spec.md`](aether/docs/specs/cluster-label-scoping-spec.md) for RC1/RC2 follow-up
- **`/api/status` and `/api/cluster/topology` honesty: ON_DUTY downgrades to UNKNOWN when peer not in transport-connected set (RC1 Wave 4 follow-up)** — closes the SWIM-detection-lag window where a recently-killed peer continues to report `lifecycleState=ON_DUTY` for ~10-20s (probe interval + suspectTimeout + reconciler aggregation) because `MembershipView.mapKvState(ON_DUTY, ...)` only requires `SwimHealth.HEALTHY` and SWIM's gossip-based detection is structurally slower than transport-level disconnect. The fix cross-references `node.connectedPeerIds()` (transport's active-peer set, which closes within ~1s of a QUIC connection drop) at the API surface in two places: (1) `StatusRoutes.toNodeInfo` now downgrades the per-peer `lifecycleState` to `"UNKNOWN"` when a peer reports `ON_DUTY` in `MembershipView` but is absent from `connectedPeers`, with self exempt (we never transport-connect to ourselves); (2) `ClusterTopologyRoutes.assembleFromTopologyManager` and `assembleTopologyStatus` now compute `coreCount` via a new `transportConnectedOnDutyCount(view, connectedPeers, selfId)` helper that filters `view.onDutyPeers()` to peers that are either self or transport-connected. `MembershipView`'s internal semantics are unchanged — only the operator-facing API surface gains the cross-reference, so internal consumers (CDM, SliceInvoker, ClusterFsmRouter, etc.) keep the SWIM-only view. Net delta in cluster B integration: 02-chaos 2p/2f → **3p/1f** (`test-kill-leader` 5/5 PASS in 0s remains; `test-kill-non-leader` and `test-kill-under-load` go from FAIL to PASS), 12-network 0p/3f → **3p/0f**. Companion test-infra changes: `is_cluster_ready` now ALSO requires `cluster_node_count_on_duty_healthy >= NODE_COUNT` (without this, `wait_for_cluster` returned TRUE while only the generation-snapshot count was satisfied, leaving downstream `pick_non_leader` looking at a non-operationally-ready cluster); two 12-network test functions (`test_cluster_formed_with_encryption`, `test_initial_state` in `test-swim-detection`) replaced their `cluster_node_count` (raw generation-snapshot membership, includes JOINING + tombstones) with `cluster_node_count_on_duty_healthy` (operational health, matches what `restore_cluster_baseline` waits for and what the test intent actually wants). Pre-existing 02-chaos `Kill_2_nodes` failure remains — it needs 2 victims but only 1 is visible from the entry point's view, which is a deeper per-node QUIC connectivity-variance issue tracked separately
- **Test-infra: `pick_non_leader` skips stale ON_DUTY candidates whose containers no longer exist on TARGET_HOST (RC1 Wave 4 follow-up)** — closes the cascading "no such container" failure mode that surfaced in Wave 4 after the universal-label diagnostic upgrade. Root cause is upstream: `/api/status` continues to report `lifecycleState=ON_DUTY` for a CTM-provisioned replacement node after it has been killed in a prior test file but before the SWIM-FAULTY → DECOMMISSIONED chain has propagated through `MembershipView`, so `pick_non_leader` happily echoes the dead NodeId across multiple test files in a chaos suite run. Fix is test-side defensive: docker-mode `pick_non_leader` now consults `_docker_container_by_node_id_label "$candidate"` for each candidate after the leader/pinned filter and skips any candidate whose container is absent on `$TARGET_HOST`, emitting a `[WARN]` line that explicitly names the upstream concern (`/api/status reports '<id>' as ON_DUTY but no live container carries label aether.node-id=<id>`) so the staleness stays visible rather than being silently papered over. `CLOUD_MODE=true` skips the guard (cloud providers don't expose `docker ps`). Net delta vs Wave 4 (`./run-tests.sh --env remote --skip-build --suites 02,12`): 12-network 0p/3f → **1p/2f**, gossip-encryption suite recovered from 0/3 → 6/6 PASS (Wave 4's apparent TLS regression was state leakage from chaos-test silent-abort upstream — the encryption tests themselves never had a bug); 02-chaos unchanged 2p/2f but the two remaining failures now carry a clean upstream root-cause label (`pick_non_leader: only 0/1 candidates available`) instead of `docker kill: no such container` (which routed attention to the test-infra layer instead of the cluster-side `MembershipView` propagation gap)
- **Hetzner provider: identity-bound provisioning + dotted↔hyphenated translation (RC1 Wave 4)** — closes the parity gap with Docker provider's identity-bound model. `HetznerComputeProvider.labelsFor(ProvisionContext)` now writes `aether-node-id=<id>` into the Hetzner label map when `ctx.nodeId().isPresent()`. The Hetzner native convention is hyphenated (`aether-node-id`, per HCloud API kebab-case norm); the upper-layer constant `NodeLifecycleManager.NODE_ID_TAG = "aether.node-id"` (dotted, Docker-native) is provider-agnostic — the boundary translation lives in `HetznerComputeProvider.translateKeys(...)` so `listInstances(Map.of("aether.node-id", id))` correctly resolves to `aether-node-id=<id>` when querying the HCloud API for `terminate-by-NodeId`. A multi-line invariant comment was added above `NodeLifecycleManager.NODE_ID_TAG` to document the convention split and prevent re-introduction of hyphenated lookups at the upper layer. Two new unit tests (`provision_contextWithNodeId_setsAetherNodeIdLabelOnServer`, `listInstances_withDottedNodeIdTag_translatesToHetznerLabel`) verify both ends of the translation. Test count delta in `aether/environment/hetzner`: 47 → 49. New end-to-end test `ClusterTopologyManagerIdentityBoundSlotE2ETest` drives the complete identity-bound flow through CTM + a real `MembershipFsm` against a shared KV fixture: deficit dispatch → slot write with `assignedNodeId` → slot expiry past `deadlineMs` → `NodeLifecycleKey → DECOMMISSIONED` tombstone + `terminateNode` invoked → late `HealthyObserved` for the tombstoned NodeId feeds into the leader FSM → asserts FSM stays `Decommissioned` (via `applyDecommissioned` cell at `ClusterMembershipReducer.java:184`) and no command is emitted. Distinct from the four mock-level unit tests in `ClusterTopologyManagerIdentityBoundSlotTest` — this one validates the end-to-end production code path
- **Test-infra: universal `aether.node-id` label + `kill_node` failure surfacing (RC1 Wave 4)** — closes the silent-failure mode that caused 02-chaos `test-kill-multiple` / `test-kill-node` / `test-kill-under-load` and 12-network `test-quic-connectivity` / `test-swim-detection` to terminate without `[FAIL]` diagnostics when `kill_node` operated on a CTM-provisioned `aether-core-node-<KSUID>` container. Root cause: `cluster.sh:1271` captured `remote_exec "docker kill ..."` output into a variable without an rc check; when `remote_exec` returned non-zero (SSH hiccup, missing container, etc.), `set -euo pipefail` killed the test function silently — `[FAIL]` line never emitted, `TEST_FAIL_COUNT` never incremented, only the suite-level FAIL count moved. Fix has four parts: (1) `aether/tests/integration/docker-compose-{a,b}.yml` add `labels: { aether.node-id: "node-N" }` to all 10 cluster-node services for universal coverage (CTM-provisioned containers already carry the label via `DockerComputeProvider.buildRunCommand`); (2) new `_docker_container_by_node_id_label` helper in `cluster.sh` queries `docker ps --filter 'label=aether.node-id=<id>'` so container resolution is identity-bound and NodeId-format-agnostic; (3) `kill_node` docker-mode branch rewritten with `kill_rc=0` + `|| kill_rc=$?` pattern (survives `set -e`), `log_fail "kill_node: docker kill of '${node_id}' (container=${name}) failed (rc=${kill_rc}): ${kill_out}"` + `return "$kill_rc"` on non-zero. Audit of `pick_non_leader`, `start_node`, `drop_ctm_replacements`, `restart_all_nodes`, `container_running`, `list_aether_containers` confirmed no remaining silent-exit patterns. `bash -n` clean; `docker-compose config` clean
- **CTM identity-bound provisioning slots (RC1 Wave 3b)** — `ProvisioningSlotKey` is now bound to a real, pre-allocated `NodeId` from the moment CTM dispatches a provision. `ClusterTopologyManagerRecord.provisionSingleNode` calls `generateProvisioningNodeId()` (using `IdGenerator.generate("aether-core-node")` with an 8-attempt paranoia collision-check against `observer.topology()` / `lifecycleReader` / `inFlightProvisions` / `slotKeyByNodeId`) BEFORE invoking `lifecycleManager.provisionNode`, threads the id through `ProvisionContext.withNodeId(...)`, and writes `ProvisioningSlotValue(spawnedAtMs, deadlineMs, assignedNodeId)` from creation. The previous flow used a synthetic `ctm-inflight-<ts>-<hash>` `localTag` only for `inFlightProvisions` map bookkeeping; the slot atom carried no identity and `slotIsAssignedAndComplete` could never match production slots. The new `tombstoneAssignedNodeOnExpiry` path (invoked from `deleteExpiredSlotAtoms`) writes `NodeLifecycleKey → DECOMMISSIONED` for the assigned NodeId via `lifecycleWriter.requestDecommission(...)` and best-effort calls `lifecycleManager.terminateNode(...)` to reap the cloud-side instance, then prunes the in-memory `slotKeyByNodeId` / `inFlightProvisions` entries. Late-arriving nodes whose slot already expired flow through the existing `ClusterMembershipReducer.applyDecommissioned` cell (returns `Outcome.nop`) and are NOT promoted to ON_DUTY — the tombstone IS the gate. `DockerComputeProvider.buildRunCommand` already honours `ctx.nodeId()` via `--label aether.node-id=<nodeId>` + `-e NODE_ID=<nodeId>`, and `Main.parseNodeId` reads `NODE_ID` env first, so the new container self-reports as the allocated id — closing the loop. New test class `ClusterTopologyManagerIdentityBoundSlotTest` (4 cases): `provisionNodes_writesSlotWithAssignedNodeId`, `slotExpiry_tombstonesAssignedNodeId`, `lateArrival_afterSlotExpiry_doesNotPromote`, `provisionNodes_generatesUniqueIds`. Hetzner provider parity closed in Wave 4
- **CTM deficit + surplus accounting includes live provisioning slots (RC1 Wave 3a)** — `ClusterTopologyManagerRecord.reconcileActive` deficit used `desired - healthyOnDuty` only; the generation projector at `ClusterGenerationProjector.projectCoreMembers` retains JOINING/DRAINING/FAILED_DRAIN as core members (only DECOMMISSIONED is filtered), so a stuck-JOINING peer was simultaneously a core occupant AND uncounted by deficit math — CTM provisioned a top-up that didn't immediately heal, and the cycle repeated. Symptoms: 02-chaos "4 healthy / 5 expected", 12-network "Initial: 5 nodes got 6", 03-scaling "7 after scale-down expected 5". Also: `handleSurplus` early-returned on any `Reconciling` state, so same-target overshoot (`actual=6, target=5`) was never terminated — CTM waited forever for in-flight terminations that the surplus path itself was supposed to dispatch. Fix: new `liveProvisioningSlotCount()` helper reads un-expired KV slot atoms; `reconcileActive` deficit becomes `max(0, desired - healthyOnDuty - liveSlots)` and short-circuits dispatch when fully covered (logs include `liveSlots`); `handleSurplus` replaces broad `Reconciling` guard with a narrowed two-condition check — defer ONLY when `Reconciling && liveSlots > 0` (in-flight provisions yet to settle) OR when terminations are already pending (preserves re-entry protection). New test class `ClusterTopologyManagerLiveSlotAccountingTest` (4 cases): deficit-with-live-slots, deficit-with-expired-slots, surplus-during-reconciling-no-live-slots, surplus-during-reconciling-with-live-slots. Drives slots through the production `provisionNodes` → `writeProvisioningSlotAtom` path (no manual test-cheat installation). Net: +8 tests across Wave 3
- **`ClusterNetwork.activePeers()` widens externally-visible peer count to include EVICTED (RC1 Wave 2, B)** — `ClusterTopologyRoutes.assembleFromTopologyManager` exposed `connectedPeerCount = AetherNode.connectedPeerIds().size()`, which delegated to `QuicClusterNetwork.connectedPeers()` — a strict `phase == CONNECTED` filter. But the internal quorum-counting method `activeConnectedCount` deliberately counts `CONNECTED + EVICTED` (per its existing comment: "EVICTED is a transient local-view state ... the peer remains in topology and its offline buffer is preserved. Only REMOVED ... drops the peer from the quorum count"). The external REST view did not match the internal quorum semantics, so the `12-network/All_nodes_connected` test reading `connectedPeerCount=3` instead of 4 caught the cluster in a momentary EVICTED transition. Fix: new `ClusterNetwork.activePeers()` accessor (default → `connectedPeers()`; `QuicClusterNetwork` override includes both CONNECTED and EVICTED phases, mirroring `activeConnectedCount`); `AetherNode.connectedPeerIds()` now calls `activePeers()`. `StatusRoutes.buildNodesResponse` is unaffected behaviourally (it enumerates the same set; EVICTED peers now appear in operator-facing `/api/status` node listings — desired behaviour). Internal consensus dispatch (`currentView`, route lookup) still uses the strict `connectedPeers()`. 548/548 `integrations/consensus` tests pass; `aether/node` compiles clean
- **Test-side semantic alignment with current API contracts (RC1 Wave 2, C)** — six tests in 07/06/10/15/13 suites were SILENTLY PASSING under the H2-bug harness; Wave 1's `log_fail` latch correctly tagged them as failing. The tests themselves had drifted from API contracts: `07-cluster-mgmt/test-apply.sh` and `test-export.sh` sent body `{overrides:{...}}` to `/api/config` whose `ConfigRoutes.SetConfigRequest` expects `{key,value}` — both fixed to use the documented shape; `06-deployment/test-schema-migration.sh:test_schema_retry` and `10-database/test-schema-retry.sh:test_schema_retry_endpoint` + `test_retry_idempotent` now accept both 2xx AND the documented "not in FAILED state" 500 body as contract-passing (the orchestrator correctly rejects retry on a healthy datasource); `15-delegation/test-02-reassignment.sh:test_node_failure_reassignment` no longer asserts `replacement-nodeId != killed-nodeId` (CTM reuses the logical NodeId for the replacement container by design) — now asserts the SCALING role is ACTIVE on its assigned node post-kill, which is the semantically correct invariant; `13-edge-cases/test-disruption-budget.sh:test_drain_first_node_allowed` got a diagnostic probe (auto-heal state + response-body capture) before the drain; the explicit `log_fail "TODO: investigate First_drain 503"` surfaces the failure visibly instead of masking it pending product-side investigation
- **Test-infra: artifact provisioning + `push_blueprint` hardening (RC1 Wave 2, A)** — `06-deployment` deploy-strategy tests reference `org.pragmatica.aether.example:url-shortener:1.0.0/1.0.1` but the runner's `deploy_blueprints` (`run-tests.sh:208`) hardcodes `org.pragmatica.aether.test:${bp}:1.0.0` — only `test/` groupId at v1.0.0 ever gets pre-pushed. Result: `Artifact not found` 400s on every strategy-deploy test. `test-deploy-immediate.sh` now calls `push_blueprint "$BLUEPRINT"` explicitly in `test_cluster_ready` (the other three strategy tests already had matching pushes for V1 and V2). `push_blueprint` itself in `lib/cluster.sh:879-924` was rewritten to be robust under `set -euo pipefail`: removed the `2>/dev/null` mask, captures stderr to a tempfile, treats `already exists`/`409`/`conflict`/`duplicate artifact` as idempotent success, retries up to 3 times (configurable via `PUSH_BLUEPRINT_ATTEMPTS`) on transient `NotLeader`/`503`/`timeout`/`connection refused`, and surfaces terminal failures with the stderr body via `log_warn` so callers no longer abort blind mid-test
- **`@Contract` on 8 pre-existing intentional side-effect voids (RC1 Wave 2, D)** — `SliceLoadingContext.stopBuffering`, `ComputeProvider.resetProvisionerState`, `EntryPointMetrics.EntryPointStats.{recordSuccess,recordFailure,reset}`, `QueryValidator.{registerTable,registerPermissive,registerFromCte}` — all pure mutator/registrar voids (AtomicBoolean.set, ConcurrentHashMap.put, AtomicLong.incrementAndGet, HashSet.add) with no failure modes. Were lint-blocking `./build.sh` Step 2 with JBCT-RET-01 across `aether/slice-api`, `aether/environment-integration`, `aether/forge/forge-simulator`, `aether/pg-tools/pg-schema`. 6 newly-visible JBCT-RET-01 sites in `ConfigurableLoadRunner.java` surfaced after this pass (per-module lint stops at first failure) — tracked as a separate cleanup
- **`NetworkServiceMessage.ConnectionEstablished` carries `Option<NodeInfo>` (RC1 Wave 1, P1)** — when the topology had forgotten a peer (CTM-replaced, post-chaos kill where `HealthReconciler` wiped, etc.), `QuicClusterNetwork.finalizeReconnect` built `unknownNodeInfo` from the QUIC Hello handshake, logged it at DEBUG, and dropped it. The routed `ConnectionEstablished(NodeId)` then fell back to `swimHealthDetector.onNodeConnected(NodeId)` which dispatches `PeerConnected(id, none())`, and `SwimHealthContext.resolveSwimAddress` searched only static `topologyConfig.coreNodes()` — dynamic NodeIds had no resolution path. Result: QUIC reconnected, SWIM never produced health evidence, `swimHealthGate` rejected `UNKNOWN`, the missing-peer reconciler re-attempted, and the system entered a 1 Hz heartbeat eviction loop (the 4103×`evictStaleConnection` / 3467×`buildUnknownNodeInfo` pattern observed in cluster B chaos runs). Fix: `ConnectionEstablished` record gains an `Option<NodeInfo>` field plus factory overloads; both `QuicClusterNetwork` ADD path and `finalizeReconnect` populate it from the handshake-supplied `unknownNodeInfo`; `AetherNode`'s `ConnectionEstablished` handler prefers transport-supplied `NodeInfo` before falling back to `topologyForSwim.get(...)`. `NettyClusterNetwork:200` updated for record-shape compile; deeper Netty parity tracked as post-GA issue #223 (Netty is unused in prod RC1). New unit test `QuicClusterNetworkTest.finalizeReconnect_unknownNodeInfo_propagatedIntoRoutedMessage`
- **`SwimProtocol.handleAnnounce` stores SWIM-port address; `healthOf` falls back to live members (RC1 Wave 1, P2+P3)** — (P2) `handleAnnounce` registered the announcer at the raw `announce.nodeInfo().address().port()` — the QUIC port — in the SWIM `members` map. SWIM seeds correctly add `SWIM_PORT_OFFSET` (100), but the ANNOUNCE receive path did not. Consequence: dynamic joiners discovered via ANNOUNCE were probed at the QUIC port, never responded, transitioned to SUSPECT then FAULTY, and CTM provisioned replacements that re-triggered the loop — a second source of the QUIC eviction storm distinct from P1. Fix: new `SwimConfig.swimPortOffset()` field (configurable boundary inside `integrations/swim`; aether-node wires it via `.withSwimPortOffset(CoreSwimHealthDetector.SWIM_PORT_OFFSET)`); `handleAnnounce` derives the SWIM address via the offset. (P3) `healthOf(NodeId)` previously read only `lastEmittedHealth`. `addSeedMember` does not emit HEALTHY synchronously — only after the first probe ack — leaving a 1-2s window where seeds were ALIVE in the members map but `healthOf` returned UNKNOWN. The strict `MembershipFsm` gate at `AetherNode:1318` requires `== HEALTHY`, which the natural `HealthyObserved` listener eventually resolves; but this added startup-latency budget contention. `healthOf` now falls back to classifying the live `members` entry when `lastEmittedHealth` has no record. New unit-test class `SwimPortOffsetAndHealthOfTest` covers both behaviours. Ping/PingReq/Ack were intentionally NOT extended to carry NodeInfo — those messages already use the SWIM-socket sender address, so carrying NodeInfo would not change the address-learning outcome
- **Integration harness reliability — H1-H5 (RC1 Wave 1)** — five bugs in the integration test harness that turned product instability into misleading red-herring failures: (H1) `scale_cluster` had no HTTP status check — `{"error":"quorum unavailable"}` was treated as success and the test waited the full timeout for a scale that never happened; cascaded into `restore_cluster_baseline` burning the entire 600s budget on a rejected POST. Now uses `-w '%{http_code}'`, requires 2xx, warns and returns 1 on non-2xx with body excerpt. (H2) `log_fail` was cosmetic — `run_test` recorded pass/fail solely from the function's last exit code, so tests that didn't pair `log_fail` with `return 1` silently passed. Now introduces a per-test `TEST_FAIL_COUNT` latch; `log_fail` increments it; `run_test` records PASS only when both fn rc=0 AND latch=0. (H3) `wait_for_leader` predicate called `cluster_leader` twice in `[ -n "$(cluster_leader)" ] && [ "$(cluster_leader)" != 'none' ]` — two separate calls landing on different round-robin gateway backends during a re-election window produced inconsistent reads. Now `lid=$(cluster_leader); [ -n "$lid" ] && [ "$lid" != 'none' ]`. (H4) `pick_non_leader` trusted an externally-passed leader string and re-read `/api/status` from a different backend — could return the actual current leader as a kill victim when caller's leader value was stale or `none`. Now re-derives leader from the same `/api/status` JSON used to enumerate ON_DUTY nodes; rejects empty/`none` input fail-fast. (H5) `drop_ctm_replacements` and `container_running` wrapped `remote_exec` with `2>/dev/null`, masking SSH and `docker rm/ps` failures. Now captures stderr to a temp file, routes through `log_warn` on rc≠0. These five fixes are the reason the integration suite now reports more failures than baseline — every "regression" was either (a) pre-existing silent failure now correctly tagged, (b) test-side semantic mismatch, or (c) environment flake. Wave 1 delta on the storm suites: 02-chaos 2p/2f → 4p/0f, 03-scaling 0p/3f → 3p/0f, 11-observability 3p/2f → 6p/0f, 08-resources 4p/1f → 5p/0f
- **SWIM ANNOUNCE cold-join mechanism (S1–S4)** — new node broadcasts a SWIM ANNOUNCE UDP message to seed peers on startup; receiving peers add the announcer to SWIM membership and emit a `JoinAnnounced` observation; `JoinAnnounced` drives a QUIC connect to the new peer; `FaultyObserved` drives soft-evict; `DepartedObserved` drives `departurePermanent`. Eliminates the previous cold-boot race where a freshly-provisioned node depended on seeds initiating Ping cycles before any peer knew it existed
- **`SwimProtocol.healthOf(NodeId)` O(1) health query** — new synchronous lookup used by the QUIC reconciler gate; avoids a full membership scan on each missing-peer reconcile cycle
- **`QuicClusterNetwork` SWIM health gate** — missing-peer reconciler now calls `swimHealthGate` before attempting reconnect; peers in `FAULTY` or `UNKNOWN` state are skipped, eliminating spurious reconnect storms to peers already marked unhealthy by SWIM
- **`QuicClusterNetwork.connect(NodeInfo)` override** — new entry point for SWIM-announced peers that are not present in the static bootstrap topology; enables dynamic peer discovery without a topology reconfiguration
- **`BootstrapModule` seed retry gap** — `retryConfigSeedIfNeeded` correctly retries seed-config fetch on transient failures; removed the `SEED_GRACE_MS` hard delay that caused a fixed wait even when seeds were immediately reachable
- **`SwimProtocol.applyNewMember` no longer re-gossips `FAULTY` state** — a new member whose initial state resolves to `FAULTY` is silently dropped rather than gossiped cluster-wide, eliminating the gossip storm triggered when a previously-known FAULTY node-id re-announces itself during rolling restarts

- **JBCT v6 parser: `<<`, `>>`, `>>>` binary shift operators in expression context** — `Java25Lexer` emits `<<` as two consecutive `INLINE__LT` tokens and `>>` / `>>>` as two / three consecutive `INLINE__GT` tokens (not as the compound `KIND_LSHIFT` / `KIND_RSHIFT` / `KIND_URSHIFT` tokens the parser expected). `parseShift` failed to recognize any shift operator, causing the entire enclosing class to fail parsing ("trailing input not consumed"). Added `advanceLShift()`, `advanceRShift()`, `advanceURShift()` helpers that accept both the compound kind (for forward compat) and the token-pair / token-triple form; wired all three into `parseShift`. Compound assignments (`<<=`, `>>=`, `>>>=`) were already lexed correctly as single tokens and continue to work. `CompoundAssignments.java` golden fixture updated to cover all three binary shift operators; `V6SmokeTest` now parses all fixtures cleanly. 250/250 jbct tests pass (was 244/244 before — 6 additional tests from new format fixtures); `mvn jbct:process` idempotent reactor-wide. PR #221
- **JBCT formatter B1+B2+B3+B4 (re-enables `jbct:process` format pass)** — four destructive defects in `FlowFormatter`/`FlowPrinter` that previously forced the format goal to be disabled in rc1: (B1) `///` markdown javadoc on first class members deleted; (B2) `//` block comments before first statements deleted; (B3) nested-lambda chain indentation mangling; (B4) multi-line if/for/while bodies collapsed onto one line — and a B4 follow-on where multi-statement bodies were emitted as empty `{}`. Root causes: (1) `FlowFormatter.flattenNonTerminal` discarded inner-wrapper `leadingTrivia` when inlining same-rule wrappers — the legacy `peglib:generate` emit produces `ClassBody[T<{>, ClassBody[ClassMember*], T<}>]` with first-member docs attached to the inner wrapper. Forward inner leadingTrivia onto the first inlined child. (2) Skip redundant outer `printIndent` in the member / statement loops when the child has a leading line/block comment — `emitLeadingComments` re-emits the indent at the right spot. (3) The parser inlines `Stmt <- Block` producing `Stmt[T<{>, BlockStmt*, T<}>]` for single-statement bodies and `Stmt[T<{>, Block[BlockStmt*], T<}>]` for multi-statement bodies; new `printStmt` dispatch routes brace-shaped Stmts (different source lines for `{` vs `}`) to `printBlock`, and `shouldInlineChild` now inlines wrapping-Block children of Stmt so `printBlock` finds the BlockStmts in both shapes. (4) Replaced FlowPrinter's three raw alignment fields (`lambdaAlignStack`, `chainColumn`, `inBreakingChain`) with a single `AlignmentContext` scoped via try-with-resources at the four call sites — drops the incorrectly-placed interleaved chain save/restore in `printAlignedBlockStatements` that clobbered nested chain compute. New / updated golden fixtures: `CommentsExtended.java` (covers `///` on first/subsequent members, `//` before if/for/lambda, single- and multi-statement bodies); `KeywordPrefixedIdentifiers.java` updated to proper multi-line `if{switch{...}}` layout. Re-applies a 181-file reformat after the `e70d861e1` 26-file pass was reverted as it had been produced by the buggy pre-fix formatter
- **Apply `--restart no` consistently in SSH-deploy path** — `BootstrapPhaseDeploy.startRuntimeViaSsh` (used by remote/static-host deploy via `deploySshNode`) was still emitting `docker run ... --restart unless-stopped`, missed in the cloud + docker-compose pass that fixed `buildRestartCommand` and `UserDataTemplate.appendContainerRun`. The same architectural reason applies (CTM owns recovery; orchestrator restart competes with auto-heal — see `aether/docs/operator/deployment-recovery.md`). Two stale assertions in `BootstrapPhaseDeployCloudSshRestartTest` updated to match production
- **SWIM-FAULTY-on-leader bridge to QUIC disconnect (cloud Container kill-leader recovery)** — narrow re-introduction of the SWIM-to-QUIC bridge that audit Step 3 removed for general peers. Triggers ONLY when (a) cluster phase is `NORMAL` and (b) the FAULTY peer is the current cluster leader. `SwimHealthContext.routeFaulty` now invokes a `faultyLeaderEvictor` callback wired in `AetherNode` to `clusterNetwork.disconnect(DisconnectNode(peer))`. Closes the catch-22 where post-Step-3 architecture requires consensus-driven eviction (DECOMMISSIONED → snapshot delta → NodeRemoved → disconnect), but consensus.apply itself depends on reliable-broadcast progressing — and the broadcast queues sends to the dead-but-still-QUIC-connected leader indefinitely on cloud Container. Bridge is narrow: non-leader FAULTY peers continue through the post-consensus path, preserving Step 3's elimination of the N+1 fan-out cascade. Phase gate prevents premature eviction during boot-time transient FAULTY events. Idempotent at QUIC layer (concurrent `peer.evict` calls from N surviving nodes deduplicate). Validated: cloud Container 02-chaos went from 60+ min hang → 4p/0f in 1395s; docker-remote 02-chaos preserved at 4p/0f in 135s; cloud JVM unaffected
- **Cloud Container deployment now uses `--restart no`** — `BootstrapPhaseDeploy.buildRestartCommand` and `UserDataTemplate.appendContainerRun` previously used `--restart unless-stopped`, which competed with Aether's CTM auto-heal. When chaos tests `docker kill` an aether-node container, Docker would immediately respawn it (interpreting SIGKILL exit 137 as a transient crash); the cluster's KV-store has already evicted the node-id under the single-writer DECOMMISSIONED rule, so the respawned container can't rejoin and flap-loops. Cluster sees no failure event, no replacement provisioning, no leader re-election. JVM mode (no Docker layer) doesn't have this problem. Fix sets `--restart no` so kills are authoritative; CTM observes the failure and provisions a replacement VM. Aligned `aether/tests/integration/docker-compose-a.yml` to the same policy. Operator guidance: [`aether/docs/operator/deployment-recovery.md`](aether/docs/operator/deployment-recovery.md) explains why orchestrator restart policies (Docker `unless-stopped`, k8s `restartPolicy: Always`, systemd `Restart=on-failure`) must be disabled when CTM is active
- **`rotate_mgmt_entry_point` cloud-aware** — `aether/tests/integration/lib/cluster.sh` previously iterated `MGMT_PORT..MGMT_PORT+NODE_COUNT-1` on `TARGET_HOST`, which is correct for docker host-port mapping but invalid on cloud (each VM has its own public IP, mgmt port is uniformly 8080). Cloud chaos tests that killed the entry-point node couldn't find any surviving node to query for the new leader. Fix branches on `ENV_TYPE`: cloud iterates over node-ids resolving each to its public IP via `cloud_public_ip`, docker/remote keeps the port-range scan. Closes the secondary failure path uncovered when the restart-policy fix made cloud kills authoritative
- **`GenerationSnapshotPublisher` heartbeat unconditional on leader (snapshot-quiesce flake)** — root cause of the multi-suite `await-quiesced status=408` cascade that intermittently took down 08-resources, 12-network, 13-edge-cases, and 06-deployment after stabilization windows. The 1Hz publisher tick at `AetherNode.java:1240-1251` was guarded by `if (!swimHints.isEmpty())` — once SWIM hints expired, no Mark events fired, so the snapshot counter stopped advancing. Test barriers using `current+1` semantics (e.g. `generation_quiesce_now`) then waited 60-120s for an epoch that never arrived, cascading into 503 drains, missing `NODE_FAILED` events, and 500 PUTs. Fix: drop the guard so the leader publishes once per second regardless of activity. Tick renamed `swimHintsTickExecutor` → `publisherTickExecutor`. Cost: one Rabia round/sec/cluster. Cured run-to-run flake on docker-remote (0/1000ms barrier resolution vs prior 60-120s timeouts)
- **`ClusterAwaitQuiescedRoute` triggers a fresh publish on entry** — defence-in-depth for the heartbeat above. New `ManageableNode.requestGenerationSnapshotRefresh()` interface method; route handler calls `nodeSupplier.get().requestGenerationSnapshotRefresh()` before polling so the request itself drives the next publish (zero-latency response when called) rather than waiting up to 1s for the next tick. Threaded through `aetherNode` record as `Runnable refreshGenerationSnapshot` parameter, populated with `generationSnapshotPublisher::markDirty`
- **Disruption budget counts only ON_DUTY against initial topology** — `NodeLifecycleRoutes.checkDisruptionBudget` previously counted ALL non-`ON_DUTY` lifecycle entries (including stale `DECOMMISSIONED` records from prior destructive suites) toward `currentlyUnavailable`, rejecting drains that should have been allowed. Refactor: `minAvailable = (initialTopology.size() / 2) + 1` (intended majority); `operationalAfterDrain = countOnDuty() - 1` (live capacity). DECOMMISSIONED entries no longer pollute the calc. New `countOnDuty()` private helper replaces the old `countUnavailableNodes()`. Closes 13-edge-cases first-drain false-409 path
- **SSH preflight timeout 180s → 300s** — `BootstrapPhaseDeploy.SSH_PREFLIGHT_TIMEOUT_MS`. Hetzner cx33 cloud-init (apt update, package install, runtime download) regularly exceeds 180s on contended provisioning days, causing the entire bootstrap to abort at Phase 5 with "5 host(s) unreachable after 180s". 300s buys a comfortable margin while still detecting genuinely-stuck hosts
- **Integration test infrastructure: race-tolerant drain test + best-effort barriers** — `aether/tests/integration/lib/generation.sh` now `log_warn` on `await_generation_quiesced` timeout (was `log_fail`); the library function returns 1 either way, so callers using `|| log_warn` no longer get spurious `[FAIL]` lines while callers that want hard-fail behavior already explicitly handle the return code. `aether/tests/integration/suites/13-edge-cases/test-disruption-budget.sh` removes inter-drain `await_generation_quiesced` waits (CTM auto-heal would otherwise replenish drained capacity and mask the budget); second/third drain accept either 200 (within budget) or 409 (auto-heal raced; budget guarded quorum). `aether/tests/integration/suites/08-resources/test-sql-connector.sh` bumps slice-route probe timeout 30s → 90s to accommodate cluster A+B concurrent-load slice propagation windows
- **`pick_non_leader` queries actual cluster membership** — `aether/tests/integration/lib/cluster.sh:156` previously iterated a hardcoded `node-1..5` list, returning the same `node-2` victim across suites. After 02-chaos killed and "revived" `node-2` (where `start_node` does `docker start ${container}` only — no KV cleanup, so the cluster never re-admits the node per the single-writer DECOMMISSIONED rule), subsequent `pick_non_leader` calls in 12-network/03 still picked `node-2` even though it was no longer in cluster membership. Killing it produced no Ping timeout → no `FaultyObserved` → no `NODE_FAILED` event → SWIM-detect-time test timed out at 60s. Fix: query `/api/nodes/lifecycle`, filter to `state=ON_DUTY`, pick from those. Falls back to the hardcoded list only if the API call fails (pre-bootstrap edge case)
- **`DEFAULT_DECOMMISSIONED_RETENTION` lowered from 24h to 60s** — `DecommissionedAtomGc` was implemented and started in `AetherNode` but with a 24-hour retention default, so KV-Store accumulated DECOMMISSIONED entries across long sessions and back-to-back integration test suites. The reaper sweeps at retention/2 (clamped 5s..1h), so 60s retention → 30s sweep cadence. With sweep active, disruption-budget calculations and `/api/nodes/lifecycle` responses no longer get polluted by historical entries. Operators can override via `[operations.auto_heal] decommissioned_retention = "..."` for forensic retention. Closes 13-edge-cases disruption-budget cross-suite pollution
- **Docker image default API key now ADMIN role** — `aether/docker/aether-node/aether.toml` previously used the simple-syntax form `api_keys = ["aether-integration-test-key"]` which `ApiKeyEntry.defaultEntry` mapped to **VIEWER** role (per `ApiKeyEntry.DEFAULT_ROLE`). Every operator endpoint (`/api/blueprint/deploy`, `/api/node/drain`, `/api/scale/*`, `/api/cluster/await-quiesced`, `/api/cluster/config`, `/api/cluster/keys`) returned 403 to the integration-test API key. Cloud bootstrap was unaffected because it composes its own runtime TOML via `BootstrapOverlayGenerator` and `cloud-hetzner-b.toml` already used rich-syntax with `authorization_role = "ADMIN"`. Fix converts the docker image default to the same rich-syntax form (matches the cloud config pattern). The key value `aether-integration-test-key` is explicitly test-only by name; production deployments rotate to operator-supplied keys via `aether cluster create-key` regardless. Closes the docker-/remote-environment integration test path
- **Typed `ProvisionContext` replaces untyped `ProvisionSpec.tags` Map** — the `Map<String, String> tags` field on `ProvisionSpec` carried two incompatible namespaces (`aether-*` Hetzner-spec dashes for labels; `aether.*` Docker-style dots that Hetzner's API regex rejected with HTTP 422). Provider read sites used `tags().getOrDefault(...)` which silently produced wrong values when keys went missing. New `ProvisionContext(clusterName, role, sourceName, nodeId, peers, coreMax, provisionedBy, extraTags)` record carries the typed metadata; each provider does its own native encoding internally. Hetzner's `mergeLabels` regex filter (added in `29b7fed38`) is reduced to a defensive last-line filter on `extraTags` only — the typed-field path can no longer ship dotted keys as Hetzner labels. CTM's `buildProvisionTags()` renamed to `buildProvisionContext()`. `BootstrapPhaseProvision` builds a typed context per node. All 4 cloud providers (Hetzner / AWS / GCP / Azure) emit `aether-cluster` / `aether-role` / `aether-source` / `aether-node-id` labels from context fields instead of reading from a Map; Docker also reads from typed context (its dotted-label convention preserved internally). The 422-bug class is structurally impossible — the dotted keys never enter the Hetzner labels Map
- **`BootstrapState` persists source cleanup handles for fresh-shell destroy** — `aether cluster destroy --cluster X` from a fresh process used to fail when the operator had bootstrapped with a non-default credentials env-var name (e.g. `${env:HCLOUD_TOKEN_PROD}`); cleanup re-read the hardcoded `HCLOUD_TOKEN` (and AWS / GCP / Azure equivalents) per `CloudCredentials.fromEnvironment`, got an empty Bearer token, and 401-leaked the orphan VMs. New `Map<String, SourceCleanupHandle> sources` field in `BootstrapState` records per-source `(provider, region, credentialEnvVars)` after each successful provision — `credentialEnvVars` stores env-var **NAMES** (e.g. `{"api_token": "HCLOUD_TOKEN_PROD"}`), never values, so secrets stay out of `~/.aether/clusters/<name>/state.json`. `BootstrapPhaseProvision.stampSourceHandle` mines `${env:NAME}` patterns from the operator's raw TOML; `BootstrapCleanup.destroyVm` prefers the persisted handle and falls through to today's hardcoded `ProviderResolver.resolveCloudComputeForCleanup` only when the field is absent. Backward-compat: state files from before this change parse with `sources()` empty and use the legacy fallback path; round-trip preserves non-empty maps
- **CTM provisioning circuit breaker** — `ClusterTopologyManagerRecord` now bounds runaway provisioning when replacement VMs consistently fail to reach `ON_DUTY`. Counter increments on each slot deadline expiry and each provider API rejection; exponential backoff (30s → 60s → 120s → 240s → 300s cap) defers the next attempt; after `MAX_CONSECUTIVE_PROVISIONING_FAILURES = 3` consecutive failures the breaker trips and `handleDeficit` halts dispatch entirely until a successful node arrival (`onNodeReady`), `ClusterPhase.NORMAL` transition, leader (re)activation, or operator `setDesiredSize`. Closes the orphan-leak factory observed on cloud where a single 70 s slot timeout (cloud-init too slow for first-boot Docker pull + container start) cascaded into 7+ orphan VMs in 7 minutes
- **08-resources `test-sql-connector` slice routing flake on cloud** — extracted the inline retarget pattern into `retarget_app_endpoint_to_active_slice` lib helper that finds an ACTIVE slice owner, retargets `APP_ENDPOINT` to its public IP, and (optionally) probes a path until it returns < 500 to catch the brief window where ACTIVE was reported but the local route table is still settling
- **`cluster_node_count` snapshot lag** — added `cluster_node_count_quiesced` test helper that calls `await_generation_quiesced` against the current epoch before reading. For single-shot count assertions immediately after a state-changing action (`scale_cluster`, `kill_node`), avoids the `max(members, desired)` heuristic biasing toward the stale member count when the snapshot hasn't yet reflected the just-committed config write
- **CTM-provisioned VMs labeled `aether-cluster=unknown`** — replacement Hetzner VMs created by `ClusterTopologyManager` were tagged `aether-cluster=unknown` instead of the actual cluster name, breaking discovery-by-label and `cloud-reaper.sh --cluster X` scoping. Root cause: bootstrap's composed `aether.toml` never emitted `[cloud.discovery] cluster_name`, so `HetznerEnvironmentConfig.clusterName()` was `Option.empty()` on every running node. `HetznerComputeProvider.buildLabels()` then fell back to `"unknown"`. Two complementary fixes:
  - `BootstrapOverlayGenerator` now emits `[cloud.discovery] cluster_name = "<name>"` for cloud sources, populating the field that all four cloud factories' `applyDiscovery` reads
  - `ClusterTopologyManagerRecord.buildProvisionTags()` passes `aether-cluster=<name>` as an explicit override label (sourced from `ClusterConfigValue.clusterName`), so the provider's default never wins. Defense in depth: the new VM gets the right tag even if a node's TOML somehow lacks `[cloud.discovery]`
- **Bootstrap cleanup credential resolution** — `BootstrapCleanup.destroyVm` previously routed through `ProviderResolver.resolveCloudCompute(String)`, an overload that constructed a `CloudConfig` with `credentials = Map.of()`. Hetzner factory then read `getOrDefault("api_token", "")` and silently produced an empty Bearer token, causing every termination call on a partial-failure cleanup to return HTTP 401 — leaking ~5 orphan VMs per failed bootstrap and exhausting Hetzner cx33-fsn1 capacity within a few iterations. Three downstream fixes applied as one structural change so the discrepancy cannot recur:
  - `BootstrapCleanup` now uses the new `ProviderResolver.resolveCloudComputeForCleanup(String)` which sources credentials from the operator's environment (`HCLOUD_TOKEN`, `AWS_ACCESS_KEY_ID/SECRET/REGION`, `GCP_*`, `AZURE_*`) via the new `CloudCredentials.fromEnvironment` resolver. Mirrors the existing `defaultHetznerClient` SSH-key-cleanup path and the `tools/cloud-reaper.sh` contract
  - The broken `ProviderResolver.resolveCloudCompute(String)` overload (and its private `minimalCloudConfig`) is **deleted** so the credential-less path can no longer be selected
  - All four cloud factories (Hetzner / AWS / GCP / Azure) now fail-fast with `EnvironmentError.CredentialsMissing` listing the missing env vars, replacing the silent `getOrDefault("...", "")` pattern. Previously a missing token reached the SDK as an empty string and the API rejected with a generic 401/422 deep in the stack
- **Cleanup failure exit code** — bootstrap and destroy commands now return `ExitCode.CLEANUP_FAILED` (4) when post-failure cloud resource cleanup leaves orphan resources, distinct from generic `ERROR` (1). Previously the orchestrator discarded the cleanup `Result` via `_ = cleanupHook().apply(state)` and only printed a `WARN` line per orphan. CI / orphan-detection pipelines can now react specifically to leaks. Bootstrap wraps the original cause in `BootstrapError.BootstrapFailedWithOrphans`; destroy escalates via `printSummary`

### Added
- **Pre-pulled VM snapshot support** — operator-facing primitive for shifting Docker install + image pull (or JDK install + JAR download) off the per-VM cloud-init critical path into a one-shot snapshot build. Saves ~30–60s per VM provision, multiplied by every CTM auto-heal replacement, scale-out, and cluster bootstrap for the snapshot's lifetime (per Aether version). Uses the existing `image` field on every cloud provider config (`HetznerEnvironmentConfig.image`, `AwsEnvironmentConfig.amiId`, `GcpEnvironmentConfig.sourceImage`, `AzureEnvironmentConfig.image`) — no schema change. Three pieces:
  - **`tools/build-aether-vm-snapshot.sh`** — Hetzner-specific builder with subcommands `build` / `list` / `latest` / `destroy` / `prune-old`. Provisions a temp VM, runs preparation cloud-init (Docker install + image pull, or JDK install + JAR download), powers it off, creates a snapshot via `/servers/{id}/actions/create_image`, deletes the build VM. Snapshots labelled `aether-snapshot=true` / `aether-version=<v>` / `aether-runtime=container|jvm`. Mirrors the operator-tool shape of `tools/provision-test-pg.sh`
  - **Idempotent cloud-init guards in `UserDataTemplate`** — `docker pull` wrapped in `if ! docker image inspect "${AETHER_IMAGE}" >/dev/null 2>&1`; JVM JAR `curl` wrapped in `if [ ! -s /opt/aether/aether-node.jar ]`. A snapshot-prepared VM short-circuits both. Existing Docker-install / JDK-install guards already idempotent
  - **`AETHER_VM_SNAPSHOT_ID` / `AETHER_VM_SNAPSHOT_ID_JVM` env overrides in `run-tests.sh`** — when set, the integration test runner copies `cloud-hetzner*.toml` to a temp dir with `image = "ubuntu-22.04"` rewritten to the snapshot id, leaving the originals untouched. Cleanup integrated into the existing `teardown` EXIT trap
  - Operator doc: [`aether/docs/operator/vm-snapshot.md`](aether/docs/operator/vm-snapshot.md) — when this matters, lifecycle (per Aether version), what snapshots include and don't include, cross-provider mechanism
- **`aether/docs/operator/deployment-recovery.md`** — operator-facing doc explaining Aether's recovery-ownership model: container/orchestrator restart policies must be disabled because CTM auto-heal IS the recovery layer. Two layers competing produces flapping nodes, masked failures, and incoherent failure semantics. Covers Docker, Kubernetes, Nomad, systemd. Explains why `restartPolicy: Never` or equivalent is required, how CTM's recovery flow works, and the architectural intent (each VM hosts one node; failure → replacement, not in-place restart)
- **`run-tests.sh --runtime container|jvm` flag** — selects between `cloud-hetzner.toml` / `cloud-hetzner-b.toml` (container, default) and `cloud-hetzner-jvm.toml` / `cloud-hetzner-jvm-b.toml` (JVM mode). New `cloud-hetzner-jvm-b.toml` for cluster B JVM-mode integration tests. Validates the second deployment path on cloud
- **ClusterGeneration distributed membership choreography** — epoch-fenced cluster-wide snapshots (`ClusterGenerationSnapshot`, `Epoch`, `Spokesman`, `ClusterQuiescence`). `GET /api/cluster/generation` exposes the current snapshot (always-safe, never 503); `POST /api/cluster/await-quiesced?epoch=T:C&timeout=30s` blocks until the queried node observes that epoch at quiescence. CLI: `aether cluster generation`, `aether cluster await-quiesced`. See [`aether/docs/specs/cluster-generation-spec.md`](aether/docs/specs/cluster-generation-spec.md)
- **`SliceState.ROUTING`** — new transitional state inserted between `ACTIVATING` and `ACTIVE`. `NodeDeploymentManager.performActivation` now publishes HTTP routes via consensus before flipping to `ACTIVE`; serial consensus ordering guarantees any node observing `ACTIVE` has already applied the routes entry. Slices without routes bypass ROUTING. Eliminates the race where a slice reported ACTIVE before its routes propagated cluster-wide, causing 404s on the first request
- **`ClusterFormationConfig`** — three configurable timeouts (`stabilizationWindow`, `postEstablishGrace`, `quorumLossHysteresis`; each 5s default) threaded through `AetherNodeConfig` builder and `NodeConfig` into `QuicClusterNetwork`. Replaces the two hardcoded `*_MS` constants
- **Compile-time management route registry** — 116-route `ManagementRoute` enum with `RouteMatcher` (O(1) hash lookup by method+prefix+paramCount), `RouteAssembler` (CLI path construction), `RouteTarget` sealed interface (LOCAL, ANY, TaskGroupTarget). All path string literals eliminated from 21 server-side route files and 37 CLI command files. Adding/renaming a route is a compiler error at every consumer
- **Task-group-aware management forwarding** — `TaskGroupAssignmentRegistry` seeds from KV-Store and maintains live `TaskGroup→NodeId` mapping via consensus notifications. LB `HttpForwarder.forwardManagement()` routes requests to the correct task-group owner. Enum-keyed dispatch in `ManagementRouter` bypasses legacy `RequestRouter` tree disambiguation
- **Node config composition** — 4-layer CLI-side config assembly: global default → per-source-type default → operator override (via `[source.X.node_config]` or template inheritance) → CLI bootstrap-time overlay. Node-side unchanged: still reads a single `aether.toml`. New `DefaultNodeConfig`, `NodeConfigComposer`, `BootstrapOverlayGenerator`. SSH path uses composed TOML via SCP. `UserDataTemplate` accepts composed document for cloud-init `write_files`. Follow-ups: #154 (Docker bootstrap), #155 (cloud provisioning wiring), #156 (Forge). Spec: `aether/docs/specs/node-config-composition-spec.md`
- **Server-side management forwarding** — every node's `ManagementServer` now owns an `HttpForwarder` and pre-routes incoming requests by `RouteTarget`: `LocalNode` → local; `TaskGroupTarget(g)` → forward when `ownerFor(g) != self`; `AnyCoreNode` → forward when local node is not in `coreNodes()`. Removes the need for clients to know which node owns a task group (REQ-6.4.1)
- **Cloud credential distribution** — `CloudCredentialsKey/Value` KV types with AES-GCM encryption. `BootstrapOrchestrator.storeCloudCredentials()` stores Hetzner token in cluster KV-Store during bootstrap, encrypted with `cluster_secret`. `HetznerComputeProvider` reads from KV-Store for auto-heal — token never on disk, in cloud-init, or in container env vars
- **Hetzner VM labels** — `CreateServerRequest` now includes `labels` field. `HetznerComputeProvider` and `BootstrapOrchestrator` apply `aether-cluster` and `aether-role` labels for lifecycle management and teardown
- **Private network support in bootstrap** — `BootstrapOrchestrator.buildCreateServerJson()` passes `networks` field. `DeploymentSpec.networkId()` configurable via TOML `deployment.network_id` with `${env:...}` interpolation
- **Cloud integration testing infrastructure** — `deploy-cloud.sh` (10-phase provisioning), `run-cloud-tests.sh`, `teardown-cloud.sh` for Hetzner Cloud. `CLOUD_MODE` flag in test library: SSH-via-bastion for `kill_node`/`start_node`, timeout multiplier, LB-routed API calls
- **Schema management helpers** — `schema_migrate`, `schema_retry`, `schema_history`, `schema_baseline`, `schema_undo` functions in integration test library
- **Blueprint publish endpoint** — `publish_blueprint` test helper for registering blueprints without deploying (required for v1→v2 strategy upgrade tests)
- **ManageableNode interface** — Extracted management API surface from AetherNode (~35 methods). `AetherNode extends ManageableNode`. All route sources + ManagementServer use `Supplier<ManageableNode>`. Enables passive nodes to serve management API
- **Passive LB ManagementServer** — `PassiveLBNode implements ManageableNode` with real KV-Store/topology/apply, no-ops for slice hosting. ManagementServer serves `/api/*` locally from LB's own synced state. `NoOpComponents` sealed interface with 13 stub implementations
- **PassiveNode.apply()** — Passive nodes can submit consensus proposals. Creates `Batch`, sends `NewBatch` to core nodes only (no traffic to other passive/worker nodes). Decision correlation resolves original promises
- **CTM auto-provisioning tags** — `buildProvisionTags()` builds 3-part PEERS from live topology using `NodeAddress.host()`. DockerComputeProvider provisions replacement containers with correct hostnames, API key, docker GID, unique names
- **DockerConfig enhancements** — `api_key`, `docker_gid` fields in `[cloud.compute]` config. Config-driven approach for production Docker deployments
- **`aether cluster bootstrap --cluster <name>` override flag** — CLI override for `[cluster].name` from the TOML; precedence is CLI > TOML > default. New `ClusterIdentity.withName(String)` and `ClusterBootstrapConfig.withClusterName(String)` copy methods. Validates against `^[a-z][a-z0-9-]{0,62}$`; invalid values exit with `ExitCode.USAGE` (64). Enables integration test harness to stamp per-suite cluster names without editing the TOML
- **`aether cluster destroy --keep-resources` flag** — debugging escape hatch that skips cloud resource termination; default behavior now terminates VMs via `BootstrapCleanup.cleanup(state)` before removing the registry entry. Previously destroy only drained nodes + removed the registry entry, leaking every provisioned VM (cost leak). Fixes the core teardown gap that made cloud testing unsafe
- **`exposeHostPorts` opt-in for `DockerComputeProvider`** — new `[cloud.compute] expose_host_ports` flag (default `false`, overlay-only). When enabled, CTM-provisioned containers publish their management port to the host via `-p {managementPortBase + nodeIndex}:8080`. Enabled in test config (`aether/docker/aether-node/aether.toml`) so integration tests on remote hosts can poll provisioned nodes directly. Production retains overlay-only behavior
- **Enriched `/api/cluster/topology`** — Returns live coreCount, connectedPeerCount, per-node details (nodeId, role, health, hostname, zone, address). Replaces stale `initialTopology()` with live `TopologyManager` data
- **`aether cluster topology` CLI** — Table output: NODE, ROLE, HEALTH, HOSTNAME, ZONE, ADDRESS columns
- **Chaos integration tests (02-chaos)** — 4 tests (19 assertions): kill non-leader, kill leader (re-election), kill multiple (quorum safety), kill under load (0% error rate through LB). Each verifies auto-heal restores cluster to target size
- **Scaling integration tests (03-scaling)** — 3 tests (16 assertions): quorum safety rejection, scale-up 5→7 (2s convergence), scale-down 7→5 under load (34s, 0% error rate). Cluster config seeding with initial-create fallback
- **Passive LB in docker-compose** — `aether-lb:local` Docker image built and deployed alongside cluster. All test traffic routes through LB. `deploy-compose.sh` builds both images
- **Initial cluster config seeding** — `POST /api/cluster/config` creates config when none exists (`.orElse()` fallback in `handleApplyConfig`)
- **Application config provisioning** — `@ResourceQualifier(type = ConfigurationSection.class)` pattern for typed config. Compile-time parser generation via `Result.all()`. Three-source merge (bundled `META-INF/config.toml` + `aether.toml` `[app.*]` + KV-Store). Runtime notification via single-threaded executor with record diff. ACTIVATE integration ensures config before routes
- **Config value object support** — Primitives, `Option<T>` variants, `List<String>`, core value objects (`TimeSpan`, `Url`, `Email`, `Uuid`, `NonBlankString`, `IsoDateTime`), and any user-defined type with `TypeName.typeName(String) → Result<T>` factory
- **Node metadata labels** — `NodeInfo.labels` (hostname, zone, instance-type, pool) propagated via Hello handshake, bootstrap from environment variables
- **PlacementHint provisioning** — `ZoneHint`, `HostGroupHint`, `AffinityHint`, `AntiAffinityHint` in `ProvisionSpec`. Cloud providers respect zone placement
- **Metadata-aware CTM scheduling** — Surplus comparator: spot-first → over-represented hosts → empty nodes → newest. Provisioning: zone-balanced placement based on current topology
- **ContentStore resource** — `@ContentStoreQualifier` annotation, `ContentStoreFactory` SPI for AHSE-backed content storage
- **Streaming retention enforcement** — Scheduled `RetentionEnforcer` removes expired segments from AHSE
- **Consumer cursor persistence** — `CursorStore` persists consumer group cursors in AHSE via named references
- **Governor failover** — `WatermarkTracker` + `GovernorFailoverHandler` for watermark-based replica catch-up from AHSE segments
- **Cross-tier stream reads** — `TieredStreamReader` with segment prefetch for optimized historical reads
- **Cloud certificate adapters** — AWS (Secrets Manager), GCP (Secret Manager), Azure (Key Vault) via `CertificateProvider` SPI with shared `CloudCertificateProvider`
- **Cloud provider placement** — AWS, GCP, Azure implement zone-aware provisioning from `ProvisionSpec.placement()`
- **Same-version deploy rejection** — Strategy deploys rejected when oldVersion == newVersion. `/api/blueprint/publish` for register-without-deploy
- **Disruption budget enforcement** — Drain endpoint checks quorum-based `minAvailable` before allowing DRAINING transition
- **Promise.allOrCancel()** — Cancels remaining promises on first failure; fixed instance `all()` from sequential to parallel
- **JBCT lint rules (4 new)** — `JBCT-PAT-03` blocking `.await()` detection (WARNING), `JBCT-RET-07` discarded `Result`/`Promise`/`Option` value (ERROR), `JBCT-STY-07` unnecessary intermediate variable before return (WARNING), `JBCT-STY-08` simple if/else with return in both branches (WARNING)
- **`@TerminalOperation` annotation** — Semantic suppression for `JBCT-PAT-03` on methods/classes where blocking is intentional (CLI, lifecycle, background threads)
- **Streaming read forwarding** — `ReadPreference.ANY_REPLICA`/`NEAREST` now routes reads to caught-up replica nodes via QUIC `ReadForward`/`ReadForwardResponse` protocol. Retry policy: primary fails → one alternate replica → error (never silent fallback to leader). `StreamReadRouter`, `RawEventDto`, `StreamReadForwardMetrics` (5 counters). Configurable split timeouts via `[streaming]` config section (`publish_forward_timeout`, `read_forward_timeout`). Defensive 28MB response cap with truncation flag. REST layer (`StreamRoutes.readEvents`) now honors the preference end-to-end
- **`ConsumerRuntimeState` async cursor loading** — eliminated blocking `.await()` in `subscribe()` by deferring cursor load to an async path; consumer starts after cursor resolves
- **jOOQ XML schema export** — `JooqXmlExporter` generates jOOQ `XMLDatabase`-compatible XML from pg-tools' static `Schema` model. No jOOQ dependency. Covers tables, columns, PK/FK/unique/check constraints, sequences, indexes, enums, domains, identity/generated columns, multi-schema. Two Maven goals: `export-jooq-xml` (generate) and `check-jooq-xml` (CI drift detection). `JooqTypeMapper` maps 25+ PostgreSQL types to jOOQ's information_schema conventions

- **Bootstrap phase extraction** — `ClusterBootstrapOrchestrator` refactored from 627-line monolith into 6 focused phase files (`BootstrapPhaseValidate`, `BootstrapPhaseProvision`, `BootstrapPhaseCollect`, `BootstrapPhaseDeploy`, `BootstrapPhaseFormation`, `BootstrapPhasePost`) plus thin orchestration skeleton
- **Pre-flight validation** — `ClusterBootstrapConfigValidator` wired into Phase 1 with warning emission. `PreflightChecker` runs cloud credential pings by default; `--full-check` flag enables SSH reachability, Docker CLI, and floating IP ownership checks in parallel per source
- **Bootstrap state persistence** — `BootstrapState` extended with `CreatedResource` tracking (VMs, firewall rules, floating IPs, containers, SSH configs), JSON serialization, file persistence to `~/.aether/clusters/<name>/bootstrap-state.json`. SHA-256 config hash. Resume from last completed phase with `--resume` flag. LIFO cleanup of all tracked resources on failure via `BootstrapCleanup`
- **Bootstrap Phase 2 enhancements** — VM tagging with `aether-cluster`/`aether-source`/`aether-role` labels. All provisioned resources tracked in state for cleanup
- **Parallel health checks** — Phase 5 polls ALL node addresses concurrently via `Promise.allOf()` instead of sequential single-node polling. Required for clusters with 50+ nodes
- **Dual KV-Store config entries** — `ClusterConfigKey.TEMPLATE` (configVersion=-1) stores original TOML with `${...}` placeholders intact for export roundtrip; `ClusterConfigKey.CURRENT` stores CLI-resolved config
- **API key file persistence** — Phase 5 saves API key to `~/.aether/clusters/<name>/api-key` with `0600` permissions
- **Floating IP attachment** — Phase 6 resolves `FloatingIpProvider` for elected LB sources and calls `attach()` for each configured floating IP
- **Forge health gate** — Phase 4 verifies forge process is reachable before proceeding to cluster formation (10s timeout with actionable error)
- **Node ID peer list fix** — `NodeConfigTemplate.buildPeersList()` uses real provisioned node IDs instead of generating sequential `clusterName-N` IDs
- **Full apply orchestrator** — `ApplyOrchestrator` with pre-flight cluster health check, terraform-style plan confirmation (`--yes` to skip), `ApplyState` persistence with `--resume`/`--rollback` support
- **Rolling restart** — `WaveExecutor` executes `RuntimeChange` via drain → destroy → provision → wait-for-ready, respecting `maxUnavailable` budget for core nodes. Workers restarted in parallel
- **Replace-before-retire** — `SourceFieldChange` provisions new nodes first, waits for cluster join, then drains and destroys old nodes
- **SSH drain** — SSH source removals drain via management API then `docker stop` via SSH. Hosts are preserved (not destroyed)
- **API key rotation** — `aether cluster rotate-key [--grace-period 5m]` generates new key, pushes to KV-Store, marks old key REVOKED with configurable grace period, updates local key file
- **API key revocation** — `aether cluster revoke-key <keyId> [--immediate]` revokes by ID with optional immediate effect
- **API key listing** — `aether cluster list-keys [--audit]` shows all keys with status; `--audit` includes full operation history
- **Multi-key auth** — `KvStoreApiKeyValidator` supports multiple concurrent ACTIVE keys with grace period for revoked keys. Enables zero-downtime rotation
- **API key audit trail** — All key operations (create, rotate, revoke, expire) logged in KV-Store as `ApiKeyAuditValue` entries
- **API key expiration sweep** — Periodic background task on leader (60s interval) marks expired keys

- **`tools/cloud-reaper.sh`** — standalone Hetzner cloud-resource reaper (kill-switch independent of bootstrap state). Lists every `aether-cluster`-labeled resource (servers, floating IPs, networks, firewalls, SSH keys) via `label_selector` API queries; default dry-run, `--destroy` flag deletes in correct order (servers → FIPs → firewalls → networks → SSH keys), `--cluster <name>` filters to a specific cluster, `--force` skips the 5s confirmation grace for CI use. Idempotent; exits non-zero on any deletion failure or remaining resources after destroy
- **`tools/provision-test-pg.sh`** — idempotent PostgreSQL test-VM provisioner for Hetzner. Creates a single labeled cx23 VM running PostgreSQL in Docker (`aether-cluster=test-pg`), uploads operator SSH key, runs a connectivity smoke test, and writes `PG_URL` to `/tmp/aether-test-pg.env` (mode 0600). `--print-only` recovers the URL when env file missing; `--destroy` nukes it. Used as the shared Forge backing store for all cloud bootstrap iterations, so cluster VMs can share state across teardowns
- **`aether cluster bootstrap --keep-on-failure` flag** — symmetric with `cluster destroy --keep-resources`. When set, a failed bootstrap skips automatic cleanup, leaving provisioned VMs and SSH keys in place for SSH-based diagnosis. Prints remediation hint with the kept-resource counts and a follow-up `aether cluster destroy` command. Critical for iterating on cloud bootstrap without burning Hetzner spend on every failed attempt
- **`aether cluster bootstrap --ssh-public-key <path>` flag** — explicit operator-public-key override for cloud-init injection. Resolution priority: CLI flag > TOML `[infrastructure.ssh] public_key_files` > `${AETHER_SSH_KEY}.pub` sibling. Cloud sources fail fast with a remediation message naming all three paths if no key resolves
- **`[infrastructure.ssh] public_key_files` TOML schema** — operator can declare one or more SSH public-key paths in the bootstrap config; `SshKeyResolver` reads them at Phase 2 and uploads to Hetzner via the SDK (reusing existing keys by fingerprint). Tracked as `SshKeyResource` in `BootstrapState` for cleanup
- **`[runtime.X] jar_url` TOML override** — pin the JVM-mode JAR URL when the auto-derived `https://github.com/pragmaticalabs/pragmatica/releases/download/v${version}{-candidate?}/aether-node.jar` is unsuitable (e.g. mirrors, prereleases without a stable tag). `RuntimeProfile.jarUrl()` accessor; `UserDataTemplate.resolveJarUrl(profile, version)` applies the override when present
- **JVM-mode cloud-bootstrap path** — `aether cluster bootstrap` against a `[runtime.default] type = "jvm"` source now provisions Hetzner VMs that install Eclipse Temurin 25 from Adoptium's apt repo, download the published `aether-node.jar`, and start it via `nohup java -jar … & disown` with per-node CLI args (`--node-id=`, `--port=`, `--management-port=`, `--peers=`, `--config=/opt/aether/config/aether.toml`). `BootstrapPhaseDeploy` is runtime-aware: cloud `DEPLOY_RUNTIME` SSHes each node and either (`container`) `docker rm -f && docker run -d` or (`jvm`) `pkill -f '^java -jar /opt/aether/aether-node.jar' && nohup java -jar … & disown` to inject the finalized PEERS list. Validated end-to-end on Hetzner with `aether/tests/integration/env/cloud-hetzner-jvm.toml`
- **Cloud SSH preflight in `BootstrapPhaseDeploy`** — before docker/JVM restart, polls each cluster VM with `ssh ... 'cloud-init status --wait'` (180s budget, 5s interval, removes successful hosts each iteration). Guarantees Docker is installed and the cloud-init initial container/JVM has run before the SSH-back command fires, eliminating the `bash: docker: command not found` race seen on slow VMs
- **`aether/tests/integration/env/cloud-hetzner-jvm.toml`** — JVM-mode test config matching the validated container path's `cloud-hetzner.toml`. Pins the `v1.0.0-rc1-candidate` JAR URL and disables mgmt API TLS for plain-HTTP health-check compatibility
- **`--cluster <name>` override on 17 cluster subcommands** — `ClusterTargetMixin` (Picocli `@Mixin`, ~50 LoC) extends the bootstrap-only `--cluster` flag to the full management surface: `status`, `topology`, `generation`, `tasks`, `await-quiesced`, `export`, `list-keys`, `apply`, `scale`, `drain`, `upgrade`, `migrate`, `create-key`, `revoke-key`, `rotate-key`, `destroy`, plus the existing `bootstrap`. Resolves the named cluster via `ClusterRegistry.entryFor(name)` → `ClusterHttpClient.setEndpointOverride`, reads its API key from `~/.aether/clusters/<name>/api-key` → `ClusterHttpClient.setApiKeyOverride`. Fail-fast on invalid name (regex `^[a-z][a-z0-9-]{0,62}$`) or missing registry entry. Eliminates per-command boilerplate; enables multi-cluster operator workflows without per-shell `aether use <name>`. Sealed `ClusterTargetMixin.ClusterTargetError` covers `InvalidClusterName`, `UnknownCluster`, `RegistryUnavailable`, `ApiKeyMissing`, `ApiKeyEmpty`, `ApiKeyReadFailed`
- **`aether cluster init` interactive wizard** — guided cluster bootstrap config generator. `--batch` for non-interactive mode (consumes a JSON spec via `--input`/stdin); `--output <path>` writes the generated TOML; `--format json|table|value|csv` for inspection. Picks runtime (`container`/`jvm`/`forge`/`docker`), source profile (`hetzner`/`aws`/`gcp`/`azure`/`ssh`/`docker`), node count, zone hint, image/JAR pin, SSH key paths, mTLS toggle, custom port assignments. Validates each section against the same parsers used by `aether cluster bootstrap`. See PR #173, #203
- **RBAC Tier 2 — three-role authorization model** — three hierarchical roles (`ADMIN`/`OPERATOR`/`VIEWER`) with per-route enforcement in the management API pipeline. `RoutePermissionRegistry` resolves required role by HTTP method + path prefix. 403 `Forbidden` for authorization failures. New `authorization_role` field on API keys (defaults to **`VIEWER`** — secure-by-default; was `ADMIN` in MVP draft). Existing routes annotated across all 40+ mutation endpoints; independent security audit passed clean. PR #202
- **JBCT-VO-02 lint rule recognizes parse + construct factory pattern** — value-object factory rule now accepts the canonical `parse(String) → Result<T>` + private `construct(...) → T` decomposition (and `tryConstruct` variant). Eliminated 47 `@SuppressWarnings("JBCT-VO-02")` occurrences across the codebase. PR #201
- **`@NullReturn` annotation** — JBCT-RET-03 escape hatch for legacy/Java-API methods that genuinely return `null` (Map.put, ConcurrentMap.compute callbacks, JDK collection APIs). Semantic suppression replaces ad-hoc `@SuppressWarnings("JBCT-RET-03")`. PR #192
- **`notification-emailer` slice example** — coverage example demonstrating `@Notify` resource (PostgreSQL LISTEN/NOTIFY) for slice-to-slice fan-out. Pairs with the `url-shortener-v2` example to exercise the `@Notify` codegen path. PR #195

### Removed
- **`AETHER_INSECURE_DEV_MODE` env var and dev-mode QUIC TLS paths** — `QuicTlsProvider.createDevClient`, `createInsecureClient`, `createSelfSignedServer` deleted. Every node now requires a resolved `TlsConfig` for QUIC cluster transport; `AETHER_CLUSTER_SECRET` becomes the single source of deterministic CA material. `DockerComposeGenerator` and `DockerComputeProvider` stopped emitting/propagating the flag. Also removed four `RabiaNode.rabiaNode` convenience overloads that silently supplied `Option.empty()`. **BREAKING** for anyone running nodes without cluster_secret
- **Legacy integration-test orchestrator scripts** — `deploy-compose.sh`, `deploy-cloud.sh`, `run-all.sh`, `run-suite.sh`, `setup.sh` deleted. Superseded by single `run-tests.sh` dual-cluster runner that handles `--env docker|remote|cloud` provisioning, suite execution, and teardown. README, architecture docs, `build-and-push.sh`, and cloud test harness updated accordingly

### Changed
- **PG VM Hetzner-firewalled, 5432 toggled per test run** — new `tools/pg-firewall.sh` (`init|open|close|status|destroy` subcommands) creates a Hetzner Cloud Firewall named `aether-pg-firewall` and applies it to the PG VM (resolved via `aether-cluster=test-pg` label). Baseline rules: 22/tcp from operator IP only; everything else implicitly denied. `open` adds 5432/tcp from `0.0.0.0/0` for the duration of an integration test run; `close` reverts to baseline. `aether/tests/integration/run-tests.sh` calls `open` once `--env cloud` is selected (right after the EXIT trap is installed) and `close` from `teardown()` after `cloud-reaper.sh`. PG remains invisible to the public internet on port 5432 outside the test window. Operator IP auto-detected via `ifconfig.me` on each `init`/`open`/`close` (override with `OPERATOR_IPS=<cidr>[,<cidr>...]`). Firewall is created/applied once via `pg-firewall.sh init`; subsequent `init` runs refresh the operator-IP rule after roaming networks
- **`build-linux-arm64-dist` runs on a native ARM runner (closes #211)** — `release.yml` switched from `ubuntu-latest` + `docker/setup-qemu-action` + `docker run --platform linux/arm64 azul/zulu-openjdk:25 …` to `runs-on: ubuntu-24.04-arm` with the standard `actions/setup-java@v4` + native Maven invocation. Drops the entire QEMU emulation layer. Job wall-clock collapsed from ~30–45 min (QEMU) to ~1m50s (native). Total release publish wall-clock is now ~5m30s end-to-end (build-and-release 2m → arm64+macos+docker-publish in parallel ~3m)
- **`12-network` capability gate enabled on cloud env** — `lib/suite.sh:detect_capabilities` now sets `CAP_NETWORK_PARTITION=true` for `docker|remote|cloud` (was `docker|remote`), unblocked by the local-SWIM-observation event-emission fix above
- **Membership architecture redesign (R1–R10)** — 10-phase rewrite of the cluster membership / consensus / leader-election / health-detection layers per [`aether/docs/specs/membership-architecture-spec.md`](aether/docs/specs/membership-architecture-spec.md). Eight architectural layers with one-way signal flow (Transport → SWIM → HealthReconciler → TopologyObserver → Rabia → Leader Election → CTM → Node Lifecycle FSM). Phases: **R1** Rabia gains durable `Paused` state retaining proposal log across transient quorum loss + explicit `reconfigure(ClusterConfig)`; **R2** SWIM emits canonical `SwimObservation` stream with cold-boot FAULTY suppression (`everSeenHealthy`) and transport-hint biased suspect window; **R3** new `HealthReconciler` is the sole writer of `NodeLifecycleKey`, owns `ClusterPhaseKey` (BOOTING/NORMAL/RECOVERING) state machine; **R4** `TopologyObserver` reduced to pure read-only KV projection; **R5** transport narrowed to emit only `TransportObservation`, `TopologyObserver` mutation API deleted; **R6** leader-election rank-staircase first-tick + always-listen KV poll across all FSM states; **R7** CTM phase-aware (suspended in BOOTING/RECOVERING) + `LifecycleWriter` SPI routes drain/decommission through HealthReconciler; **R8** new per-node `NodeLifecycle` FSM (STARTING→JOINING→ACTIVE→DRAINING→STOPPED) backs `/health/live` + `/health/ready`; single-writer rule for `NodeLifecycleKey` enforced; **R9** `aether status` exposes `clusterPhase` + per-node `lifecycleState` + `cluster.quorate`; new test helpers `wait_for_phase` / `wait_for_quorum` / `wait_for_node_lifecycle`; **R10** cleanup, JBCT lint pass, `LeaderManagerTest` stabilized via Awaitility, `ClusterTopologyManagerRecord.legacyLifecycleWriter` deleted. Two follow-up JBCT review rounds tightened thread-safety, null-policy, and value-object hygiene
- **Leader election rewritten as explicit state machine** — `LeaderManager` now delegates to a new `LeaderElectionFsm` backed by `integrations/statemachine`. Seven explicit states (`DORMANT → QUORUM_WAITING → ELECTING → LED → RE_ELECTING → QUORUM_LOST → STOPPED`) with declarative transition table, guards, and entry/exit actions replace the previous nine-atomic ad-hoc state. Silent early-returns are gone — every (state, event) either matches a transition or logs "ignored in state X". Key correctness fixes: (1) `LeaderCommitted(L)` where `L ∉ currentTopology` is now rejected with a WARN, eliminating the stale-commit replay that could re-install a dead leader and block re-election indefinitely; (2) `NodeRemoved`/`NodeDown` unified as `NodeGone` internally, removing the path-dependent guard that caused re-election to bail when the leader was already cleared; (3) in-flight proposals bounded by a timeout (`max(3×retryDelay, 5s)`) so a hung `propose()` Promise can no longer leave `proposalInFlight` stuck true forever; (4) `stuckElectionCount` relaxes the candidate pool to raw topology after N failed attempts, handling the degraded case where `expectedCluster` drifted; (5) `triggerElection()` arriving before `QuorumEstablished` is buffered and replayed on entry to `QUORUM_WAITING` rather than silently dropped. Single-thread dispatcher (daemon, per-node) serializes event processing; timers and proposal callbacks fire-and-forget onto `SharedScheduler`. New `LeaderManager.stop()` wired into `RabiaNode.stop()` chain. `integrations/statemachine` extended with `Builder.onEntry(state, action)` / `onExit(state, action)` and `InMemoryStateMachine.executeTransition` now runs `exit(from) → transition.action → entry(to)` (skipped for self-transitions). Follow-up cleanup #188 (rc2): collapse `NodeRemoved`/`NodeDown` at the notification layer as well
- **`QuicClusterNetwork` per-peer state machine** — introduced `PeerState` owning the full per-peer connection lifecycle (`INIT → CONNECTING → CONNECTED ⇄ EVICTED → REMOVED`) with explicit `offerOutbound`/`attach`/`evict`/`authoritativeRemove` transitions and a separate 10k-entry offline buffer. Collapsed five previously-parallel structures (`peerLinks`, `connectingInProgress`, `passivePeers`, `connectionEstablishedAt`, plus the broadcast queue-on-evict previously conflated with Netty writability backpressure) into a single `Map<NodeId, PeerState>`. `outboundQueues` retained only for channel-level writability backpressure. Eliminates whole classes of race conditions (duplicate connection attempts, ordering dependencies in `disconnect`, queue preservation vs. drop during transient evictions). Contributes to 6–12× speedups on Cluster A integration suites (04-streaming 310s → 46s, 08-resources 370s → 57s, 09-artifacts 218s → 18s) and recovery of 15-delegation from 0/2 to 2/2. 21 unit tests. See #185. Supersedes the reverted broadcast queue-on-evict attempt (cb8ee3952) which reused the 100-entry Netty backpressure queue as the wrong primitive
- **Integration test harness simplified via ClusterGeneration barrier** — new `aether/tests/integration/lib/generation.sh` exposes `await_generation_quiesced` / `generation_current` / `generation_quiesce_now` over `POST /api/cluster/await-quiesced`, preferring the `aether cluster await-quiesced` CLI over raw curl. Deleted: `self_heal` 3-step recovery + 4 chaos-suite call sites, `restore_baseline`, 4-iteration retry loops in `deploy_blueprint` / `publish_blueprint` / `deploy_start`, 5-iteration retry in `deploy_blueprints`, `tolerate-already-in-state` branch in canary complete, test-side 5..7 overprovision tolerance in `test-kill-node`, the drain-reset / rescale-fallback in `test-disruption-budget` and `test-stale-route-cleanup`. Propagation-race `sleep N` calls replaced with epoch barriers; legitimate chaos-timing sleeps (failure-detection windows after `kill_node`) kept. Timing instrumentation added: provisioning, cluster formation, blueprint deploy, per-suite quiesce-barrier duration, per-test duration all printed in the final summary. New `aether/tests/integration/README.md` covers prerequisites, env setup, building, running, suite selection, results format, troubleshooting, and adding tests (#174). See [`aether/docs/specs/cluster-generation-spec.md`](aether/docs/specs/cluster-generation-spec.md) §13.3
- **Aether relicensed to BSL 1.1** — `aether/**`, `jbct/slice-processor/`, and `jbct/slice-processor-tests/` carry per-file SPDX `BUSL-1.1` headers with `Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko`, `Change Date: 2030-01-01`, `Change License: Apache-2.0`. Root `LICENSE` turned into a monorepo routing document; Apache-2.0 text preserved in `LICENSE-APACHE-2.0`. `core/`, `integrations/`, and the rest of `jbct/` remain Apache-2.0. Canonical header template at `docs/legal/bsl-header.txt`, bulk applicator at `tools/license/apply-bsl.sh`. See issue #162 for physical relocation of the BSL-licensed slice-processor modules under `aether/` (deferred)
- **QUIC TLS always required** — `AetherNodeConfig.quicTls: TlsConfig` is now a mandatory builder field, independent of `cluster.tls` (which still gates HTTP TLS). `Main.java` always resolves a deterministic `TlsBundle` from `cluster_secret` / `AETHER_CLUSTER_SECRET` and fails startup if neither is set. `QuicSslContextFactory.createServer/createClient/createServerFromBundle/createClientFromBundle` accept ALPN `applicationProtocols` varargs; `QuicTlsProvider` wires the `"aether-cluster/1"` ALPN through for every handshake. Cluster transport now uses a shared CA — no more ephemeral-per-restart certs
- **HttpForwardResponse demuxed by `Pipeline`** — `AetherNode` now routes `HttpForwardResponse` to `ManagementServer.onHttpForwardResponse` for `Pipeline.MANAGEMENT` and to `AppHttpServer` otherwise. Fixes mgmt-forwarded requests timing out because responses were landing in the app forwarder's `pendingForwards` map (classic 13-edge-cases drain 503)
- **Composite API-key validator honors config-based credentials** — `KvStoreApiKeyValidator.validateApiKey` falls back to bypass only when BOTH config-based keys AND KV-store keys are absent. Previously an empty KV-store erroneously let unauthenticated requests through even when `appHttp.apiKeys` was configured (mgmt API returned 200 where it should have returned 401/403). `SecurityValidator.hasConfiguredCredentials()` default + `ApiKeySecurityValidator` override make the check composable
- **API-key KV iteration now type-safe** — `KvStoreApiKeyValidator` and `ApiKeyRoutes` (list / audit / sweep) migrated from `kvStore.snapshot().entrySet()` + `.asString()` filtering to `kvStore.forEach(ApiKeyKey.class, ApiKeyValue.class, …)`. Fixes `ClassCastException: LeaderKey cannot be cast to AetherKey` when the store contains non-`AetherKey` entries
- **Forward timeout config consolidation** — `AppHttpConfig.forwardTimeout` removed; HTTP forward timeouts now live in `[timeouts.forwarding]` as `app_timeout` (default 5s) and `management_timeout` (default 5s, used by management forwarding). `ForwardingTimeouts` record extended; `AppHttpConfig` factory overloads collapsed
- **jOOQ version bump** — 3.20.10/3.20.11 → 3.21.1 across root, integrations/db, and aether/resource (fixes version drift)
- **Cluster bootstrap spec** — node-group-centric configuration model with named source/runtime profiles, multi-zone via multi-source, template inheritance (`[template.X]`), elected floating-IP load balancer, deferred database URL resolution, `config_version` field, firewall rules with TCP/UDP protocol support, three node roles (`core`/`worker`/`spot`)
- **Tier 1 cluster-sync rename** — `MetricsMessage` → `ClusterSyncMessage`, `MetricsPing/Pong` → `ClusterSyncPing/Pong`, `MetricsCollector` → `ClusterSyncCollector`, `MetricsScheduler` → `ClusterSyncScheduler`, plus factory/method/test renames. Pure rename — zero behavior change. `ENVELOPE_FORMAT_VERSION` bumped (1000 → 1001) because the deterministic codec tag keys on FQCN. App-level metrics collectors (`InvocationMetricsCollector`, `DeploymentMetricsCollector`, `ArtifactMetricsCollector`, `EventLoopMetricsCollector`, `RabiaMetricsCollector`, `GCMetricsCollector`), `MetricsRoutes`, `DashboardMetricsPublisher`, and Tier-2 `WorkerMetrics*` types are untouched. Tier-2 rename tracked in issue #178. See `aether/docs/specs/clustersync-refactor-spec.md` commit 0
- **Cloud bootstrap pipeline activated end-to-end** — `BootstrapPhaseProvision.provisionCloudSource` now renders per-node `UserDataTemplate` output (previously dead code) and threads it through `ProvisionSpec.userData()` per VM. `BootstrapPhaseDeploy.deployCloudSource` is no longer a no-op: it runs the SSH preflight, restarts the runtime (container or JVM) with the finalized 3-part PEERS list, and health-polls `/health/live` per node. `BootstrapPhaseValidate` generates `clusterSecret` once at validate-time and persists it through `BootstrapState` so it's available to all downstream phases. The composed `aether.toml` is bind-mounted over the image's bundled `/app/aether.toml` (container) or `--config=`-pointed (JVM) so operator config wins. Per-node identity (`NODE_ID`, `CLUSTER_PORT`, `MANAGEMENT_PORT`, `PEERS`, `AETHER_CLUSTER_SECRET`) is delivered as env vars/CLI flags rather than TOML, matching `Main.java`'s schema. `BootstrapResult.endpoint` includes the management port (`http://<ip>:8080`) so `--wait` polling actually reaches the API. `--wait` polls `/api/cluster/status` for `state == "CONVERGED"` (was the wrong route+field). End-to-end validation: both `cloud-hetzner.toml` (container, image from `RuntimeProfile.image()`) and `cloud-hetzner-jvm.toml` (JVM, Temurin 25 + JAR from GitHub releases) reach `Cluster is healthy.` with leader elected and all task groups distributed
- **`BootstrapOverlayGenerator` schema** — `[cluster]` block now emits `tls = config.operations().tls().autoGenerate()` (operator-driven mTLS toggle for mgmt API; was inheriting default `true`). `[cluster.ports]` (was `[cluster].ports` placeholder) carries operator-specified `management` and `cluster` ports. `[node]` and `[cluster].peers` blocks dropped — Main reads neither; per-node identity is env-driven now. Database section flattened to `[database.<name>]` with auto-detected `async_url`/`jdbc_url` field name (was `[database.forge].async_url` only)

### Changed
- **`HETZNER_API_TOKEN` → `HCLOUD_TOKEN`** — Standardized to Hetzner's official env var name across all Java code, docs, and specs
- **`leader` → `active` rename** — `DeploymentManagerImpl` and `AbTestManager` field/method rename (`requireLeader()` → `requireActive()`), `DeploymentError.NOT_LEADER` → `NOT_ASSIGNED` with task-group-aware message
- **Path rearrangement** — All management API paths follow "params at tail" convention (`/api/deploy/{id}/promote` → `/api/deploy/promote/{id}`). Breaking wire-protocol change, acceptable per RC1 status
- **QUIC frame/data limits** — Bumped from 1MB/4MB/16MB to 32MB/32MB/64MB for frame length, stream data, and connection data. Enables large artifact forwarding through LB management pipeline
- **Disruption budget** — `checkDisruptionBudget()` now counts any non-ON_DUTY node state (DRAINING, DECOMMISSIONED, SHUTDOWN) as consuming a budget slot, not just DRAINING
- **Management API forwarding** — LB forwards all management API requests to core nodes via QUIC binary protocol (Pipeline.MANAGEMENT). Eliminates NoOp stubs, PassiveLBNode, and local handling. Endpoints that previously returned 500 or hung (artifacts, schema, drain, storage) now work correctly through the LB
- **TopologyManager.coreNodes()** — Maintained `Set<NodeId>` of non-passive nodes for O(1) core node lookup. Used by HttpForwarder for management pipeline node selection
- **PassiveNode simplified** — Removed `apply()` method and correlation map. KV-Store sync via decisions continues; consensus proposals no longer needed from passive nodes
- **Dashboard** — Fixed empty panels (strategies store endpoints, template fields). Added 10s secondary polling for topology/governors/strategies/streams/observability. Fixed success rate display
- **StreamAccessImpl → PartitionedStreamAccess** — JBCT naming compliance, removed Impl suffix
- **Example scripts** — `run-forge.sh` scripts now extract version from POM dynamically instead of hardcoding
- **JBCT-RET-07 rule refinements** — removed `onPresent` (Option side-effect, no error channel) and `timeout` (scheduling side-effect) from chain-terminal set; added string-literal stripping to prevent false positives on code-generation string content; added top-level assignment detection to exclude explicitly-typed local declarations
- **Naming consistency: Pragmatica Lite → Pragmatica Core; Aether → Unified Application Runtime** — terminology pass across docs, READMEs, in-source documentation, and user-facing CLI text. Library identity is "Pragmatica Core" (was "Pragmatica Lite"); Aether is consistently described as "Unified Application Runtime" in long-form references. Module/package/artifact names unchanged. PR #129
- **Grammar generation switched to `peglib-maven-plugin` (0.2.2 → 0.6.0)** — both PostgreSQL (`aether/pg-tools/pg-parser`) and Java 25 (`jbct/jbct-parser`) parsers now regenerate via the `org.pragmatica-lite:peglib-maven-plugin` 0.6.0. `peglib` itself bumped 0.2.2 → 0.6.0. The old custom generators (`GenerateStandaloneParser.java` test-class, `jbct/scripts/generate-parser.sh`) deleted. Regeneration command: `mvn -Pgenerate-parser -pl aether/pg-tools/pg-parser,jbct/jbct-parser generate-sources`. Generated parser sizes shrank ~13% (PgSqlParser) and ~8% (Java25Parser) under 0.6.0's architectural improvements (flat int[] CST layout, selective packrat, choice dispatch — 8.55× faster on reference Java25 corpus). The 0.6.0 generator produces a slightly different CST trivia profile that flowed through `jbct-format`'s rules, reformatting 26 source files across `aether-config`/`aether-deployment`/`cli`/`environment*`/`node` modules. Two new lint findings surfaced (`JBCT-RET-01`, `JBCT-RET-03`) — pre-existing violations that 0.5.0's CST missed and 0.6.0 correctly detected; fixed `BootstrapStateJson.parseSourceCleanupHandle` (return `Option<T>` instead of `null`) and suppressed at `ManageableNode.requestGenerationSnapshotRefresh` (fire-and-forget signal). Both downstream reactors compile and pass tests

### Fixed
- **`CoreSwimHealthDetector.addObservationListener` lost listeners registered before SWIM started** — the previous body `protocol().onPresent(p -> p.addObservationListener(consumer))` silently dropped listeners when `protocol()` was empty (which is the case at AetherNode init time, before `QuorumStateNotification` arrives and triggers SWIM startup). Both `healthReconciler::onSwimObservation` and `eventAggregator::onSwimObservation` were attached at init, so both vanished. `healthReconciler` happened to also receive faults via the legacy `SwimHealthListener.onMemberFaulty` callback path, so health-driven recovery kept working — but `eventAggregator` had no fallback channel, so on cloud the events ring buffer never saw `NODE_FAILED`/`NODE_LEFT` after a kill (12-network suite). Fix: `addObservationListener` now buffers consumers in a `pendingObservationListeners` list (CoW); `seedAndWrap` re-attaches every pending listener to each freshly-started `SwimProtocol`, so registrations made during AetherNode init survive across SWIM start/restart cycles. Pre-existing `protocol().onPresent` attach is retained for runtime registrations during a Running state
- **`NODE_FAILED`/`NODE_LEFT` events emitted from local SWIM observation** — every node now records what it witnessed via SWIM, eliminating the leader bottleneck. `ClusterEventAggregator` lost `onNodeRemoved`/`onNodeDown` (both gated on the leader-only `TopologyChangeNotification` broadcast); new `onSwimObservation(SwimObservation)` emits `NODE_FAILED` on `FaultyObserved` and `NODE_LEFT` on `DepartedObserved`. Wired in `AetherNode` alongside the existing `healthReconciler` SWIM listener. Lifecycle KV writes (DRAINING/DECOMMISSIONED) still emit via `onNodeLifecyclePut` (KV-replicated, every node sees them). Operator drain and SWIM-detected failure now flow through separate, idempotent paths with no single point of failure for observability
- **Runtime cloud auto-scale wired end-to-end** — `BootstrapOverlayGenerator.cloudComputeSection` was emitting `provider` to `[cloud.compute]` (wrong section). `ConfigLoader.populateCloudConfig` reads from `[cloud]`, so on Hetzner/AWS/GCP/Azure clusters `lifecycleManager.isCloudManaged()` returned false and `/api/cluster/scale` requests logged `"no ComputeProvider, cannot auto-provision"` and silently no-op'd. New `cloudSection` + `cloudCredentialsSection` helpers emit `[cloud] provider = "..."` and `[cloud.credentials] api_token = "..."` for cloud-type sources. `cloudComputeSection` reworked: dropped misplaced `provider`, added `server_type` from CORE role's `instance_type`. 03-scaling now PASS on Hetzner. Operator-facing docs added: `docs/reference/cloud-integration.md` § Credential Propagation to Nodes, `docs/operators/runbooks/scaling.md` § Cloud Auto-Scaling, `docs/specs/cluster-bootstrap-spec.md` REQ-4.2.7
- **Manifest-driven version strings** — `aether --version` reported a hardcoded `"Aether 1.0.0-alpha"` even on `release-1.0.0-rc1`; `AetherNode.VERSION` was hardcoded `"1.0.0-rc1"`. New `BuildInfo` class in `aether-config` reads `Implementation-Version` + `Implementation-Build-Date` from the executable jar's MANIFEST.MF; new `AetherVersionProvider` (picocli `IVersionProvider`); POMs configure `maven-jar-plugin` and `maven-shade-plugin`'s `ManifestResourceTransformer` to inject the entries from `${project.version}` + `${maven.build.timestamp}`. `aether --version` now reports `Aether <project.version> (built <ISO-timestamp>)`. Falls back to `dev`/`unknown` on IDE classpath
- **Bootstrap formation auth chicken-and-egg** — `ClusterBootstrapOrchestrator.httpPost` → `ClusterHttpClient.postDirect` did not attach `X-API-Key`. On clusters where `[app-http] security_mode = "API_KEY"` the formation POSTs to `/api/cluster/config` and `/api/cluster/keys` got HTTP 401. New `postDirect(url, body, Option<String> apiKey)` overload; `BootstrapPhaseFormation.extractConfiguredApiKey` reads rich-syntax `[source.X.node_config.app-http.api-keys.<key>] authorization_role = "ADMIN"` (preferred) or simple `api_keys = [...]` list (fallback, VIEWER role yields HTTP 403). Cluster B integration test fixture updated to rich syntax with explicit ADMIN role
- **`BootstrapCleanup` NumberFormatException on cloud cleanup** — `BootstrapPhaseProvision` constructed `CreatedResource.ProvisionedVm` with `node.nodeId()` (e.g. `hetzner-eu-core-0`) as `resourceId`. On cleanup, `BootstrapCleanup.terminateInstance` passed that string to `HetznerComputeProvider.terminate` which expects a numeric Long → exception on every failed-bootstrap cleanup; reaper backstop saved us. Fixed by passing `node.serverId()` (the actual provider instance ID)
- **Cloud-aware integration test helpers** — `test-01-quorum-safety.sh:direct_scale_status` was Docker-only (port-hopping `${TARGET_HOST}`) — on cloud all attempts returned `status: 000`, 3p/3f. Now hits the leader directly via `cloud_public_ip` on cloud. `test-disruption-budget.sh` hardcoded `node-5/node-4/node-3` (Docker convention) — new `to_node_id` helper in `lib/common.sh` translates docker→cloud forms (`node-N` → `${CLOUD_SOURCE_NAME}-core-$((N-1))`). `wait_for_node_count_fast` falls back to slow-poll on cloud. `test-cert-rotation.sh` skips error-rate assertion when `renewalStatus = NOT_CONFIGURED` (no rotation possible on TLS-disabled cluster — proper coverage tracked in #209). 03-scaling now 3p/0f, 13-edge-cases drain test 6p/0f, 05-security 3p/0f on Hetzner


- **`aether cluster destroy --cluster <name>` flag** — symmetric with `cluster bootstrap --cluster <name>`. Resolves the named cluster via `ClusterRegistry.entryFor(name)` and operates on its bootstrap-state file regardless of which cluster is currently active. Without it, destroying a non-active cluster required `aether use <name>` first (and the integration-test cloud teardown was a swallowed no-op for the inactive A/B cluster). For arbitrary or unregistered clusters, `tools/cloud-reaper.sh --cluster <name> --destroy --force` remains the label-based safety net independent of registry/state-file presence
- **`aether cluster init -o` short alias collided with global `-o` `--format`** — PR #203 introduced `-o` as a short alias for `--output` on `ClusterInitCommand`, which Picocli rejected with `DuplicateOptionAnnotationsException` against the existing global `-o` for `OutputOptions.format`. The crash happened at every `aether` invocation, breaking the entire CLI. Short alias dropped — long form `--output <path>` retained
- **`postgres-async` LISTEN/UNLISTEN ordering for transactions** — `PgListenChannel` issued `LISTEN`/`UNLISTEN` on the implicit autocommit connection while a transaction was in progress on a separate connection, so async notifications arrived before the consumer's transaction had committed (or vice versa). Now LISTEN is deferred until the connection's transaction state is stable; UNLISTEN respects in-flight transaction. PR #194
- **`postgres-async` typed-get gaps for Boolean/UUID/byte[] and `TypeToken<T[]>`** — `PgRow.get(Boolean.class)`, `PgRow.get(UUID.class)`, and `PgRow.get(byte[].class)` returned `Cause` instead of mapping to the corresponding wire types; `TypeToken<T[]>` array decoding was missing entirely. Fixed via `PgValueDecoder` extensions + array-decoder dispatch. PR #197
- **`postgres-async` `PerformanceTest` hung CI 10-min timeout** — `PerformanceTest.java` is `@Tag("Slow")` and routinely runs >10 min, but Surefire's `excludedGroups>Infinite` only excluded `Infinite` (JUnit 5 tag-expression syntax via comma is broken; pipe is over-eager). Added explicit `<excludes><exclude>**/PerformanceTest.java</exclude></excludes>` so the default surefire run skips it. Run manually with `-DexcludedGroups=` or via the `slow-tests` profile when needed
- **`CertificateRenewalSchedulerStaleTimerTest` `@Disabled` — racy under CI** — `immediateRenewalBranch_storesScheduledFutureForCancellation` reproduces 2/2 in CI on slow runners due to a race between scheduler tick and assertion read. Disabled with redesign-note comment (`CountDownLatch` on transition or non-firing executor). Other two tests in the class are stable. Tracked for post-RC1
- **Cloud test harness on `run-tests.sh --env cloud`** — three patches enabling end-to-end cloud integration runs: (1) per-cluster bootstrap is gated on suite selection (`A_SUITES` / `B_SUITES` non-empty), preventing 11-VM bootstrap that exceeds Hetzner quota when only one cluster's suites were requested; (2) `CLUSTER_*_MGMT` / `CLUSTER_*_APP_DIRECT` are derived from `cloud_public_ip node-1` per cluster (was hardcoded to docker-compose `localhost:5150`/`5170`); (3) teardown uses `tools/cloud-reaper.sh --cluster <name> --destroy --force` (was `aether cluster destroy --cluster <name>` which was a no-op until commit `036057b4d` added the flag — leaking 5 VMs per cloud run). Smoke suite `00-smoke` now passes end-to-end on Hetzner: 5 nodes, `state=CONVERGED`, all assertions green, 165s wallclock
- **`ClusterInitCommand` + `ClusterConfigWizard` discarded `Result` returns (JBCT-RET-07)** — five unhandled `Result<...>` return values in the post-merge cluster-init wizard surfaced as JBCT lint errors. Resolved via extracted helpers that consume the `Result` chain end-to-end (no `@SuppressWarnings`)
- **Bootstrap silently swallowed cluster-config + API-key store failures** — `BootstrapPhaseFormation.storeClusterConfig` and `storeApiKey` printed `Warning: ...` on HTTP failure but reported bootstrap success regardless. The leader's `NodeLifecycle` FSM races with `/health/ready` quorum signal — Phase 6 detects readiness as soon as enough peers connect, but the leader's single-writer KV path requires its own NodeLifecycle to be ACTIVE (typically a few seconds later). On unlucky timing the cluster never had its config persisted, so `--wait` polled `state == CONVERGED` indefinitely. Now both stores retry every 2s for up to 60s; terminal failure fails bootstrap with new `BootstrapError.FormationWriteFailed`; success path is unchanged
- **Test-infra cloud-aware `reassign_task_group`** — `lib/cluster.sh` previously stripped a `node-` prefix and dereferenced the result as a port-offset integer; on cloud where leaders are `hetzner-eu-core-N`, this exploded with `set -u: hetzner: unbound variable`. Added env-aware branch: cloud uses `cloud_public_ip "$leader"` to resolve the leader's host, with the standard `MGMT_PORT` (no per-node offset). Docker/remote behavior unchanged
- **Test-infra capability probe parses `PG_URL`** — `detect_capabilities` checked `pg_isready -h ${PG_HOST:-${TARGET_HOST:-localhost}}`, defaulting to `localhost` for cloud runs and unconditionally setting `CAP_PERSISTENCE=false`. Now extracts host/port from `PG_URL` (the canonical source on cloud, sourced from `/tmp/aether-test-pg.env`) when `PG_HOST` is unset. Unblocks `06-deployment`, `08-resources`, `10-database`, `14-storage` on `--env cloud`
- **Test-infra teardown trap** — `set -e` plus a non-zero `print_results` exit (when any suite fails) made the script exit before Step 12 teardown, leaking 5 cloud VMs per failed run. Now an `EXIT` trap installed right after suite filter step calls `teardown` regardless of which step failed; `print_results` wrapped in `set +e/-e`. Same `SKIP_TEARDOWN` opt-out honored
- **Test-infra cloud-reaper path** — `${REPO_ROOT}/tools/cloud-reaper.sh` resolved to `aether/tools/cloud-reaper.sh` (REPO_ROOT is actually `aether/`, not the repo root) — the path didn't exist; teardown failed with "No such file or directory" as soon as the EXIT trap actually fired. Path now `${REPO_ROOT}/../tools/cloud-reaper.sh`
- **Test-infra `ENV_TYPE` not exported to suite subprocesses** — `lib/common.sh` set `ENV_TYPE` but never `export`ed it; suite scripts under parallel-suite-runner subprocesses got an empty value, defaulting cluster-shape branches to docker. Added `export ENV_TYPE`
- **Test-infra per-cluster API key sourced post-bootstrap** — RBAC Tier 2's secure-by-default (VIEWER) means every API call needs authentication. Default `API_KEY=aether-integration-test-key` (the docker hardcoded value) yielded `401 Unauthorized` against fresh cloud clusters. `run-tests.sh` now reads `~/.aether/clusters/<name>/api-key` after each cluster bootstrap and exports it as `AETHER_API_KEY`/`ADMIN_API_KEY`/`OPERATOR_API_KEY`
- **`BootstrapCleanup` couldn't terminate cloud VMs after a failed bootstrap** — `CreatedResource.ProvisionedVm.provider` was being stamped with the source TYPE (literal `"cloud"`) instead of the actual provider name (`"hetzner"`/`"aws"`/etc.). `BootstrapCleanup` then looked up `EnvironmentIntegrationFactory("cloud")` and got `No factory found for provider 'cloud'` — every failed bootstrap orphaned all 5 VMs, requiring manual reaper cleanup. `BootstrapPhaseProvision.resolveProviderName` now returns `source.provider().map(CloudProviderName::value).or(source.type().value())`, with package-private exposure for testability. Added `BootstrapCleanup.cleanup(state, cloudComputeResolver)` overload as a clean test seam. Regression tests pin both the construction site and the cleanup consumer
- **Cloud-init for cluster VMs included no SSH key, blocking all post-failure diagnosis** — `UserDataTemplate` had no `ssh_authorized_keys` section and `HetznerEnvironmentConfig.sshKeyIds` was never populated by the bootstrap orchestrator. New `BootstrapPhaseSshKey` (Phase 2) uploads-or-reuses (by MD5 fingerprint) the operator's public key to Hetzner via the SDK, stamps `SshKeyResource` in state for cleanup tracking, and threads the resulting key id into `ProvisionSpec`. New `SshAuthorizedKeysScript` cloud-init fragment installs both the operator key (`/root/.ssh/authorized_keys`) and a passwordless `aether` system user. Resolution order: `--ssh-public-key` CLI flag > `[infrastructure.ssh] public_key_files` TOML > `${AETHER_SSH_KEY}.pub` sibling. Cloud sources fail fast if no key resolves
- **JVM-mode JAR URL pointed at a non-existent repo** — `UserDataTemplate.appendJvmInstall` used `pragmaticalabs/aether/releases/...` (wrong repo path; correct is `pragmaticalabs/pragmatica`) and produced a `v${version}` tag that didn't exist for prerelease versions like `1.0.0-rc1`. Fixed repo path; tag derivation now appends `-candidate` when the version has a prerelease suffix; added `[runtime.X] jar_url` TOML override for explicit pinning
- **`UserDataTemplate.render` was never invoked from production code** — the rendered cloud-init script (Docker install, image pull, `aether.toml` write, container/JVM startup) existed but no production caller wired it through to provisioning. `BootstrapPhaseProvision` only delivered the SSH-keys-only script, leaving cluster VMs as bare Ubuntu with no aether-node runtime. Now `provisionCloudWithCompute` builds per-node user_data via `UserDataTemplate.render(...)`, threads it through `ProvisionSpec.userData()`, and provider integrations (Hetzner/AWS/GCP) honor the per-spec value over the factory-wide default
- **`docker run --config /config/aether.toml` was silently ignored** — `Main.findArg("--config=")` requires the `=`-joined form; space-separated args are dropped. Even with `=`, the image's hardcoded entrypoint passes `--config=/app/aether.toml` first and `findArg` returns the first match — bundled defaults always won. Fixed by bind-mounting the composed file directly over `/app/aether.toml:ro` (so the entrypoint's hardcoded path now points to operator config) and dropping the trailing `--config` arg. JVM mode uses the `=`-form correctly via direct CLI args
- **Cloud-init signal "done" arrived before Docker was usable** — `BootstrapPhaseDeploy`'s SSH preflight polled `ssh ... 'true'`, which succeeds the moment sshd accepts a connection. On slow Hetzner VMs (Temurin install, image pull) the SSH-back's first `docker rm -f` then failed with `bash: docker: command not found`. Preflight probe upgraded to `ssh ... 'cloud-init status --wait'` which blocks until cloud-init reaches a final state — guaranteeing Docker is installed and the cloud-init initial container has run before the deploy phase issues commands
- **Cloud SSH-back killed its own SSH session via overly broad `pkill`** — `pkill -f /opt/aether/aether-node.jar` matches against full process command lines, including the bash spawned by SSH (whose argv contains the JAR path as part of our long `pkill … nohup java …` script). pkill killed the SSH session itself → exit 255, no JVM started. Pattern anchored to `^java -jar /opt/aether/aether-node.jar` so only processes whose argv0 is `java` match
- **JVM mode tried JDK 21 against a Java 25 JAR** — `appendJvmInstall` ran `apt-get install openjdk-21-jre-headless`, but the published `aether-node.jar` is compiled to class file 69.0 (Java 25). Cloud-init succeeded, then the JVM crash-looped with `UnsupportedClassVersionError`. Now installs Eclipse Temurin 25 from Adoptium's apt repo (`packages.adoptium.net`), with proper signed-by keyring setup and version codename derivation
- **Composed `aether.toml` inherited `[cluster].tls = true` from defaults**, requiring mTLS for the management API and breaking plain-HTTP `wget --spider` health-checks (the published image's HEALTHCHECK and the deploy-phase poll). `BootstrapOverlayGenerator.clusterSection` now emits `tls = config.operations().tls().autoGenerate()` so `[operations.tls] auto_generate = false` flows through to a plain-HTTP mgmt API (cluster QUIC transport still uses TLS via `[tls].cluster_secret`)
- **Cloud SSH-back used the `aether` user (lacked docker group)** — cloud-init creates `aether` for SSH access but Docker is installed afterward, so `aether` is not in the `docker` group and `docker ps` returns "permission denied". Cloud sources now default to `root` for the SSH-back commands (cloud-init runs as root and has docker access). SSH-source path's `aether` default unchanged. Operator can override via `source.user`
- **Image tag derived from `cluster.version` instead of operator config** — `BootstrapPhaseDeploy.resolveContainerImage` was producing `ghcr.io/pragmaticalabs/aether-node:1.0.0` (which doesn't exist; only `:1.0.0-rc1-candidate` is published). Now reads `RuntimeProfile.image()` from the matching runtime profile (default = `"default"`) and uses it verbatim, falling back to derived only when no image is configured
- **PEERS list used `port + i` offset for multi-host clusters** — `buildThreePartPeers` emitted `nodeId:host:port+i` (a docker-compose holdover where multiple containers shared a host network). For SSH and cloud sources, every VM has its own host network and binds the same `clusterPort`. Peers list now emits `nodeId:host:port` for all entries
- **Cloud SSH preflight failed on the first VM that wasn't ready yet** — when DEPLOY_RUNTIME starts, slow VMs may still be booting / installing docker. The first SSH attempt timing out aborted the whole phase. Preflight now polls each cluster node with a 180s outer budget, removing successfully-reached hosts each iteration; failure aggregates and names every persistently-unreachable IP in the error message
- **Cloud `BootstrapPhaseDeploy` was a no-op for cloud sources** — printed `"Cloud-init already applied during provisioning"` and returned success without doing anything. Cluster never got the finalized PEERS list (cloud-init's container started with empty PEERS, falling through to `generatePeersFromConfig` → `aether-node-N:8090` defaults that don't resolve). Now SSHes each cloud node and `docker rm -f && docker run -d` (container) or `pkill && nohup java -jar` (JVM) with the finalized 3-part PEERS, then health-polls `/health/live`. Reuses the package-`static` `buildThreePartPeers` helper across both runtime branches
- **Bootstrap config TOML never persisted to KV-Store** — `BootstrapPhaseFormation.buildConfigJson` POSTed `{"clusterName": …, "version": …}` to `/api/cluster/config`, but the server's `ApplyConfigRequest` expects `{"tomlContent": …, "expectedVersion": <long>}`. Jackson reported `Type mismatch: expected long, got unknown at expectedVersion` (HTTP 500). Now sends the original TOML content (threaded through `BootstrapContext.rawTomlContent`) with `expectedVersion=0` (server's initial-store path)
- **Bootstrap CLI hit a dead `/api/cluster/api-key` legacy route** — `BootstrapPhaseFormation` POSTed to both the new `/api/cluster/keys` (succeeds) and the legacy `/api/cluster/api-key` (HTTP 404, no server handler). Removed the legacy fallback and the orphan `CLUSTER_API_KEY_SET` enum entry
- **`aether cluster bootstrap --wait` exit code was non-zero on success** — `--timeout` defaulted to `0` and a validator returned `ExitCode.ERROR` when `--wait` was passed without it; the bootstrap completed successfully but the CLI process exited non-zero, breaking scripts/CI. `--timeout` now defaults to `300` seconds (matching `aether cluster apply`); the validator is removed. Same fix applied symmetrically to `aether cluster scale` and `aether blueprint deploy`
- **`aether cluster bootstrap --wait` polled a route with no server handler** — `ManagementRoute.CLUSTER_HEALTH` (`/api/health`) is bound by `aether health` (per-node) but there is no cluster-health endpoint at `/api/health`. The poll always returned `UNKNOWN`. Now polls `CLUSTER_CONFIG_STATUS` (`/api/cluster/status`) and reads `state` from the response, treating `"CONVERGED"` as ready. Threads `BootstrapResult.apiKey()` and `BootstrapResult.endpoint()` to `ClusterHttpClient.setApiKeyOverride`/`setEndpointOverride` so the polling actually authenticates and targets the just-bootstrapped cluster (which isn't registered as the active cluster yet)
- **Cloud test harness used non-existent bastion + private network** — `cloud_ssh` invoked `-J ${AETHER_SSH_USER}@${BASTION_IP}` and `cloud_node_ip()` returned hardcoded `10.0.1.1${num}`, but the bootstrap config never created a private network or bastion. Switched to **Option A** (direct public-IP addressing): new `cloud_public_ip <node-id>` helper reads VM addresses from `~/.aether/clusters/<name>/bootstrap-state.json`; `cloud_ssh` connects directly. `BOOTSTRAP_CLUSTER_NAME` env var now matches what `aether cluster bootstrap --cluster <name>` registered. Includes 8 unit tests in `test/test-cloud-helpers.sh` against a synthetic state-file fixture. Bastion + private network model deferred to a future phase (requires non-trivial bootstrap code: `NetworkingType` enum extension, `BastionProvider` SPI, `BootstrapPhaseNetwork`)
- **Hetzner `cx22` instance type no longer exists** — both `cloud-hetzner.toml` and `cloud-hetzner-b.toml` updated to `cx33` (4 vCPU, 8 GB RAM). cx22 → 404 at provision
- **Cluster scale-up silently dropped on non-leader receivers** — `ManagementRoute.CLUSTER_SCALE` was declared `taskGroup(SCALING)` but `ClusterTopologyManagerRecord.onClusterConfigChanged` is leader-gated, so when the SCALING owner was not the leader, the scale request silently no-op'd (CTM Inactive → notification dropped). Reclassified to `RouteTarget.LEADER` (uses the `LeaderNode` infrastructure from the delegation Bug A fix). Also: `setDesiredSize` returns `Promise<Unit>` (was fire-and-forget `Result<Unit>` swallowing consensus apply failures). Also: removed the duplicate `ClusterConfigKey.CURRENT` write inside `executeScale` — the route handler is now the sole writer; CTM picks up the change via the existing KV ValuePut notification path
- **Self ON_DUTY bootstrap retry** — `HealthReconciler.proposeSelfOnDutyWrite` now retries up to 8 attempts with exponential backoff (200ms → 2s cap) on transient `ConsensusError.NodeInactive` rejections. R8 deletion of `NodeDeploymentManager.retryLifecycleOnDuty` regressed cold-start where the local lifecycle FSM hadn't reached ACTIVE before the self-ON_DUTY KV write fired. `AetherNode.bridgeSelfReadyToLifecycle` reordered to advance `nodeLifecycle.signalReady()` before `healthReconciler.signalSelfReady()` to narrow the race window. Without this fix the cluster never reached `coreCount=5` after `docker compose up` on remote
- **Aggregator quorum threshold structurally unreachable** — `ObservationAggregator.quorumThreshold` returned `(onDuty+1)/2` (= 3 for 5-node clusters), but each node's `HealthReconciler` only feeds its own local SWIM observation into the aggregator (no cross-node observation gossip). With 1 observer per aggregator and threshold ≥ 2, `tally()` permanently returned `none()`, and `proposeLifecycleWrite(target, DECOMMISSIONED)` was never invoked when peers died. Now leader-gated single-observer mode: leader's local SWIM FAULTY observation alone authorizes the lifecycle KV write (SWIM's own indirect-probe quorum already validates the observation). Followers' aggregators continue observing for diagnostics but propose no writes
- **Bridge KV `NodeLifecycleKey` to `/api/events`** — followers now surface `NODE_LEFT` (graceful drain → DECOMMISSIONED) and `NODE_FAILED` (abrupt loss with no prior DRAINING) on the events stream via `ClusterEventAggregator.onNodeLifecyclePut`. Previously `NODE_LEFT/NODE_FAILED` were emitted only on the leader via the QUIC `TopologyChangeNotification.NodeRemoved/NodeDown` path, leaving follower event buffers silent. The bridge uses a per-node lifecycle-state cache for idempotent edge detection and reads cluster size from the membership snapshot
- **Operator task-group reassignment ignored** — `CLUSTER_TASK_REASSIGN` route was declared `RouteTarget.taskGroup(DEPLOYMENT)` but `TaskAssignmentCoordinator.reassign()` is leader-bound; requests forwarded to the DEPLOYMENT owner (often not leader) returned `NOT_LEADER` which `curl -sf` swallowed silently. New `RouteTarget.LeaderNode` variant routes leader-bound management calls correctly via `ManageableNode.leaderId()`. `NOT_LEADER` now surfaces as HTTP 409. `HttpForwarder` gained `forwardToLeader` branch with typed `NoLeaderElected` / `LeaderDisconnected` / `NotLeader` causes
- **Auto-reassignment loops back to restarted node** — `TaskAssignmentCoordinator.isOrphanedOrFailed` orphan branch (topology-departure path) now calls `trackFailedNode(group, assignedTo)` to arm the 30s cooldown. `selectLeastLoadedNode` tie-breaker switched from `Comparator.naturalOrder()` on `NodeId` (which always preferred lexicographically-lowest) to a stable hash-based rotation `(group.hashCode() * 31 + node.hashCode()) & 0x7fffffff` so different groups prefer different nodes on tied load. `writeAssignment` clears the target node from `failedNodes` so an operator-issued assignment is not blocked by stale cooldown
- **CLI `--request-timeout` ignored by cluster commands** — `ClusterHttpClient` (used by `aether cluster *` subcommands) used a static `JdkHttpOperations` singleton with default ~60s request timeout, causing `aether cluster await-quiesced --timeout 60s` to time out at exactly 61s before the server's 60s blocking window completed. New `ClusterHttpClient.setRequestTimeout(Duration)` is wired from `AetherCli.main` to honor `--request-timeout` (default 130s, > server-side 120s max). All five HTTP methods (`doGet`/`doPost`/`doPut`/`getDirect`/`postDirect`) apply the timeout
- **Test-infra: `pick_non_leader` and `kill_node` exclude pinned MGMT entry-point** — on cluster B (`docker-compose-b.yml`, `restart: "no"`), `node-1` is the pinned operator entry-point at port 5160. Previously chaos tests could select and kill it, leaving the mgmt endpoint dead and cascading failures across all subsequent cluster-B suites. New `mgmt_entry_point_node()` helper resolves the pinned node from env (default `node-1` for cluster B); `pick_non_leader` filters it out of candidates; `kill_node` refuses to kill it. `test-kill-leader.sh` skips fast (instead of failing) when the leader is the pinned node since cluster B has no safe rotation path
- **Test-infra: `cluster_node_count` reads `/api/cluster/generation`** — fixture-port-only polling missed CTM-provisioned nodes (overlay-only by default). Now reads `core.members[].nodeId` count + `core.desiredSize` from the leader-side generation snapshot. Combined with `wait_for_node_count_fast` (curl-based, avoids ~1-2s CLI cold-start per poll), scaling tests observe new core members as they join
- **Long-suspected peers stayed in topology, wedging consensus writes** — `TopologyObserver.initReconcile` now evicts any peer whose `state.failedAttempts` passes `BackoffConfig.shouldDisable` (default 4 retries) by routing a `TopologyManagementMessage.RemoveNode`. Without this, a CTM-provisioned node that was externally terminated (e.g. `docker rm -f`) without a clean shutdown stayed indefinitely in `nodeStatesById` as `SUSPECTED` when QUIC didn't produce a hard disconnect event. The phantom kept appearing in `activeNodes` used by `ClusterDeploymentManager.cleanupStale{NodeArtifact,NodeRoutes,Slice}Entries`, so its KV entries survived across destructive suites — by suite 13 the forwarding layer was racing against dead addresses and `cluster.apply(...)` on blueprint publish timed out with `Promise timed out after 10000ms`. Eviction routes `RemoveNode` through the standard path, which now cascades to the existing CDM per-node cleanup
- **CDM `cleanupStale*` runs on periodic reconcile** — previously only on `activate()` after a leader handoff, so if the leader stayed put through several suites, accumulated orphan slice/node-artifact/node-routes entries never got swept
- **CTM deficit hysteresis** — `ClusterTopologyManagerRecord.handleDeficit` defers the actual `provisionNodes` call by `autoHealConfig.retryInterval()` (10s default) after transitioning to `Reconciling`. At the end of the hysteresis window, `attemptProvisionAfterHysteresis` re-reads `observer.activeNodeCount()`: if the deficit healed (peer reconnected, handleAddNodeMessage cleared its tombstone and re-added it), transition straight to `Converged` without provisioning. Absorbs transient QUIC flaps that would otherwise inflate the cluster to `configured + 1` nodes while a surplus-detect cycle catches up
- **CTM over-provisioning after kill-under-load (#166)** — `TopologyObserver.initReconcile` re-added every configured core node that was missing from `nodeStatesById`, intended for transient-disconnect recovery. After an external `docker kill`, the dead peer got resurrected from `config.coreNodes()` on every 5s reconciliation tick while CTM provisioned a replacement, producing a 6-node cluster instead of the 5-node target. Added a `tombstonedNodes` set: `handleRemoveNodeMessage` records the peer, `initReconcile` and `handleDiscoveredNodes` skip tombstoned peers, `handleAddNodeMessage` (explicit re-add via QUIC Hello from a restarted container) clears the entry, and the drain-to-self reseed path also clears. CTM reconciliation switched from `observer.activeNodeCount()` to a new `observer.healthyActiveNodeCount()` that filters by `NodeHealth.HEALTHY`; `/api/cluster/topology.coreCount` and CTM's provision-tag `aether.peers` list now consult the same health-aware filter. `QuicClusterNetwork.onPostEstablishGraceComplete` / `onQuorumLossConfirmed` also emit a `TopologyChangeNotification.nodeRemoved` after routing deferred `RemoveNode`, so CTM re-reconciles at the moment the topology actually shrinks instead of waiting for the next random event
- **Canary test re-lookup of deployment ID** — `test-deploy-canary.sh` called `deploy_list` → `deploy_extract_id` in every stage. `deploy_list` filters by `Deployment::isActive`, so once a deployment reaches COMPLETED it disappears from the list and the re-lookup returns empty, failing the COMPLETE assertion. Test now captures `DEPLOYMENT_ID` from the start-response and reuses it across stages. Canary COMPLETE tolerates the already-COMPLETED case by verifying final state via `deploy_status`
- **Cluster B state pollution between destructive suites** — `self_heal` now calls `restore_baseline` before the 120s CTM-auto-heal wait. `restore_baseline` removes any CTM-provisioned `aether-core-*` containers and starts stopped compose nodes, so the next suite on cluster B starts against the canonical `node-1..5` topology instead of a mix of original + provisioned replacements whose drifting identities confused slice placement in 13-edge-cases
- **`aether` CLI hangs indefinitely on a wedged management endpoint** — `AetherCli.rawGet/rawPost/rawPut/rawDelete` called `httpOps.sendString(request).await()` against an `HttpRequest` with no `.timeout(...)`. When a server accepted the connection but never responded (e.g. management forward to a dead task-group owner whose internal retry exhausted without surfacing an error), the await blocked forever. New `--request-timeout=<seconds>` option (default 60s, 0 disables) attaches a `TimeSpan.timeSpan(N).seconds().duration()` timeout to every HttpRequest builder
- **Test-persistence schema migration silently skipped under shared PostgreSQL** — `aether_schema_history`'s `(version, type)` PK is global to the database, so when an example blueprint applied `V001__create_tables.sql` before `test-persistence`'s `V1__create_kv.sql`, the latter was treated as already-applied and `kv_store` was never created. Bumped to `V900__create_kv.sql` to avoid collisions with examples and standard test fixtures (proper namespacing tracked separately)
- **Slice-processor dropped path/query params in body-bearing routes** — `generatePathBodyRoute`, `generateQueryBodyRoute`, and `generatePathQueryBodyRoute` emitted `.to((key, body) -> delegate.method(body))`, discarding the path lambda arg. When a slice method took a single combined record (e.g. `PutRequest(String key, String value)`) against `PUT /{key}` + JSON body, the body-parsed record had `key=null`, surfacing later as a SQL not-null violation or equivalent. Generators now walk the param record's components in declaration order, matching path/query names against component names and emitting `body.<component>()` for the rest — `delegate.put(new PutRequest(id, body.name()))`. Compile-time error when a path/query name has no matching record component. `MethodModel.recordComponents(TypeMirror)` helper added
- **Cluster B leader election storm** — `QuicClusterNetwork.handleQuorumCandidate` previously short-circuited the 5s stabilization window with an "all peers connected — establishing quorum immediately" path. On a concurrently-starting cluster (e.g. compose nodes with no staggered `depends_on` gate), this fired `QuorumStateNotification.established` before transient QUIC flap settled, which then mutated the Rabia topology mid-round and left consensus stuck in `Phase[value=0]` — proposals kept submitting every 3s with no `onLeaderCommitted`. Now always waits `stabilizationWindow`, then starts a `postEstablishGrace` window that buffers single-peer REMOVE events in `pendingRemovals` (cleared on ADD, flushed as real RemoveNode on expiry). Restores consensus progress on concurrent-start clusters without affecting staggered-start clusters
- **Cluster scale-up wouldn't provision new nodes** — three-layer bug: (1) `cluster-config.toml` declared `[core_topology]` but `ClusterBootstrapConfigParser` reads `[cluster.core]`, so stored `coreMax` defaulted to the current count and any scale > 5 was rejected with "Invalid core max"; (2) `/api/cluster/scale` route is `taskGroup(SCALING)` and forwards to the SCALING owner, but `ClusterTopologyManager` only runs on the consensus leader — `setDesiredSize()` on the non-leader node's inactive CTM was a no-op. Added a KV listener on every node: on `ClusterConfigKey` put, propagate `coreCount` into `CTM.setDesiredSize(...)`; the leader's active CTM reacts and reconciles; (3) `DockerComputeProvider` hardcoded `network_name = "aether-network"` while multi-cluster integration tests use `aether-a-network` / `aether-b-network`, and published host ports `8080..8084` that collided with the seed cluster. Added `AETHER_DOCKER_NETWORK` env-var override (auto-propagated to provisioned children) and dropped host-port publishing for provisioned nodes — they are reachable via the docker network only, with management traffic forwarded through existing exposed nodes
- **Task reassignment rejected with NOT_LEADER** — `PUT /api/cluster/tasks/reassign/{group}` is `taskGroup(DEPLOYMENT)` routed, but `TaskAssignmentCoordinator.reassign()` short-circuited to `CoordinatorError.NOT_LEADER` unless called on the consensus leader. Outer coordinator now writes `TaskAssignmentValue` directly via `clusterNode.apply([Put])` — consensus replicates the change, leader's active coordinator picks it up through its existing notification path, and each node's `TaskGroupActivator` activates/deactivates components as the KV change lands
- **Integration test wait conditions** — `is_cluster_ready` now waits for `>= NODE_COUNT` nodes (not just quorum of 3), eliminating race where `cluster_node_count` returned 4 before topology fully populated. `discover_endpoints` fetches LB endpoints via `aether` CLI and probes reachability before accepting — falls back to direct node access when discovered LB hostname is only resolvable inside the cluster network. `run-tests.sh` made safe against empty `A_SUITES`/`B_SUITES` arrays under `set -u`
- **`test-persistence` blueprint** — `read` route migrated from `GET /stream` (mismatched against single-param method) to `POST /stream`; `resources.toml` now declares the `[database]` section required by the `PgSqlConnector` qualifier (flagged by PR #161's new resource-config validator). `RouteSourceGenerator` correctly handles zero-parameter methods (`.to(_ -> delegate.foo())` instead of `new FooRequest()`) in both `generateNoParamsRoute` and `resolveParameterType`
- **`EmberCluster.setClusterSize`** — annotated with `@Contract` to satisfy JBCT-RET-01 for intentional side-effectful void methods
- **Envelope format version** bumped to v8 for config update manifest entries
- **JSON injection in CLI** — `aether cluster migrate` now escapes user-supplied values in JSON request body
- **Config parse safety** — Environment integration factories wrap `Long.parseLong`/`Integer.parseInt` with `Result.lift()` to prevent node crash on malformed config
- **Thread safety** — Replaced non-thread-safe `EnumMap` with `ConcurrentHashMap` in QUIC outbound queues
- **Null policy** — `DeploymentManagerImpl` uses `Option<Version>` instead of raw null in domain logic; `parseThresholds` wrapped with `Result.lift()` fallback
- **Composition** — Replaced 4x `fold(() → default, id)` with `.or(default)` in CTM; simplified nested fold in `GovernorFailoverHandler`
- **Factory methods** — Added JBCT-compliant factories for `MigrationStep`, `MigrationError`, `PgStreamError`, `CloudCertificateProviderError` subtypes
- **Test assertions** — Fixed assertion-free drain test; replaced silent-pass `assertThat(cause).isNull()` pattern in 4 provider test files
- **TaskGroupActivator infinite loop** — Skip ACTIVE/FAILED terminal states in `onTaskAssignmentPut` to prevent activation write-back triggering re-activation
- **Docker socket permissions** — `group_add` for host Docker GID in compose + `--group-add` in DockerComputeProvider for provisioned containers
- **Docker network name** — Explicit `name: aether-network` in compose avoids project prefix; provisioned containers join the correct network
- **Container name collisions** — Nano-time suffix on provisioned container names prevents conflicts across test runs
- **NODE_ID_TAG mismatch** — `NodeLifecycleManager` used hyphens (`aether-node-id`) but Docker labels use dots (`aether.node-id`), preventing container termination during scale-down
- **ClusterConfigApplier not wired** — ManagementServer used `unused()` no-op applier; scale operations stored config but CTM never called `setDesiredSize()`. Now wires real applier via `ManageableNode.clusterTopologyManager()`
- **LB phantom topology nodes** — 3-part PEERS parsing in LB Main.java eliminates random NodeId generation that polluted cluster topology
- **Consensus sync cancelled under load** — `advancePhase()` unconditionally set engine state to Idle, cancelling sync tasks when Decisions arrived during synchronization. Now only transitions InPhase→Idle, preserving Syncing state. Root cause of provisioned node 180s+ activation delay under HTTP load
- **LB binary response corruption** — `sendResponse()` round-tripped response bodies through UTF-8 String, replacing every non-UTF-8 byte with U+FFFD (3 bytes). Corrupted artifact GETs and any binary response. Fixed to write raw bytes via `ResponseWriter.write()`
- **LB management API on dedicated port** — Management API forwarding now requires explicit `LB_MANAGEMENT_PORT` configuration; absence disables forwarding entirely. Prevents accidentally exposing management API on public client port. Default `LB_MANAGEMENT_MAX_CONTENT_LENGTH` = 2 MiB
- **Auth on forwarded requests** — QUIC-forwarded management requests now enforce the same `validateManagementSecurity` check as direct HTTP. Prevents auth bypass via LB management port
- **RequestRouter walk-back** — `findRoute()` iterates descending headMap entries instead of single `floorEntry`, fixing route resolution when sibling prefixes (e.g. `/api/streams/publish/`, `/api/streams/read/`) shadow the parent (`/api/streams/`)
- **Unknown route fallback** — LB forwards unmatched management routes to any core node (legacy/Maven repository routes) instead of returning 502. Node-side returns proper 404 `HttpResponseData` for truly unknown paths
- **Integration test overhaul v2** — Dual-cluster architecture (non-destructive parallel + destructive sequential), `run-tests.sh --env docker|remote|cloud` single entry point, suite metadata (`suite.conf` on all 16 suites), capability-based filtering, self-heal between destructive tests, LB endpoint discovery via cluster status API
- **Test blueprints** — Three purpose-built test slices: `test-echo` (stateless), `test-persistence` (PgSql + streaming), `test-full` (multi-slice + delegation). Built as Step 5 of `build.sh`
- **Environment templates** — TOML configs for docker, remote, and cloud-hetzner environments (A/B cluster pairs), dual-cluster Docker Compose files with shared PostgreSQL
- **Zero external dependencies** — Eliminated python3, jq, and bc from integration test infrastructure. Shell-native JSON parsing via `lib/json.sh`, awk for floating-point arithmetic, Aether CLI as primary API client
- **Hetzner IT test safety** — Explicit surefire exclusion of `*IT.java` in hetzner environment module prevents accidental cloud server creation during `mvn verify`
- **JBCT suppressions for jOOQ XML** — `IndentingXmlStreamWriter`, `JooqXmlExporter` (javax.xml interface implementations), `ExportJooqXmlMojo`, `CheckJooqXmlMojo` (Maven API contract) properly annotated with `@Contract`/`@SuppressWarnings`
- **Deploy-compose CTM cleanup** — `deploy-compose.sh` explicitly kills `aether-core-*` containers before every deploy. Auto-provisioned containers from CTM survive `docker compose down` and previously broke consensus on subsequent runs
- **Integration test deploy helpers** — Deploy start/promote/rollback/complete/list/status use LB-routed `api_post`/`api_get` instead of `aether_failover` (which silently returned error JSON on wrong-owner nodes). Strategy tests baseline v1 first, then publish v2 for upgrade
- **SecurityPolicy deny-by-default** — Unrecognized security policy values now default to `apiKeyRequired()` instead of silently falling through to `publicRoute()`. Prevents config typos from creating unauthenticated routes
- **SQL injection in LISTEN/UNLISTEN** — PostgreSQL channel names validated against `^[a-zA-Z_][a-zA-Z0-9_]*$` before interpolation into simple query protocol
- **InsecureTrustManagerFactory gated in QUIC transport** — Insecure TLS mode now requires `AETHER_INSECURE_DEV_MODE=true` env var. Default (no TLS config) returns an error instead of silently disabling certificate validation
- **InsecureTrustManagerFactory gated in PostgreSQL driver** — PG SSL connections default to JVM system trust manager. Insecure mode requires `pragmatica.pg.insecure-tls=true` system property
- **XXE protection in Maven XML parsing** — Full XXE hardening (disallow-doctype-decl, external entities, XInclude) in `MavenSettingsCredentials` and `MavenLocalRepoLocator`
- **SHA-256 artifact checksums** — Artifact verification upgraded from SHA-1 to SHA-256 primary with SHA-1 fallback. Missing checksums now fail the download instead of being silently skipped
- **Cloud config secret redaction** — `toString()` overridden on `HetznerConfig`, `AwsConfig`, `AzureConfig`, `GcpConfig`, `S3Config` to redact API tokens, secret keys, and private keys
- **Docker Compose random secret** — Fallback cluster secret uses `SecureRandom` 32-byte hex instead of hardcoded `"auto-generated-compose-secret"`
- **SSH image name validation** — Docker image names validated against safe pattern before interpolation into SSH commands, preventing command injection
- **API key file storage** — Bootstrap writes API key to `~/.aether/clusters/<name>/api-key` with 600 permissions instead of printing to stdout
- **STRONG consistency eviction guard** — `REJECT_WHEN_FULL` eviction policy for STRONG streams prevents consensus-committed events from being silently evicted. AHSE required for STRONG stream creation
- **Failover recovery wiring** — `StreamingCoordinator.activate()` triggers `GovernorFailoverHandler` for all streams on STREAMING task group activation. Replays events from AHSE segments + replica watermarks
- **Cross-node stream publish forwarding** — Producers on any node can publish to any partition via direct QUIC messages (`StreamForwardMessage`). No HTTP overhead — binary protocol with correlation tracking and 5s timeout
- **Consumer group coordination** — Automatic partition assignment using KV-Store-backed `ConsumerGroupCoordinator` (leader-side round-robin) + `ConsumerGroupRegistry` (read-side mirror). Join/leave/status management API endpoints
- **Sync replication acknowledgment** — `replicateAndAwait(minSyncReplicas)` waits for N replica acks before resolving. Configurable via `StreamConfig.minSyncReplicas`
- **Batch replication** — `ReplicationBatcher` accumulates events per partition (100 events or 1ms window) and sends single `ReplicateEvents` message. 10-50x reduction in QUIC message count
- **Consumer read-preference** — `ReadPreference` enum (GOVERNOR, ANY_REPLICA, NEAREST) routes reads to replicas for load distribution
- **Push notification for co-located consumers** — `OffHeapRingBuffer.appendListeners` invoke consumer callbacks immediately on append. Eliminates polling latency for same-JVM consumers (~1-10us)
- **Adaptive polling** — Consumer poll interval adapts 1ms-50ms: doubles on empty poll, resets to 1ms on data. Replaces fixed 50ms
- **Producer batching API** — `StreamPublisher.publishBatch(List<T>)` with `OffHeapRingBuffer.appendBatch()` for single eviction check and batch replication
- **Zero-copy consumer** — `OffHeapRingBuffer.readSlice()` returns `MemorySegment` view into buffer. No `toArray()` copy for co-located consumers
- **Push consumer cursor persistence** — `ConsumerRuntimeState` loads initial cursor from `CursorStore` and checkpoints every 1000 events or 30s
- **Segment compression** — LZ4 and ZSTD compression for sealed segments via existing `CompressionCodec` infrastructure. Configured per stream via `StreamConfig.compression`
- **Segment encryption** — AES-256-GCM encryption for sealed segments via existing `BlockEncryptor`. Configured per stream via `StreamConfig.encryptionKeyId`
- **Transactional cursor commits** — `PgTransactionalCursorCommit` wraps cursor UPSERT + business logic in single PostgreSQL transaction for exactly-once semantics
- **Compound retention policies** — `RetentionMode.ALL`/`ANY` combinators for time + count + size retention policies
- **Stream deletion API** — `DELETE /api/streams/{name}` endpoint
- **Consumer cursor/lag API** — `GET /api/streams/consumers/{name}` endpoint with partition offsets
- **Stream memory configuration** — `STREAM_MAX_MEMORY_BYTES` env var (default 128MB) + `aether.streams.memory.used.ratio` Micrometer gauge
- **Consumer timeout** — Auto-unsubscribe consumers idle for 60s
- **QUIC auto-reconnect** — `TopologyObserver` re-adds configured core nodes removed from topology on each reconciliation cycle. Fixes LB losing connections to restarted nodes
- **CTM env propagation** — `DockerComputeProvider` propagates `AETHER_INSECURE_DEV_MODE` and `AETHER_CLUSTER_SECRET` to provisioned containers
- **QUIC missing-peer reconciler** — `QuicClusterNetwork` now ticks every 5s and dispatches `connectPeer` for any configured peer absent from `connectedPeers()`. Recovers from container-recreation reconnect asymmetry where a recreated peer re-handshakes with N-1 peers but silently misses one (sticky SUSPECTED never clears via per-pong fan because no traffic flows). Per-peer jittered exponential backoff (5s initial → 60s cap) held on `PeerState`; `CONNECTING` / `REMOVED` / wrong-direction skipped; cancellable on shutdown. Validated end-to-end by smoke gate recovery on the remote integration suite
- **`swimHints` projection TTL (60s default)** — `HealthReconcilerContext.swimHints` map entries now decay after `swim_hints_ttl` (configurable via `[operations.auto_heal]`). Defense-in-depth so sticky SUSPECTED self-heals when transport recovery is delayed; aligns with the project invariant "state reconstructible from KV-Store" — the in-memory projection map no longer holds non-decaying state forever. SWIM's own SUSPECT/FAULT signals remain authoritative

### Changed
- **`build.sh`** — Exports `AETHER_INSECURE_DEV_MODE=true` for development builds

## [1.0.0-alpha] - 2026-04-04

### Added
- **CTM bidirectional convergence** — ClusterTopologyManager now reliably converges cluster to configured size in both directions: scale-up (provision) and scale-down (terminate). Separate `configuredSizeRef` (operator intent) from `desiredSizeRef` (working target). Node selection for termination: empty nodes first, then most recently joined, never self. CAS-based state transitions eliminate race conditions
- **DockerComputeProvider** — `ComputeProvider` SPI implementation for Docker-based cluster scaling. Provisions/terminates containers via Docker CLI, label-based instance discovery, atomic port allocation. Enables integration test scaling without cloud providers
- **PostgreSQL persistence adapter** — `@PgSql` type-safe persistence with compile-time SQL validation. Annotation processor validates `@Query` SQL and generates CRUD from method names (Spring Data conventions: `findBy*`, `save`, `insert`, `deleteBy*`, `countBy*`, `existsBy*`). Named parameter rewriting (`:param` → `$N`), query narrowing (`SELECT *` → explicit columns), record expansion for INSERT/UPDATE
- **PostgreSQL tooling (aether-pg-tools)** — SQL parser (PEG-based, ~500 rules), event-sourced schema model (25 event types), 41-rule migration linter (lock hazards, type design, schema design, migration practice), Java record/enum code generation from schema
- **pg-maven-plugin** — standalone Maven plugin for generating Java records/enums from PostgreSQL migration SQL files (`mvn pg:generate`)
- **`PgSqlConnector`** — PostgreSQL-specific marker interface extending `SqlConnector`, async-only factory routing (no JDBC/R2DBC fallback)
- **`@PgSql` qualifier** — resource qualifier annotation for persistence interfaces and slice factory parameters
- **`jbct add-persistence`** — CLI command to add PostgreSQL persistence support to existing projects
- **`--with-persistence` flag** — option on `jbct init` to scaffold persistence from the start
- **pg-showcase example** — demonstrates all persistence patterns: `@Query` with joins, CRUD auto-generation, record expansion, multi-table, projections
- **PostgreSQL persistence guide** — comprehensive developer documentation with setup, examples, validation rules

- **Compile-time validation stages 3-4** — parameter type checking against schema columns, return record field mapping against SELECT output, CRUD column existence validation, NOT NULL column coverage for insert/save, safe type coercion support
- **Record expansion wiring** — `VALUES(:request)` and `SET :request` patterns now expand record fields in generated SQL with accessor expressions in factory code
- **Migration manifest** — `pg-maven-plugin` generates `migrations.list` for reliable annotation processor schema discovery
- **JBCT file size limit** — `[files] maxFileSize` in `jbct.toml` (default 1MB) auto-skips grammar-generated parsers from format/lint
- **JBCT glob excludes** — `[files] excludes` in `jbct.toml` for explicit file pattern exclusion from format/lint
- **`@Contract` suppresses all JBCT rules** — marks Java API boundary methods (annotation processors, Maven Mojos) as exempt from JBCT lint
- **pg:lint Maven goal** — migration linting via `mvn pg:lint`, reports lock hazards, type design issues, schema design problems
- **Unified blueprint-level deployment** — single `aether deploy` command and `/api/deploy` endpoint replacing separate canary/blue-green/rolling-update commands. All deployment strategies operate on entire blueprints (all slices atomically), not individual slices
- **Unified deployment spec** — `aether/docs/specs/unified-deploy-spec.md` with complete API design

### Changed
- **Flow-based JBCT formatter** — completely replaced trivia-entangled CstPrinter with FlowPrinter that makes layout decisions from code structure + width measurement only. Eliminates all blank-line accumulation bugs by design. 0 non-idempotent files across 1,970-file codebase
- **DeploymentMap renamed** — `DeploymentMapImpl` → `IndexedDeploymentMap` (JBCT naming compliance)
- **Standalone example POMs** — `url-shortener` (1.0.0) and `url-shortener-v2` (1.0.1) decoupled from parent POM version, produce same `org.pragmatica.aether.example:url-shortener` artifact at different versions for deployment strategy testing
- **Aether Store branding** — PostgreSQL persistence adapter branded as "Aether Store" in all user-facing documentation
- **build.sh** — replaced `-q` with grep filtering, JBCT formatting warnings visible, no more stalls on large files
- **Format logging** — JBCT formatter now logs reformatted files at WARN level (was DEBUG)
- **url-shortener examples** — migrated from raw `@Sql`/`SqlConnector` to typed `@PgSql` persistence interfaces
- **Deployment CLI** — `aether deploy --canary`, `aether deploy --blue-green`, `aether deploy --rolling` replace `aether canary`, `aether blue-green`, `aether update`
- **Deployment REST API** — `/api/deploy` replaces `/api/canary/*`, `/api/blue-green/*`, `/api/rolling-update/*`
- **Resource reference docs** — added `PgSqlConnector` section with link to persistence guide

### Fixed
- **SchemaLoader migration discovery** — expanded from 1 suffix to 28 common descriptions, plus manifest-first approach
- **Table name resolution** — `OrderRow` → `orders` (was `order`), correct pluralization via schema lookup
- **INSERT with record params** — expands record fields (was using parameter name as column)
- **FQCN in generated code** — `java.lang.Long` → `Long`, inner types simplified in factory output
- **FactoryGenerator mapper typeArg** — `getObject()` calls now include class argument for Instant/BigDecimal
- **`Result.failure(cause)` → `cause.result()`** — 7 sites in SchemaBuilder, CodegenPipeline, RecordGenerator
- **Multi-statement lambdas** — 6 extracted to named methods across SchemaBuilder, DdlAnalyzer, linter rules, TypeMapper
- **SWIM double-start race condition** — atomic `starting` flag prevents two ESTABLISHED notifications from creating duplicate SWIM protocols; transport bind failure now aborts protocol creation
- **Slice processor @PgSql detection** — `ResourceQualifierModel.fromParameter()` now checks type-level annotations, not just parameter annotations; persistence interfaces correctly classified as resources
- **Slice processor factory wrapping** — generated code maps `PgSqlConnector` through `{Interface}Factory` when resource type differs from parameter type
- **PgSqlConnectorFactory SPI registration** — added to `META-INF/services/org.pragmatica.aether.resource.ResourceFactory`
- **Blueprint deploy classifier** — CLI and server auto-append `:blueprint` classifier when only `groupId:artifactId:version` given
- **Integration test node count** — `cluster_node_count()` uses health endpoint (QUIC peers) instead of metrics-based status endpoint
- **Integration test deploy flow** — push artifacts before deploy, use CLI for deployment with failover
- **Status endpoint node count** — uses live `connectedPeerIds()` instead of stale metrics-based count
- **CLI SLF4J warnings** — added `slf4j-nop` to CLI dependencies
- **Docker healthcheck** — uses `/health/live` (no auth required) instead of `/api/health`
- **Audit logging** — set to WARN level, suppresses debug auth success noise
- **QUIC reconnection storm** — root cause was 2-part PEERS format creating wrong NodeIds (`node-aether-node-X-6000`). Fixed with 3-part format (`nodeId:host:port`). Also: self-connection guards in `onPeerConnected` and `processViewChange`, self-exclusion from reconciliation loop, `connectingInProgress` dedup guard
- **Deploy list endpoint** — CLI `deploy list` used wrong path `/api/deployments` (404), corrected to `GET /api/deploy`
- **Deploy immediate field** — CLI `deployImmediate()` used `"blueprint"` field, corrected to `"artifact"` for `/api/blueprint/deploy`
- **DeployCommand JSON bodies** — CLI now sends correct nested JSON matching API's `DeployRequest` schema: nested `"canary"/"blueGreen"/"rolling"` strategy configs, nested `"thresholds"` object
- **Slice processor FQCN in provide() calls** — `generateResourceProvideCall` and plain interface factory params now use `ImportTracker` for simple names instead of fully-qualified class names
- **Integration test scripts** — fixed JSON field paths (08-http-client), OOM prevention with RPS cap (04-under-load), temp file race condition (13-concurrent-deploys), strengthened disruption budget assertions (13-disruption-budget), relaxed error rate threshold
- **Integration test helpers** — added missing `schema_status()`, `drain_node()`, `activate_node()` functions; drain endpoint uses path params not JSON body
- **Integration test TLS handling** — certificate-status and cert-rotation tests handle `NOT_CONFIGURED` state when TLS is disabled
- **Integration test load target** — cert-rotation load test uses management endpoint `/health/live` (was app endpoint)
- **Smoke test node count** — uses `>=` assertion to accommodate passive LB node
- **Status endpoint self-node** — `/api/status` now includes responding node in `cluster.nodes` list (was excluded, showing 4/5)
- **Deployment lifecycle** — `start()` auto-advances through PENDING → DEPLOYING → DEPLOYED; `complete` allowed from DEPLOYED, ROUTING, or PROMOTING states
- **Dashboard auth** — API key login overlay with sessionStorage; static files bypass auth; no data fetching until key validated; 401 handling doesn't interrupt login
- **Dashboard success rate** — normalizes server values (0-100) to client fraction (0-1) across all data sources (REST, WebSocket, seed)
- **Dashboard nodes/slices** — populates nodes from REST `cluster.nodes`; fetches slice details from `/api/slices`
- **Release workflow** — added `binutils` for `objcopy` in arm64 jlink build
- **k6 load test** — sends `X-API-Key` header for authenticated app endpoints
- **Storage management test** — handles empty `{}` response when no storage configured
- **JBCT formatter blank-line artifacts** — removed 13,911 blank lines across 91 aether files from previous formatter bug

### Removed
- **Separate deployment commands** — `aether canary`, `aether blue-green`, `aether update` removed (use `aether deploy --strategy`)
- **Separate deployment REST endpoints** — `/api/canary/*`, `/api/blue-green/*`, `/api/rolling-update/*` removed (use `/api/deploy`)

## [0.25.0] - 2026-04-01

### Added
- **Hierarchical Storage Engine (AHSE)** — Content-addressed block storage with tiered Memory + Disk hierarchy. Core library at `integrations/storage` (zero Aether deps), Aether adapter at `aether/aether-storage`. BlockId (SHA-256), MemoryTier (CAS-bounded), LocalDiskTier (sharded filesystem), StorageInstance (write-through + tier-waterfall reads), SingleFlightCache (read dedup), MetadataStore (in-memory; the KV-Store-backed variant was never built — `InMemoryMetadataStore` is the only implementation and `StorageBlockKey`/`StorageRefKey` have zero production readers; corrected 2026-08-24, #634 item 6), SnapshotManager (dual-trigger: mutation count + time interval, rolling pruning), StorageReadinessGate (startup sequencing with read/write barriers), per-instance TOML config (`[storage.*]` sections), ArtifactStore migration (chunks via StorageInstance), config-driven StorageFactory with node wiring, per-node REST API (`/api/storage`, `/api/storage/{name}`, `/api/storage/{name}/snapshot`), per-cluster REST API (`/api/cluster/storage`, `/api/cluster/storage/{name}`) with KV-Store status publishing, CLI commands (`aether storage list/status/snapshot`), 107 unit + integration tests
- **Streaming Phase 1 runtime** — `StreamPublisherFactory` and `StreamAccessFactory` (ResourceFactory SPI), `StreamPublisherImpl` with partition-key routing or round-robin, `PartitionedStreamAccess` with cross-partition fetch and consensus cursor checkpointing, `StreamConsumerAdapter` for single-event and batch handlers, `StreamConfigParser` for blueprint `[streams.xxx]` TOML sections
- **CDM stream integration** — stream creation from blueprint config during deployment, consumer subscription registration at slice activation via KV-Store, unsubscription on deactivation
- **QUIC certificate rotation** — `CertificateRenewalScheduler` wired to node startup, triggers at 60% remaining validity, exponential retry backoff (5min→4h cap), server restart on same port with atomic SSL context swap
- **HTTP server certificate rotation** — ManagementServer and AppHttpServer receive renewed bundles and restart with new TLS contexts (H1 + H3)
- **Certificate expiry observability** — `GET /api/certificate` endpoint, `aether cert status` CLI command, expiry timestamp and renewal status
- **QUIC per-stream backpressure queue** — bounded queue (100 per peer per stream type) replaces silent drop, drain on channel writable, queue depth metrics
- **Declarative cluster management** — `aether-cluster.toml` config format with `[deployment]` + `[cluster]` sections, config parser with 14 validation rules, diff engine with field-to-action matrix, `aether cluster` CLI (bootstrap, apply, status, export, scale, upgrade, drain, destroy, list, use, remove), cluster registry (`~/.aether/clusters.toml`), cloud-init user-data template, KV-Store config storage with optimistic concurrency, 5 management API endpoints
- **Gossip key rotation** — `RotatingGossipEncryptor` with VarHandle hot-swap, epoch-day versioned keys from `SelfSignedCertificateProvider`, KV-Store `GossipKeyRotationHandler` for cluster-wide key distribution, 24-hour dual-key overlap window
- **TLS operator guide** — comprehensive documentation: auto-generated certs, manual cert files, rotation lifecycle, monitoring, gossip encryption, troubleshooting
- **On-premises SSH bootstrap** — `SshBootstrapOrchestrator` with per-node Docker deployment, `DockerComposeGenerator` for single-host testing, `SystemdUnitTemplate` for JVM deployments, `RemoteCommandRunner` (ProcessBuilder SSH/SCP), `--compose-only` flag
- **Notification hub example** — two-slice example exercising streaming + per-route security + Principal injection end-to-end
- **PostgreSQL LISTEN/NOTIFY resource** — `PgNotificationSubscriber` with dedicated connection, multi-channel config (`[pg-notifications.xxx]`), `PgNotification(channel, payload, pid)`, annotation processor detection, comprehensive developer guide
- **Dashboard observability** — depth registry config UI (inline edit, add/remove rules) + invocation requests tab (sortable metrics, slow requests, traces, filters)
- **Integration test suite** — 14 suites, 56 Docker-based test scripts (smoke, stability, chaos, scaling, streaming, security, deployment, cluster-mgmt, resources, artifacts, database, observability, network, edge-cases)
- **Installation binaries** — jlink custom JRE + shaded JAR bundles for node/cli/forge, multi-platform archives (linux-amd64, linux-arm64, darwin), platform-aware install.sh/upgrade.sh

- **Streaming Phase 2** — Governor-push replication (fire-and-forget with watermark tracking), strong consistency (Rabia consensus produce path for total ordering), sealed segment pipeline (EvictionListener → SegmentSealer → StorageSegmentSink → SegmentReader), consumer read-preference (LEADER/NEAREST/FOLLOWER_ONLY), governor failover recovery (watermark-based replica catch-up), tier-aware retention (aggressive post-seal eviction)
- **AHSE Phase 2** — RemoteTier (S3-backed StorageTier with SigV4 REST client), ContentStore (auto-chunking API with manifest blocks, compression integration), DemotionManager (4 eviction strategies: AGE/LFU/LRU/SIZE_PRESSURE, dormant/active lifecycle), StorageGarbageCollector (orphan collection with grace period, dormant/active), PromotionManager (frequency-based cold-to-hot promotion), write-behind policy (async slow-tier writes with bounded queue), cross-node prefetching (SWIM-piggybacked access hints)
- **AHSE Phase 3** — LZ4/ZSTD compression pipeline, AES-256-GCM block encryption, StorageBackedPersistence (ContentStore-backed RabiaPersistence)
- **S3 REST client** — SigV4-signed S3-compatible client in `integrations/cloud/aws/s3` (PutObject/GetObject/DeleteObject/HeadObject/ListObjectsV2, path-style MinIO support)
- **Architectural compliance** — dormant/active lifecycle on all background workers, KV-Store persistence abstractions (WatermarkStore, ReplicaAssignmentStore, TombstoneStore), SegmentIndex rebuild from storage refs, control-plane delegation investigation updated
- **Integration test metrics** — opt-in thread/heap/RSS collection before+after each test (`COLLECT_METRICS=true`)
- **Integration test README** — comprehensive setup guide, architecture docs, test-writing examples
- **Soak test exclusion** — `SKIP_SOAK=true` (default) in `run-all.sh` and `run-suite.sh` to skip long-running soak tests

### Removed
- **`-XX:+ZGenerational`** — removed from all JVM configurations (Java 25 makes generational ZGC the default)
- **`-XX:+UseCompactObjectHeaders`** — removed from all JVM configurations (no measurable impact in benchmarks)

### Changed
- **java-peglib 0.2.1** — parser regenerated, all 35 lint rules updated for new CST shape (ordered-choice container wrapping)
- **ConsumerConfig** — added `checkpointIntervalMs`, `maxRetries`, `deadLetterStream` fields (backward compatible)
- **StreamConfig** — added `maxEventSizeBytes` field with enforcement in `StreamPartitionManager.publishLocal()`
- **Nullable AtomicReference eliminated** — `CancellableTask` (VarHandle, 9 usages), `StoppableThread` (VarHandle, 4 usages), `AtomicHolder<T>` (VarHandle, 4 usages) in `core/` replace all `getAndSet(null)` patterns
- **Docker image base** — switched from `eclipse-temurin:25-alpine` to `eclipse-temurin:25-noble` (glibc required by netty-quiche native library)
- **SSH bootstrap** — Docker bridge network with container hostnames instead of `--network host`, env-var-based config (PEERS, CLUSTER_PORT, MANAGEMENT_PORT), `$HOME/aether` paths instead of `/opt/aether`
- **Docker config** — `repositories = ["builtin"]` (DHT is fully distributed; `local` fallback removed)
- **Integration test assertions** — cluster health checks use `/api/status` instead of `/health/ready` and `/api/nodes`
- **CLI global output formatting** — `--format` (json/table/value/csv), `--field` (dot-notation extraction), `--quiet`, `--no-color` / `NO_COLOR` env var on all ~100 commands via picocli mixin
- **CLI Jackson migration** — replaced hand-rolled JSON parsing with `JsonMapper` tree API; deleted `SimpleJsonReader`, `formatJson()`, `extractJsonString()` and duplicates
- **CLI standardized exit codes** — `SUCCESS=0`, `ERROR=1`, `TIMEOUT=2`, `NOT_FOUND=3` across all commands
- **CLI TLS support** — `--tls-skip-verify` / `-k` flag with trust-all SSL; scheme-aware URL resolution
- **CLI shell completions** — `aether generate-completion` for bash/zsh/fish; auto-install in `install.sh`
- **JsonMapper tree API** — `readTree()`, `extractField()`, `prettyPrint()` methods added to jackson integration module

### Changed
- **Cluster-wide `/api/slices`** — returns all slices across all nodes with per-node instance states, target counts, and version; old per-node behavior moved to `/api/node/slices`
- **Per-node route endpoint** — `/api/routes` moved to `/api/node/routes` for naming consistency
- **CLI `slices` command** — now shows cluster-wide view; added `node-slices`, `routes`, `node-routes` commands

### Added
- **Standalone passive load balancer** — `aether-lb.jar` shaded binary with `--peers`, `--http-port`, `--cluster-port` CLI args, joins cluster as PassiveNode, routes HTTP via binary protocol; includes Dockerfile
- **Passive node KV-Store snapshot sync** — passive nodes (LB) receive full KV-Store state on cluster join via `KVSyncRequest`/`KVSyncResponse`; LB works regardless of when it starts relative to blueprint deployment
- **Stream auto-creation on publish** — `POST /api/streams/{name}/publish` auto-creates stream with default config if it doesn't exist; follows Kafka `auto.create.topics.enable` pattern
- **Stream creation endpoint** — `POST /api/streams` for explicit stream creation with configurable partition count

### Changed
- **java-peglib 0.2.0** — PEG parser generator bumped from 0.1.8, Java25Parser regenerated (-2,940 lines net)

### Fixed
- **CLI version** — was hardcoded at 0.19.2, now correctly shows 0.25.0
- **`@CodecFor` annotation processor** — two-pass processing: register all types first, then generate codecs (fixes ordering issues); generates codecs for external records and enums
- **Java 25 ambiguous method reference** — `ListenNotifyTest.subscribe()` lambda → method reference for stricter overload resolution
- **Streaming API** — REST publish failed with "Stream not found" because streams were only created lazily by slice factories, not available via management API
- **Stream publish payload** — changed from base64-only to raw UTF-8 string for simpler management API usage
- **Stream memory allocation** — management API streams use 16MB default (was 1GB per stream, crashing containers)
- **Stream memory cap** — `StreamPartitionManager` enforces 128MB global off-heap cap, rejects new streams when exceeded instead of OOM crash
- **Idle stream reaper** — `reapIdleStreams()` destroys empty streams past retention age, freeing off-heap memory
- **Integration test `api_post`/`app_post`** — bash brace expansion bug `"${2:-{}}"` appended extra `}` to all POST bodies
- **Integration test suite** — API key auth (`aether-integration-test-key` default), correct url-shortener endpoints (`/api/v1/urls/`), stream payload format (`data` as string), stream info JSON parsing, concurrent deploy test, reduced streaming load duration (30s from 300s)
- **Autoscaler noisy log** — scale-down rule logged at INFO every 5s even when blocked by min-instances guard; changed to DEBUG
- **Java 25 TLS compatibility** — RSA self-signed certs for dev mode, BouncyCastle PEMParser for EC key loading (preserves named curve encoding for BoringSSL), explicit BC KeyFactory in `SelfSignedCertificateProvider`
- **Schema migration lock failover** — new leader scans for MIGRATING schemas with expired locks and resets to PENDING
- **Dashboard schema retry button** — FAILED migrations can be retried from dashboard UI
- **Certificate rotation race condition** — SSL contexts updated before server stop, eliminating null-server window

## [0.24.1] - 2026-03-25

### Added
- **ClusterTopologyManager** — new node lifecycle manager with reconciliation state machine (FORMING → CONVERGED ↔ RECONCILING). Handles auto-heal, scale-up/down, quorum safety. Replaces fragile boolean flags in CDM with clean state transitions. Single action path for all node count changes
- **Consensus-driven topology discovery** — Hello handshake carries node address; ON_DUTY lifecycle notifications trigger topology additions. Dynamically provisioned nodes become visible to all cluster members via consensus, not just the provisioning leader
- **Per-route security** — routes.toml `[security]` section with per-route policies (public/authenticated/role:name), type-safe `RouteSecurityPolicy` interface with `canAccess()`, `SecurityPolicy` sealed variants in Aether, route-level enforcement in AppHttpServer (per-route wins over global SecurityMode)
- **Principal/SecurityContext injection** — slice handler methods can declare `Principal` or `SecurityContext` parameters; code generator injects from `SecurityContextHolder` automatically
- **Blueprint security overrides** — operators can override route security at deploy time via `[security.overrides]` in blueprint.toml with `strengthen_only`/`full`/`none` policies
- **QUIC transport metrics** — `QuicTransportMetrics` with active connections, handshakes, messages sent/received, write failures, backpressure drops; exposed via `/api/metrics/transport`
- **Per-route request metrics** — Micrometer counters and timers per route pattern; security denial counters with denial type classification
- **Dashboard route security badges** — Routes panel on Deployments page shows security policy per route
- **Config validation warnings** — blueprint parser warns on unrecognized TOML sections
- **Streaming lifecycle operations spec** — §16 added to in-memory streams spec: replica count change, repartitioning, stream deletion, migration patterns

### Changed
- **Topology management refactored** — `TcpTopologyManager` renamed to `TopologyObserver` (pure observation); new `ClusterTopologyManager` wraps observer and manages cluster size. CDM no longer owns node provisioning
- **`NodeLifecycleValue` carries address** — host/port included in ON_DUTY registration for consensus-driven node discovery (backward compatible with old format)
- **`RouteSecurityPolicy` renamed to `SecurityPolicy`** — moved from transport-level to intent-based (Public, Authenticated, ApiKeyRequired, BearerTokenRequired, RoleRequired); extends generic `RouteSecurityPolicy` from http-routing layer
- **`[security]` section optional** — routes.toml without `[security]` defaults to PUBLIC with STRENGTHEN_ONLY policy (backward compatible)
- **Security validators handle all policy variants** — ApiKeySecurityValidator and JwtSecurityValidator now handle Authenticated and RoleRequired in addition to their primary types
- **Route security in KV-Store** — `NodeRoutesValue.RouteEntry` carries security field; serialization is backward compatible with old format

### Fixed
- **Node auto-heal** — killed nodes automatically replaced via ComputeProvider; batch provisioning proportional to deficit; quorum safety (never below 3); ON_DUTY health check before considering provision complete; leader failover detects ready nodes via consensus
- **Node departure healing** — SWIM FAULTY routes `RemoveNode` to topology manager; QUIC disconnect routes `RemoveNode` for passive LB; CDM rebuilds state before cleanup; sequential reconciliation prevents consensus batch collisions
- **Reconnection storm eliminated** — `ConnectionFailed` routed to topology manager for exponential backoff; reconciliation loop is sole reconnection driver; new nodes bypass ConnectionDirection for initial join
- **QUIC write failures detected** — `writeAndFlush()` listener detects failures, removes stale links, triggers reconnection
- **QUIC DataHandler error containment** — `exceptionCaught()` closes channel; deserialization wrapped in try-catch to prevent single malformed message from killing connection
- **QUIC write backpressure** — writability check before write; `WriteTimeoutHandler(10s)` in stream pipelines
- **QUIC Hello deserialization safety** — try-catch in both server and client Hello handlers
- **SecurityMode=NONE + authenticated route** — returns clear 401 "Route requires authentication but no security mode is configured" instead of vague error
- **WWW-Authenticate header** — no longer sent when SecurityMode=NONE (was misleadingly advertising ApiKey)
- **WebSocket auth timeout** — sends AUTH_TIMEOUT message before closing instead of silent disconnect
- **Overlapping route detection** — compile WARNING when two routes have same method+path pattern
- **Invocation metrics strategy** — returns 501 Not Implemented with clear message; CLI explains limitation

## [0.24.0] - 2026-03-24

### Added
- **QUIC cluster transport** — replaces TCP for all inter-node communication. Stream-per-message-type multiplexing (consensus stream 0, KV stream 1, HTTP forward stream 2, DHT stream 3), mandatory TLS 1.3 with auto-generated self-signed certs for dev, 0-RTT reconnection, connection migration, NodeId-ordered connection initiation. First Java distributed runtime on QUIC
- **Soak test infrastructure** — 4-hour k6 sustained load scenario with chaos injection phases (worker kill, rolling restart), Prometheus + Grafana monitoring with 14-panel auto-provisioned dashboard, automated pass/fail verdict (6 criteria: heap growth, GC pause, P99 drift, error rate, SWIM stability, node count), markdown report generation

### Changed
- **SWIM port offset** — changed from cluster_port+1 to cluster_port+100 to avoid port collisions in multi-node Forge
- **Passive LB** — uses own event loop groups instead of TCP server groups (QUIC has no TCP server)

### Fixed
- **QUIC message delivery** — DataHandler replaces Hello handler after handshake; messages were silently dropped post-Hello
- **QUIC message framing** — added LengthFieldBasedFrameDecoder to QUIC stream pipelines; QUIC streams are byte-oriented like TCP, not message-framed
- **QUIC message routing** — incoming messages now routed to MessageRouter via onMessageReceived callback
- **QUIC idle timeout** — disabled per RFC 9000 §10.1; peer-to-peer connections died after 30s of no traffic between consensus rounds (only leader→peer had regular MetricsPing traffic)
- **Passive LB event loop** — uses own groups instead of TCP server groups (QUIC has no TCP server)

## [0.23.1] - 2026-03-24

### Added
- **AppHttpServer: configurable request size limits** — `max_request_size` in TOML with `DataSize` parser (KB/MB/GB), 413 response when exceeded
- **AppHttpServer: multipart file upload** — `FileUpload`, `MultipartRequest` records, Netty `HttpPostRequestDecoder` integration, `RequestContext.multipartRequest()` accessor
- **AppHttpServer: API token auth** — `SecurityMode` config (none/api-key/jwt), reuses management RBAC infrastructure, `SecurityContextHolder` ScopedValue propagation to slice handlers, health endpoint bypasses auth
- **AppHttpServer: JWT auth with JWKS** — `JwtSecurityValidator`, `JwtTokenParser`, `JwtSignatureVerifier` (RS256/ES256), `JwksKeyStore` with TTL cache, clock skew tolerance — pure JDK crypto, no external JWT libraries
- **AppHttpServer: HTTP/3 via Netty QUIC** — `Http3Server` with dual-stack H1+H3 support, `QuicSslContextFactory`, `Alt-Svc` header for protocol upgrade hints, `HttpProtocol` config enum
- **Dashboard: new panels** — schema migration status, governor/community, deployment strategies (canary/blue-green/A/B), streams, cluster composition (core/worker counts)
- **Operational audit events** — 7 event types in cluster event stream (AccessDenied, NodeLifecycleChanged, ConfigChanged, BackupCreated/Restored, BlueprintDeployed/Deleted)
- **`@CodecFor` annotation** — compile-time + runtime codec validation for external types. Manual codecs required (no auto-generation), `REQUIRED_TYPES` validated at startup. Three-layer safety net: compile-time field check, `@CodecFor` declaration, runtime startup validation. Eliminates silent serialization failures permanently
- **Codec processor compile-time field validation** — ERROR for `@Codec` records with unregistered field types
- **ManagementServer HTTP/3** — dual-stack H1+H3 support matching AppHttpServer, `management_protocol` TOML config
- **NettyHttpOperations** — HTTP/1.1 + HTTP/3 client via Netty QUIC, alternative to JDK HttpClient. Full HTTP/3 stack (server + client) complete
- **Manual codecs for core types** — TimeSpan, Email, Url, NonBlankString, Uuid, IsoDateTime registered in NodeCodecs via `@CodecFor` with hand-written codecs

### Fixed
- **Dashboard: 24 audit issues** — alert data unwrapping, ALERT_RESOLVED broadcast, INITIAL_STATE node population, per-node metrics, real P50/P95/P99 percentiles, time range selector, WS auth, REST auth headers, error toasts, per-channel WS status, topology diffing, success rate chart Y-axis, latency panel after tab switch
- **JBCT compliance: 40+ issues** — constant-time API key comparison, generic error messages to clients, unknown role defaults to VIEWER, Result.lift for exception boundaries, Option for null policy, AtomicReference for thread safety, @Contract for lifecycle methods
- **`@Codec` on `AuthorizationRole`** — fixes serialization failure in HTTP forwarding
- **Pre-existing codec issues** — TimeSpan, MethodName, ExecutionMode, KVCommand, Blueprint, NodeLifecycleState, SchemaStatus all now have proper codec registration via `@Codec` or `@CodecFor`

## [0.23.0] - 2026-03-23

### Added
- **In-memory streaming (preview)** — ordered, replayable, consumer-paced streaming as a first-class Aether resource
  - `StreamPublisher<T>`, `StreamSubscriber`, `StreamAccess<T>` — slice-developer API with `@PartitionKey` annotation for partition routing
  - `OffHeapRingBuffer` — off-heap ring buffer using `MemorySegment` with circular wrap-around and retention eviction (count/size/age)
  - `StreamPartitionManager` — governor-local produce/consume with per-stream partition management
  - Annotation processor: detects stream resources, generates manifest entries, envelope format v7
  - `StreamConsumerRuntime` — push-based delivery with RETRY (exponential backoff), SKIP, and STALL error strategies
  - `DeadLetterHandler` — in-memory dead-letter storage for failed events
  - REST API: `GET /api/streams`, `GET /api/streams/{name}`, `POST /api/streams/{name}/publish`, `GET /api/streams/{name}/{partition}/read`
  - CLI: `aether stream list`, `aether stream status`, `aether stream publish`
  - KV-Store types for stream metadata, partition assignments, cursor checkpoints
  - 140+ tests across the streaming stack

## [0.22.0] - 2026-03-23

### Added
- **RBAC Tier 2 — role-based authorization** — three hierarchical roles (ADMIN/OPERATOR/VIEWER) with per-route enforcement in the management API pipeline. RoutePermissionRegistry resolves permissions by HTTP method and path prefix. 403 Forbidden for authorization failures. TOML config `authorization_role` field on API keys (defaults to ADMIN for backward compat). Independent security audit passed clean — all 40+ mutation routes verified
- **Operational audit events in cluster event stream** — 7 event types (AccessDenied, NodeLifecycleChanged, ConfigChanged, BackupCreated, BackupRestored, BlueprintDeployed, BlueprintDeleted) routed through ClusterEventAggregator alongside existing DeploymentEvent and SchemaEvent
- **Audit trail expansion** — AuditLog calls added to all mutation paths: schema migration lifecycle, CDM scaling decisions, config changes, backup/restore, node lifecycle transitions, blueprint deploy/undeploy

### Changed
- **Feature catalog updated** — reflects 0.21.1/0.21.2 additions, backup/restore contradictions resolved, statistics updated (145 features: 24 battle-tested, 113 complete)

## [0.21.2] - 2026-03-22

### Added
- **Schema migration failure recovery** — automatic retry with exponential backoff (5s/15s/45s) for transient failures, manual retry via `POST /api/schema/{ds}/retry` and `aether schema retry` CLI command
- **Schema migration events** — structured `SchemaEvent` hierarchy (MigrationStarted, MigrationCompleted, MigrationFailed, MigrationRetrying, ManualRetryRequested) with natural language explanations suitable for both human operators and LLM agents
- **Failure classification** — transient (connection timeout, lock contention) vs permanent (SQL syntax, checksum mismatch) with appropriate retry behavior
- **`schema_required` blueprint config** — `[deployment]` section option to skip schema migration gate, allowing slices that don't need schema to deploy immediately

## [0.21.1] - 2026-03-22

### Added
- **Docker scaling test infrastructure** — 5-core + 7-worker Docker Compose setup with phase-based orchestrator, k6 load tests (steady-state + scaling verification), Maven protocol artifact upload
- **CORE_MAX env var** — Docker containers configure core/worker role via environment variable instead of per-node TOML
- **X-Node-Id header on all HTTP responses** — enables k6 to verify traffic distribution across nodes
- **SWIM startup delay** — configurable cooldown after quorum (default 10s) before first probe, allowing TCP connections to stabilize
- **SWIM revival grace period** — recently-revived members skip probing for configurable duration (default 5s)

### Changed
- **SharedScheduler consolidation** — migrated 10 production schedulers to SharedScheduler (min 8 platform threads), eliminating thread pool proliferation across SWIM, CircuitBreaker, Retry, canary evaluation, and heartbeat
- **SWIM transport uses Netty built-in DnsNameResolver** — replaced custom DomainNameResolver with Netty's native DNS resolver, eliminating Promise chain overhead in the send path. DNS resolution stays entirely within Netty's event loop
- **SWIM logging levels** — recv messages at TRACE (was INFO), SUSPECT/FAULTY at WARN (was INFO)
- **SwimConfig uses TimeSpan** — replaced Duration with TimeSpan, relaxed defaults for Docker (period=1s, probeTimeout=800ms, suspectTimeout=15s)
- **PiggybackBuffer dissemination counting** — changed from drain-on-read to peek-and-age with configurable max disseminations, preventing premature update loss

### Fixed
- **InetSocketAddress codec missing** — no codec was registered for `InetSocketAddress`, causing silent serialization failure for ALL SWIM Ping/Ack messages with piggybacked membership updates. Every probe timed out, causing universal SUSPECT cascade. Root cause of all SWIM flapping
- **SWIM relay sequence collision** — relay Pings reused original requester's sequence number, colliding with local probes. Fixed with dedicated relay sequence and RelayInfo mapping
- **SWIM PingReq sender address** — handlePingReq looked up requester from member list (hostname-based, possibly missing). Fixed by passing actual UDP sender address
- **SWIM relay cleanup** — age-based expiry instead of pendingProbes presence check (which removed ALL relays since relay sequences are never in pendingProbes)
- **SWIM state priority enforcement** — FAULTY > SUSPECT > ALIVE at same incarnation prevents stale ALIVE piggyback from overriding SUSPECT
- **SWIM round-robin probing** — deterministic member selection instead of random, ensuring all members probed equally
- **SWIM FAULTY member cleanup** — bounded growth with 3× suspectTimeout eviction threshold
- **SWIM incarnation bump on Ack** — prevents stale SUSPECT piggyback from re-suspecting a node that just responded
- **Schema migration concurrency** — local deduplication via inFlightMigrations Set prevents duplicate migrations from concurrent KV-Store notifications
- **AppHttpConfig wiring** — Main.java now reads `[app-http]` TOML section and calls `withAppHttp()`
- **ConfigurationProvider wiring** — Main.java now builds and wires ConfigurationProvider from TOML file
- **Missing SqlConnector factories in node JAR** — added resource-db-async and resource-db-jdbc dependencies
- **GossipEncryptor race condition** — resolved at quorum time instead of assembly, when certificate provider is initialized

## [0.21.0] - 2026-03-21

### Added
- **Per-datasource schema migration engine** — full migration execution engine with Flyway-style versioned (V), repeatable (R), undo (U), and baseline (B) migration types. Schema history tracked in `aether_schema_history` table per datasource. Checksum validation, transactional per-script execution, configurable failure/failover policy
- **Schema orchestration** — distributed coordination layer with consensus-based locking, artifact resolution, and status tracking (PENDING → MIGRATING → COMPLETED/FAILED). CDM integration gates slice deployment on schema readiness
- **Schema management REST API and CLI** — REST endpoints (`/api/schema/status`, `/api/schema/migrate/{ds}`, `/api/schema/undo/{ds}`, `/api/schema/baseline/{ds}`) and CLI commands (`aether schema status|history|migrate|undo|baseline`)
- **Schema directory convention** — `schema/` root maps to default `[database]` config section (matching `@Sql`), subdirectories map to `[database.<name>]` sections. Single-datasource slices need no subdirectory
- **Schema migration executes end-to-end** — DatasourceConnectionProvider provisions SqlConnector per datasource, wiring migration engine to actual database execution
- **Strict datasource resolution** — missing config section causes explicit failure with descriptive error; no silent fallback or derivation
- **Removed embedded H2 from Forge** — Forge no longer provides an embedded H2 database; external PostgreSQL required via `start-postgres.sh`
- **Schema migration prerequisites** — `start-postgres.sh` scripts create the required database; migration engine requires pre-existing databases (creates tables, not databases)
- **Blueprint artifact auto-packaging** — `generate-blueprint` goal now automatically packages the blueprint JAR (no need to add `package-blueprint` explicitly). Schema directory default changed to `${project.basedir}/schema`
- **Forge artifact-based deployment** — `--blueprint` accepts artifact coordinates with classifier (`groupId:artifactId:version:classifier`). Forge resolves via configured Repository chain (local Maven repo in dev, DHT in production). TOML deployment path removed
- **Enriched `/api/nodes` endpoint** — now returns role (CORE/WORKER) and isLeader flag per node, with role sourced from `ActivationDirectiveValue` in KV-Store
- **`GET /api/cluster/governors` endpoint** — exposes governor announcements from KV-Store: governor ID, community, member count, and member list

### Deployment Strategies
- **Canary deployments** -- Progressive traffic shift with configurable stages (1% -> 5% -> 25% -> 50% -> 100%), auto-evaluation every 30s, health-based auto-rollback, KV-Store persistence, leader failover recovery
- **Blue-green deployments** -- Atomic traffic switchover (~100ms via single Rabia round), drain period, instant switch-back for rollback, 2x resource usage during transition
- **A/B testing** -- Deterministic traffic split by request context (header hash, cookie hash, header match, percentage), ScopedValue-based variant propagation through invoke chains, per-variant metrics collection
- **Deployment strategy coordinator** -- Mutual exclusion (one strategy per artifact), unified routing lookup for all strategies
- **HTTP version-aware routing** -- AppHttpServer checks deployment strategy routing before serving locally, forwards to remote node when weighted decision routes to other version
- **Blueprint deployment config** -- Optional `[deployment]` TOML section for strategy selection and configuration

### Changed
- **Deployment event aggregator — KV-Store driven** — deployment events (STARTED/COMPLETED/FAILED) now derived from `NodeArtifactKey` KV-Store notifications instead of manually injected local messages. All nodes see all deployment events. Deployment duration tracked from LOAD→ACTIVE, node join-to-first-deployment timing included
- **Jackson 3.1.0 LTS** — bumped from 3.0.3, annotations from 2.20 to 2.21
- **JBCT review compliance** — SharedScheduler for canary evaluation (was shutdownNow), AtomicBoolean for SliceInvoker.stop(), immutable FailoverContext collections, AB→Ab rename (acronym-as-word), factory methods for all value objects, Option for null policy, deployment audit logging via AuditLog, void helper suppressions
- **Role-aware unified AetherNode** — merged WorkerNode into AetherNode. Single `aether-node.jar` binary for both CORE and WORKER roles. Consensus observer mode (receives Decisions without voting), `ForwardingClusterNode` for transparent KV write forwarding, `SwitchableClusterNode` for runtime role switching. WORKER→CORE promotion supported. `aether/worker` module eliminated — components ported to `aether/node` and `aether-metrics`
- **Quorum fix for mixed clusters** — when `coreMax > 0`, consensus quorum calculated against core node count only (not total nodes including workers)
- **KV-commit-driven allocation/deallocation** — slice allocation and deallocation now triggered exclusively by KV-Store commit notifications (`onSliceTargetPut`/`onSliceTargetRemove`), eliminating double-allocation race in blueprint handler
- **ReconciliationAdjustment events** — CDM emits scaling events to cluster event stream when reconciliation adjusts instance counts

### Fixed
- **Deployment flow audit** — comprehensive CDM/NDM handoff audit: schema migration gate blocks ACTIVATE until migrations complete, exclusive schema lock acquisition prevents split-brain races, allocation index bounds check prevents IOOBE, drain eviction excluded from reconciliation, retry counters scoped to (artifact, node), optimistic sliceStates write removed, stuck timeout multiplier increased to 3×, blueprint stores combined into single consensus batch
- **Timeout failure misclassification** — `updateSliceStateWithRetry` re-classified already-classified failures through a string round-trip, converting transient timeouts (`CoreError.Timeout` → `Intermittent`) into fatal errors (`Fatal.UnexpectedError`). Pre-classified `failureReason` and `fatal` flag now passed directly to `NodeArtifactValue`
- **Consensus pipeline saturation during activation** — all consensus operations in NDM activation chain (topic subscriptions, scheduled tasks, endpoints, cleanup) now use `applyWithRetry` with 30s timeout × 2 retries, matching state transition retry behavior. Previously only `updateSliceStateWithRetry` had retry logic; bare `cluster.apply().timeout()` calls would fail under multi-slice deployment load
- **JBCT compliance across deployment subsystem** — factory methods for `SliceNodeKey`, `SliceDeployment`, `SuspendedSlice`, `ParsedArtifactCoords`; null checks replaced with `Option.option()`; multi-statement lambdas extracted to named methods; `create*Command` renamed to `build*Command`; `seedNodes` changed to `Set.copyOf()`; blueprint iteration snapshot in reconcile; `fold()` replaced with `.map().or()`
- **`coreMax` config wiring** — `core_max` from TOML `[cluster]` section now threaded through ConfigLoader → AetherConfig → AetherNodeConfig → TopologyConfig. Previously always defaulted to 0 (unlimited), preventing worker node assignment
- **Blueprint artifact resolution** — `publishFromArtifact` resolves via configured Repository chain (local Maven, DHT) with explicit classifier support. Clear error on missing classifier
- **Leader election reliability** — `triggerElection()` now defers with retry when called before LeaderManager is active, instead of silently dropping. Fixes flaky leader election in Forge (single-JVM multi-node) where rank-0 node's trigger was lost due to startup race
- **NDM promise chain ordering** — failure/success handlers in loading, activation, deactivation, and unloading chains changed from `onFailure`/`onSuccess` (async) to `withFailure`/`withSuccess` (sequential), preventing state write races
- **Activation timeout alignment** — ACTIVATING stall timeout (90s) and NDM activation chain timeout (90s) aligned; stall detector fires at 3 min (2× multiplier), after NDM has had time to fail and write FAILED state
- **Consensus operation timeouts** — all `cluster.apply()` calls in NDM now have 15s timeout, preventing orphaned Rabia proposals from hanging activation chains forever
- **Double slice allocation** — blueprint handler no longer allocates directly; allocation deferred to `onSliceTargetPut` notification, fixing race where 5 instances were created instead of 3
- **Multi-phase allocation double-write** — `tryAllocate()` now optimistically tracks allocations in `sliceStates`, preventing Phase 2/3 of `issueScaleUpCommands` from re-allocating nodes already assigned in Phase 1 (async `cluster.apply()` hadn't committed yet)
- **Blueprint deletion deallocation** — `handleAppBlueprintRemoval()` now issues deallocation commands before removing artifacts from `blueprints` map; previously deferred to `onSliceTargetRemove` which couldn't find the artifacts because they were already removed
- **SliceState ACTIVATING timeout** — test expected 60s but actual was 90s (aligned after activation timeout changes)
- **Cloud providers — AWS, GCP, Azure** — complete cloud integration for all major providers:
  - `integrations/xml/jackson-xml` — XML mapper module (Jackson XML) mirroring `JsonMapper` pattern, needed for AWS EC2 XML responses
  - `integrations/cloud/aws` — AWS cloud client with SigV4 signing from scratch, EC2 (XML), ELBv2 (JSON), Secrets Manager (JSON). No AWS SDK
  - `integrations/cloud/gcp` — GCP cloud client with RS256 JWT token management, Compute Engine, Network Endpoint Groups, Secret Manager. No GCP SDK
  - `integrations/cloud/azure` — Azure cloud client with dual OAuth2 tokens (management + Key Vault), ARM REST API, Resource Graph KQL, Key Vault. No Azure SDK
  - `aether/environment/aws` — AWS environment integration: EC2 compute, ELBv2 load balancing, tag-based discovery, Secrets Manager
  - `aether/environment/gcp` — GCP environment integration: Compute Engine, NEG load balancing, label-based discovery, Secret Manager
  - `aether/environment/azure` — Azure environment integration: VM compute, LB backend pools, Resource Graph discovery, Key Vault secrets
  - CDM `completeDrain()` now calls `ComputeProvider.terminate()` to stop billing on drained cloud VMs. Tag-based instance lookup via `aether-node-id`. Works uniformly for all providers (Hetzner, AWS, GCP, Azure)
  - `AetherNode` applies `aether-node-id` tag on startup via IP-based self-identification for CDM terminate correlation
  - `ComputeProvider` SPI extended: `provision(ProvisionSpec)` for detailed specs, `listInstances(TagSelector)` typed filter
  - `LoadBalancerProvider` SPI extended: 7 new default methods — `createLoadBalancer`, `deleteLoadBalancer`, `loadBalancerInfo`, `configureHealthCheck`, `syncWeights`, `deregisterWithDrain`, `configureTls`
  - `SecretsProvider` SPI extended: `resolveSecretWithMetadata`, `resolveSecrets` (batch), `watchRotation`
  - `CachingSecretsProvider` — TTL-cached wrapper for any SecretsProvider
  - New SPI types: `ProvisionSpec`, `TagSelector`, `LoadBalancerSpec`, `LoadBalancerInfo`, `HealthCheckConfig`, `TlsTerminationConfig`, `SecretValue`, `SecretRotationCallback`
- **Cloud integration — Hetzner end-to-end** — complete Hetzner Cloud integration for real cloud testing:
  - `SecretsProvider` implementations: `EnvSecretsProvider` (AETHER_SECRET_* env vars), `FileSecretsProvider` (/run/secrets files), `CompositeSecretsProvider` (first-success chain). Zero cloud dependencies, universal fallback
  - `DiscoveryProvider` SPI: label-based peer discovery replacing static TOML peer lists. `discoverPeers()`, `watchPeers()` (polling), `registerSelf()`/`deregisterSelf()`. Wired into AetherNode bootstrap — registers on start, deregisters on graceful shutdown
  - `HetznerDiscoveryProvider`: discovers peers via `aether-cluster` server labels, extracts host/port from private IPs and `aether-port` label, configurable poll interval
  - `ComputeProvider` extensions: `restart()`, `applyTags()`, `listInstances(tagFilter)` with default implementations. Hetzner provider overrides all three using API reboot/label update/label selector
  - `InstanceInfo.tags` field for cloud metadata passthrough (server labels → instance tags)
  - `HetznerClient` extensions: `listServers(labelSelector)`, `updateServerLabels()`, `rebootServer()`, `Server.labels` field
  - `EnvironmentIntegration.discovery()` facet with backward-compatible wiring
- **Blueprint Artifact Transition** — blueprints packaged as deployable JAR artifacts:
  - **Blueprint artifacts**: Blueprints are now packaged as deployable JAR artifacts containing `blueprint.toml`, optional `resources.toml` (app-level config), and optional `schema/` directory (database migration scripts)
  - **`PackageBlueprintMojo`**: New Maven plugin goal (`package-blueprint`) produces classifier `blueprint` JARs with `Blueprint-Id` and `Blueprint-Version` manifest entries
  - **`publishFromArtifact`**: New deployment path — upload blueprint JAR to ArtifactStore, then deploy via `POST /api/blueprint/deploy` or `aether blueprint deploy <coords>`
  - **Config separation**: Application config (`resources.toml`) travels with blueprint at GLOBAL scope; infrastructure endpoints (`[endpoints.*]` in `aether.toml`) stay at NODE scope. ConfigService merges both hierarchically (SLICE > NODE > GLOBAL)
  - **Schema migration prep**: Blueprint artifacts carry `schema/` migration scripts (root `schema/*.sql` maps to `[database]`, subdirectories `schema/<name>/*.sql` map to `[database.<name>]`). End-to-end execution via DatasourceConnectionProvider
  - **New KV types**: `BlueprintResourcesKey/Value`, `SchemaVersionKey/Value`, `SchemaMigrationLockKey/Value` for blueprint resources and schema tracking
  - **CLI commands**: `blueprint deploy <coords>` and `blueprint upload <file>` for artifact-based blueprint deployment
- **Notification resource (Phase 1 — Email)** — three new modules delivering async email notifications:
  - `integrations/net/smtp` — async SMTP client on Netty with STARTTLS, IMPLICIT TLS, AUTH PLAIN/LOGIN, multi-recipient support, connection-per-send. Full state machine (GREETING→EHLO→STARTTLS→AUTH→MAIL FROM→RCPT TO→DATA→QUIT)
  - `integrations/email-http` — HTTP email sender with pluggable vendor mappings via SPI. Built-in: SendGrid, Mailgun, Postmark, Resend. Hand-built JSON/form-data (no Jackson dependency)
  - `aether/resource/notification` — thin Aether resource wiring (`NotificationSender` + `NotificationSenderFactory`). Routes to SMTP or HTTP backend based on config. Exponential backoff retry. `@Notify` resource qualifier annotation for slice injection

## [0.20.0] - 2026-03-17

### Added
- **Scheduled task ExecutionMode** — replaced `boolean leaderOnly` with `ExecutionMode` enum (`SINGLE`, `ALL`). `SINGLE` (default) fires on leader only, `ALL` fires independently on every node with the slice deployed. TOML: `executionMode = "ALL"` in `[scheduling.*]` sections
- **Blueprint pub-sub validation** — deploy-time validation rejects blueprints where a publisher topic has no subscriber. `PubSubValidator` cross-references all publisher/subscriber config sections across all slices in the blueprint. Orphan publishers produce a descriptive error and the blueprint is not deployed
- **Transaction-mode connection pooling** — postgres-async driver now supports `PoolMode.TRANSACTION` which multiplexes N logical connections over M physical connections. Borrows per-query/transaction, returns on completion. Includes prepared statement migration across physical backends, LISTEN/NOTIFY pinning, nested transaction (savepoint) support, and `ReadyForQuery` transaction status parsing. Eliminates need for external PgBouncer
- **Compound KV-Store key types** — `NodeArtifactKey` (replaces per-method EndpointKey + SliceNodeKey) and `NodeRoutesKey` (replaces per-route HttpNodeRouteKey) with compound values. Single writer per node per artifact, ~10x reduction in entry count and consensus commits
- **Hybrid Logical Clock** — new `integrations/hlc` module providing `HlcTimestamp` (packed 48-bit micros + 16-bit counter) and thread-safe `HlcClock` with drift detection, used for DHT versioned writes
- **Cron scheduling** — wired existing `CronExpression` parser into `ScheduledTaskManager` with one-shot+re-schedule pattern. Cron tasks fire at the next matching time, then re-schedule automatically
- **Weeks interval unit** — `IntervalParser` now supports `w` suffix (e.g., `2w` = 14 days) for schedules that cron can't express naturally
- **Pause/resume scheduled tasks** — operators can pause and resume individual scheduled tasks via REST API (`POST .../pause`, `.../resume`) and CLI (`scheduled-tasks pause/resume`). Paused state persisted in KV-Store through consensus
- **Manual trigger** — fire any scheduled task immediately via REST API (`POST .../trigger`) or CLI (`scheduled-tasks trigger`), regardless of schedule or paused state
- **Execution state tracking** — `ScheduledTaskStateRegistry` tracks last execution time, consecutive failures, total executions per task. State written to KV-Store after each execution (fire-and-forget). REST API responses enriched with execution metrics
- **Execution state endpoint** — `GET /api/scheduled-tasks/{config}/{artifact}/{method}/state` returns detailed execution state including failure messages
- **Centralized timeout configuration** — all operator-facing timeouts consolidated into `TimeoutsConfig` with 14 subsystem groups. TOML `[timeouts.*]` sections with human-readable duration strings (`"5s"`, `"2m"`, `"500ms"`). Covers invocation, forwarding, deployment, rolling updates, cluster, consensus, election, SWIM, observability, DHT, worker, security, repository, and scaling. Legacy `_ms` fields (`forward_timeout_ms`, `cooldown_delay_ms`) supported with automatic migration. Reference: `aether/docs/reference/timeout-configuration.md`

### Changed
- **Invocation timeouts reduced** — server-side timeout 25s→15s, client-side invoker timeout 30s→20s. Faster failure detection for stuck invocations
- **Activation chain timeout increased** — 2m→5m to accommodate loading (2m) + activating (1m) with headroom
- **Local repository locate timeout reduced** — 30s→10s (local filesystem operations don't need 30s)
- **Config record field standardization** — all `long *Ms`/`int *Seconds`/`Duration` fields in config records replaced with `TimeSpan`. Affected: `AppHttpConfig`, `WorkerConfig`, `TtmConfig`, `RollbackConfig`, `AlertConfig.WebhookConfig`, `NodeConfig`, `PassiveLBConfig`
- **Control plane KV-Store migration (complete)** — all control plane data migrated from DHT to KV-Store with compound key types. Publishers write only `NodeArtifactKey`/`NodeRoutesKey` (no dual-write). All consumers (EndpointRegistry, DeploymentMap, HttpRouteRegistry, ControlLoop, ArtifactDeploymentTracker, LoadBalancerManager) handle new types via KVNotificationRouter. CDM cleanup uses new key types for stale entry removal. ~10x reduction in consensus commits per deployment
- **WorkerNetwork eliminated** — consolidated inter-worker TCP transport into NettyClusterNetwork (NCN) via PassiveNode's DelegateRouter. Workers now use a single Netty TCP stack instead of two. All inter-worker messaging (mutations, decisions, snapshots, metrics, DHT relay) flows through NCN's `Send`/`Broadcast` messages
- **Server UDP support** — `Server` now supports optional UDP port binding alongside TCP, sharing the same workerGroup (EventLoopGroup). Configured via `ServerConfig.withUdpPort()`. Foundation for future lightweight UDP messaging
- **SWIM sole failure detector** — removed NCN's Ping/Pong keepalive. SWIM is now the only failure detection mechanism. Eliminates redundant probing and simplifies the network layer
- **SWIM shared thread pool** — `NettySwimTransport` can use Server's workerGroup instead of creating a separate `NioEventLoopGroup(1)`. Passed via `CoreSwimHealthDetector` on quorum establishment
- **HTTP server shared EventLoopGroups** — `HttpServer` accepts external `EventLoopGroup` instances via new factory overload. `NettyHttpServer.createShared()` binds on provided groups without owning them (no shutdown on stop). AppHttpServer, ManagementServer, and AetherPassiveLB now share Server's boss/worker groups, reducing per-node thread pools from 6+ to 2
- **Worker module JBCT compliance** — converted 7 types (`MutationForwarder`, `GovernorCleanup`, `DecisionRelay`, `WorkerBootstrap`, `WorkerMetricsAggregator`, `WorkerDeploymentManager`, `GovernorElection`) from final classes/sealed interfaces to JBCT-compliant interfaces with local record implementations. Eliminated Mockito from all 7 worker test files, replaced with simple record stubs
- **DHT versioned writes** — every DHT put now carries an HLC version; storage rejects writes with version <= current, fixing out-of-order state overwrites (e.g., LOADED overwriting ACTIVE)
- **ReplicatedMap local cache** — `NamespacedReplicatedMap` now maintains a `ConcurrentHashMap` local cache with `forEach()` for iteration, enabling CDM to rebuild slice state from DHT
- **CDM state rebuild** — `ClusterDeploymentManager` rebuilds slice state from DHT `ReplicatedMap` instead of consensus KV-Store
- **DHT notification broadcasting** — active nodes broadcast DHT route mutations to passive peers (load balancers) via `DHTNotification` protocol messages

### Fixed
- **CDM reconciliation interval** — ClusterDeploymentManager was incorrectly wired to cluster topology interval (5s) instead of its own 30s deployment reconciliation cycle, causing 6x excessive reconciliation
- **TcpTopologyManager node resurrection race** — `get()`+`put()` pattern in connection failure/established handlers was not atomic; a concurrent `remove()` between the two calls could resurrect a removed node. Fixed with `computeIfPresent()` for atomic read-modify-write
- **Route eviction on node death** — `HttpRouteRegistry.evictNode()` existed but was never wired to `NodeRemoved` topology event. Dead nodes stayed in route tables until CDM's slow consensus-based cleanup completed (60s+). Now evicted immediately on disconnect. Also added `cleanupStaleNodeRoutes()` to periodic reconcile as defense-in-depth
- **NodeLifecycleKey race on restart** — `registerLifecycleOnDuty()` skipped write if key existed, but pending consensus batch could delete the stale key after the check. Now unconditionally writes ON_DUTY (only guards DECOMMISSIONED). Added `onRemove` defense-in-depth handler to re-register if key is unexpectedly removed
- **CDM LOAD command tracking race** — `issueLoadCommand()` put LOAD in `sliceStates` before consensus confirmed, causing phantom instances that blocked reconcile retries. Moved tracking to `withSuccess` callback
- **NDM pending LOAD scan** — NDM now scans KV-Store for pending LOAD commands on activation, catching commands issued by CDM before NDM transitioned from Dormant
- **Worker thread bottleneck offloading** — SWIM `DisconnectNode` routing uses `routeAsync` to avoid blocking shared SWIM thread. `StaticFileHandler` caches classpath resources in `ConcurrentHashMap` to eliminate repeated blocking I/O
- **HTTP forwarding zero-copy bodies** — removed unnecessary defensive `byte[]` cloning from `HttpRequestContext` and `HttpResponseData` constructors and accessors, eliminating ~4 array copies per forwarded request
- **Anti-entropy migration HLC poisoning** — migration data now carries HLC versions and uses `putVersioned()` instead of unversioned `put()` which was storing with `Long.MAX_VALUE`, permanently blocking all subsequent versioned writes to affected keys
- **GitBackedPersistence** — configure git user email/name after `git init` to prevent commit failures on CI runners without global git config
- **ReadTimeoutHandler removed** — Netty `ReadTimeoutHandler` removed from cluster network; SWIM health detection handles peer liveness instead
- **ReplicatedMap async notification race** — `NamespacedReplicatedMap` used `.onSuccess()` (async dispatch) for cache updates and subscriber notifications, causing rapid state transitions (LOADED→ACTIVE) to arrive out of order at CDM. Changed to `.withSuccess()` (synchronous dispatch) to preserve causal write ordering
- **ReplicatedMap subscriber re-entrance** — synchronous notification delivery exposed a re-entrance bug: when subscriber callbacks trigger nested puts (e.g., CDM reacting to LOADED by issuing ACTIVATE), the outer `forEach` continued delivering stale values to later subscribers (DeploymentMap). Replaced with drain loop (trampoline pattern) that enqueues notifications and processes them iteratively, ensuring each state transition is fully delivered to all subscribers before the next begins
- **Full DHT replication for control plane** — AetherMaps now uses `DHTConfig.FULL` replication so all nodes receive all control plane notifications (slice-nodes, endpoints, routes), fixing notification delivery gaps on non-replica nodes
- **Route eviction on node departure** — removed redundant `routeRegistry.evictNode()` call from `HttpForwarder`; DHT cleanup handles route removal
- **RemoteRepositoryTest** — assertion updated to accept both "Download failed" and "HTTP operation failed" error messages after HttpOperations refactor
- **CodecProcessor doubly-nested types** — `@Codec` annotation processor now recursively scans nested helper types inside permitted subclasses (e.g., `RouteEntry` inside `NodeRoutesValue`). Previously only scanned one nesting level, causing `No codec registered` errors at runtime
- **Virtual thread starvation in example tests** — `InMemoryDatabaseConnector` now uses synchronous `Promise.resolved()` instead of async `Promise.lift()` for in-memory operations, preventing carrier thread starvation on low-vCPU CI runners
- **Test await timeouts** — all example test `await()` calls now use 10-second timeouts to prevent indefinite hangs on resource-constrained environments

## [0.19.3]

### Multi-Blueprint Lifecycle
- Fixed critical bug: blueprint deletion now only removes artifacts owned by the deleted blueprint (was removing ALL artifacts)
- Fixed critical bug: `owningBlueprint` field in SliceTargetValue now correctly populated during blueprint deployment
- Added artifact exclusivity enforcement — prevents two blueprints from deploying the same artifact (rejects with descriptive error)
- Added deletion guard — prevents blueprint deletion while its artifacts have active rolling updates
- CDM state restore now correctly populates blueprint ownership from KV-Store
- Added `SliceTargetValue.sliceTargetValue(Version, int, int, Option<BlueprintId>)` factory

### Added
- **Governor mesh advertised address** — governors now announce a routable TCP address instead of hardcoded `0.0.0.0`. Auto-detects via `InetAddress.getLocalHost()` or uses configurable `advertise_address` in `[worker]` TOML section. Fixes cross-host governor mesh connections
- **Event-based community scaling** — governors monitor follower metrics locally and send scaling requests to core only when thresholds are sustained. Zero baseline bandwidth. Architecture:
  - **Worker metrics messages** — `WorkerMetricsPing`/`WorkerMetricsPong` between governor and followers (~100 bytes per pong)
  - **Community scaling messages** — `CommunityScalingRequest` (governor→core, event-driven), `CommunityMetricsSnapshotRequest`/`CommunityMetricsSnapshot` (core→governor, on-demand diagnostics)
  - **CommunityScalingEvaluator** — sliding window (5 samples × 5s default) with sustained-breach detection for CPU, P95 latency, error rate. Per-direction cooldown prevents thrashing
  - **WorkerMetricsAggregator** — governor-side component with periodic ping cycle, follower pong collection, JMX self-metrics, stale cleanup, evaluator integration
  - **ControlLoop community scaling handler** — validates evidence freshness (<30s), checks blueprint existence and cooldown, applies scaling via existing KV-Store path, publishes ScalingEvent
  - **Scaling cap includes workers** — `prepareChangeToBlueprint()` now counts worker nodes in cluster size for scaling cap calculation
  - **ClusterEvent types** — added `COMMUNITY_SCALE_REQUEST` and `COMMUNITY_METRICS_SNAPSHOT` to EventType enum

- **Passive Worker Pools Phase 2a — DHT-Backed ReplicatedMap** — moves high-cardinality endpoint data from consensus KV-Store to DHT, reducing write amplification from O(N) to O(3):
  - **`aether/aether-dht` module** — generic typed `ReplicatedMap<K,V>` abstraction with namespace-prefixed keys, `MapSubscription` event callbacks, `CachedReplicatedMap` (LRU + TTL), `ReplicatedMapFactory`
  - **Community-aware replication** — `ReplicationPolicy` with home-replica rule (1 home + 2 ring replicas = RF=3), `HomeReplicaResolver` for deterministic community-local selection, `ConsistentHashRing` spot-node exclusion filter
  - **Endpoint migration** — `EndpointRegistry` unified with DHT subscription events (core + worker endpoints in single registry), `NodeDeploymentManager` writes endpoints via DHT `ReplicatedMap`, `SliceInvoker` simplified to single-registry lookup
  - **Replication cooldown** — startup RF=1 with background push to RF=3 after configurable delay, rate-limited to prevent boot storm
  - **Governor mesh infrastructure** — `GovernorMesh` and `GovernorDiscovery` for cross-community DHT traffic routing (full wiring in Phase 2b)
  - **DHT node cleanup** — `DhtNodeCleanup` removes dead node endpoints from DHT on SWIM DEAD detection
  - **AetherMaps** — factory for 3 named maps (endpoints, slice-nodes, http-routes) with serializers
- **Worker Slice Execution (P1+P2a Completion)** — end-to-end worker node functionality: slices deployed with `WORKERS_PREFERRED` placement run on worker nodes, publish endpoints to DHT, and SliceInvoker routes traffic to workers:
  - **CDM worker awareness** — ClusterDeploymentManager discovers workers via `ActivationDirectiveKey(WORKER)`, populates `AllocationPool` with worker nodes, writes `WorkerSliceDirectiveKey/Value` directives to consensus for worker slice deployment
  - **PlacementPolicy in SliceTargetValue** — `placement` field (CORE_ONLY, WORKERS_PREFERRED, WORKERS_ONLY, ALL) added to slice target configuration. Management API `POST /api/scale` accepts optional `placement` parameter. CLI: `aether scale --placement`
  - **WorkerDeploymentManager** — sealed interface with Dormant/Active states managing slice lifecycle on workers: watches `WorkerSliceDirectiveKey` from KVNotificationRouter, self-assigns instances via consistent hashing of SWIM members, drives SliceStore load→activate chain, publishes endpoints and slice-node state to DHT
  - **WorkerInstanceAssignment** — deterministic consistent hashing for instance distribution across workers. Same inputs produce same assignment on every worker — no coordination needed
  - **Governor cleanup** — `GovernorCleanup` maintains per-node index of DHT entries (endpoints, slice-nodes, HTTP routes). On SWIM FAULTY/LEFT, governor removes dead node's entries from all three DHT maps. `GovernorReconciliation` runs on governor election to clean orphaned entries
  - **KVNotificationRouter on workers** — workers build notification router on PassiveNode's KVStore to watch `WorkerSliceDirectiveKey` entries, same pattern as AetherNode's notification wiring
  - **SliceNodeKey DHT migration** — SliceNodeKey reads/writes moved from consensus to `slice-nodes` ReplicatedMap. CDM, NDM, ControlLoop, DeploymentMap, ArtifactDeploymentTracker all subscribe via `asSliceNodeSubscription()` adapters
  - **HttpNodeRouteKey DHT migration** — HttpNodeRouteKey reads/writes moved from consensus to `http-routes` ReplicatedMap. HttpRoutePublisher, HttpRouteRegistry, AppHttpServer, LoadBalancerManager all subscribe via `asHttpRouteSubscription()` adapters
  - **WorkerEndpointRegistry removed** — dead code from Phase 1 replaced by DHT-backed endpoint registry. `WorkerRoutes`, `WorkerGroupHealthReport`, `WorkerEndpointEntry` deleted
  - **DHT replication config** — `[dht.replication]` TOML section for `cooldown_delay_ms`, `cooldown_rate`, `target_rf` with environment-aware defaults
- **Container image publishing** — `release.yml` builds multi-arch Docker images (amd64+arm64) via buildx, publishes to GHCR and Docker Hub. SHA256 checksums generated for all release artifacts
- **Upgrade script** (`aether/upgrade.sh`) — detects current version, downloads new JARs to temp dir, verifies SHA256 checksums, atomic binary swap with backup, running process detection
- **Rolling cluster upgrade script** (`aether/script/rolling-aether-upgrade.sh`) — API-driven zero-downtime upgrades: discovers nodes, drains → shuts down → waits for restart → activates → canary checks each node. Supports `--dry-run`, `--canary-wait`, `--api-key`, `--skip-download`
- **Passive worker pools design spec** (`aether/docs/specs/passive-worker-pools-spec.md`) — architecture for scaling to 10K+ nodes: elected governors, SWIM gossip, KV-Store split, auto flat↔layered transition, 3-phase rollout plan
- **Passive worker pools Phase 1** — foundation for scaling beyond Rabia consensus limits (5-9 nodes) with passive compute nodes:
  - **SWIM protocol module** (`integrations/swim/`) — UDP-based failure detection with periodic probes, indirect probing, piggybacked membership updates
  - **Worker node module** (`aether/worker/`) — WorkerNode composes PassiveNode + SWIM + Governor election + Decision relay + Mutation forwarding + Bootstrap
  - **Governor election** — pure deterministic computation (lowest ALIVE NodeId), no election messages exchanged
  - **Worker configuration** — `WorkerConfig` with SWIM settings, core node addresses, placement policy (CORE_ONLY, WORKERS_PREFERRED, WORKERS_ONLY, ALL)
  - **Worker endpoint registry** — non-consensus ConcurrentHashMap-based registry with round-robin load balancing, governor health report population
  - **SliceInvoker dual lookup** — core endpoints first, worker endpoints fallback via governor routing
  - **CDM pool awareness** — `AllocationPool` record, `WorkerSliceDirectiveKey`/`WorkerSliceDirectiveValue` in consensus KV-Store
  - **Worker management API** — `GET /api/workers`, `GET /api/workers/health`, `GET /api/workers/endpoints`
  - **CLI commands** — `aether workers list`, `aether workers health`

- **Multi-Group Worker Topology (Phase 2b)** — workers self-organize into zone-aware groups with per-group governors. Deterministic group computation from SWIM membership — same inputs produce same groups on every worker:
  - **WorkerGroupId** — `(groupName, zone)` identity record with `communityId()` format (`groupName:zone`)
  - **GroupAssignment** — deterministic zone-aware group computation: extracts zone from NodeId, splits zones exceeding `maxGroupSize` via round-robin subgroups
  - **GroupMembershipTracker** — tracks SWIM membership and computes zone-aware groups, exposes `myGroup()`, `myGroupMembers()`, `allGroups()`
  - **Per-group governor election** — governor election scoped to own group members, not all SWIM members
  - **Per-group Decision relay** — governor only relays Decisions to own group followers, reducing broadcast scope
  - **GovernorAnnouncementKey/Value** — governors announce themselves to consensus KV-Store. Core nodes track community sizes and governor identities via `ClusterDeploymentManager`
  - **CDM community-aware placement** — `AllocationPool` extended with `workersByCommunity` map. CDM tracks governor announcements for community-aware instance distribution. End-to-end wiring: CDM distributes instances across communities, writes per-community directives, workers filter by targetCommunity
  - **WorkerSliceDirectiveValue** extended with optional `targetCommunity` for community-scoped deployment
  - **AetherKey community serialization** — `GovernorAnnouncementKey` round-trip through KV-Store backup/restore with pipe-delimited communityId format
  - **Worker configuration** — `WorkerConfig` extended with `groupName` (default `"default"`), `zone` (default `"local"`), `maxGroupSize` (default `100`). TOML: `worker.group_name`, `worker.zone`, `worker.max_group_size`

- **KV-Store durable backup** — serializes cluster metadata (slice targets, node lifecycle, config) to a single TOML file managed in a local git repo. Git provides versioning, history, diffs, and optional remote push for offsite backup
  - **TOML Writer** (`integrations/config/toml`) — serialization support added to the custom TOML library, including inline table parsing
  - **KV-Store serializer** (`aether/slice`) — converts all 18 AetherKey/AetherValue types to/from TOML with pipe-delimited values grouped by key-type sections
  - **Git-backed persistence** (`integrations/consensus`) — `GitBackedPersistence` implements `RabiaPersistence` using git CLI via ProcessBuilder for atomic snapshots
  - **Backup configuration** — `[backup]` TOML section with enabled, interval, path, remote fields and environment-aware defaults
  - **Management API** — `POST /api/backup`, `GET /api/backups`, `POST /api/backup/restore`
  - **CLI commands** — `aether backup trigger`, `aether backup list`, `aether backup restore <commit>`

- **SWIM core-to-core health detection** (P1.13) — replaces TCP disconnect as health signal for core nodes. `CoreSwimHealthDetector` bridges SWIM membership events to `TopologyChangeNotification`. 1-2s failure detection vs 15s-2min with TCP. TCP disconnect no longer triggers topology removal — only SWIM `FAULTY`/`LEFT` does
- **Automatic topology growth** (P1.14) — CDM dynamically assigns core vs worker role to joining nodes. `RabiaEngine` activation gating: seed nodes auto-activate, non-seed nodes wait for CDM authorization. `TopologyConfig` extended with `coreMax`/`coreMin`. New `TopologyGrowthMessage` sealed interface (`ActivateConsensus`, `AssignWorkerRole`). Management API: `GET /api/cluster/topology`. CLI: `aether topology status`
- **E2E test rework: container networking** — replaced dual-mode networking (Linux host / macOS bridge with PID-based port allocation) with standard bridge networking for all platforms. All containers use identical internal ports (8080/8090) and communicate via DNS. Eliminates port conflicts and enables realistic test scenarios
- **E2E test scenarios** — 8 new tests leveraging container networking:
  - `RollingRestartE2ETest` — zero-downtime sequential node restart
  - `SwimDetectionE2ETest` — SWIM failure detection timing bound
  - `NodeDrainE2ETest` — graceful drain lifecycle via management API
  - `NetworkPartitionE2ETest` — minority partition isolation and reconvergence
  - `SliceLifecycleE2ETest` — full deploy/scale/invoke/undeploy cycle
  - `TopologyGrowthE2ETest` — dynamic node addition to running cluster
  - `LoadBalancerFailoverE2ETest` — slice invocation rerouting after failure
  - `LeaderIsolationE2ETest` — leader disconnect recovery without split-brain

### Security
- **Inter-node mTLS** — CertificateProvider SPI with SelfSignedCertificateProvider (BouncyCastle EC P-256, HKDF deterministic CA from shared `clusterSecret`). All TCP transports (consensus, DHT, management, app HTTP) secured with mutual TLS
- **SWIM gossip encryption** — AES-256-GCM symmetric encryption for all SWIM protocol messages. Wire format: `[keyId][nonce][ciphertext+GCM tag]`. Dual-key support for seamless rotation
- **Certificate renewal scheduler** — automatic renewal at 50% of validity (3.5 days for 7-day certs), 1-hour retry on failure
- **Gossip key rotation** — `GossipKeyRotationKey`/`GossipKeyRotationValue` in consensus KV store for coordinated key rotation
- **TLS by default** — DOCKER and KUBERNETES environments enable TLS automatically. `clusterSecret` configurable via TOML `[tls]` section or `AETHER_CLUSTER_SECRET` env var (dev default: `aether-dev-cluster-secret`)
- **Unit tests** — SelfSignedCertificateProviderTest (8 tests: deterministic CA, cert issuance, gossip key), AesGcmGossipEncryptorTest (10 tests: round-trip, dual-key, error cases), TlsConfig fromProvider bridge tests (4 tests)

### Changed
- Dockerfile version labels now use build-arg `VERSION` instead of hardcoded values
- TCP disconnect in `NettyClusterNetwork` no longer fires topology removal — reconnection continues while SWIM handles health detection
- `TcpTopologyManager` never routes `RemoveNode` on connection failure — always continues reconnection with backoff
- Dockerfile source URLs updated to `pragmaticalabs/pragmatica`
- `install.sh` enhanced with `--version` flag, SHA256 checksum verification, WSL2 detection
- Root `install.sh` references `main` branch instead of `release-0.19.3`

### Fixed
- `AetherNode.VERSION` updated from `0.19.0` to `0.19.3`
- `AetherUp.VERSION` updated from `0.7.2` to `0.19.3`
- **SWIM codecs not registered** — `NodeCodecs` was missing `SwimCodecs.CODECS`, causing all SWIM probes to fail silently
- **SWIM false positives during startup** — deferred SWIM start to after quorum establishment to prevent marking alive nodes FAULTY during cluster formation
- **Activation gating** — `isSeedNode()` always returned true because `TcpTopologyManager` requires self in `coreNodes`. Replaced with explicit `activationGated` boolean on `AetherNodeConfig`/`NodeConfig`, passed through to `RabiaEngine`
- **Passive LB false FAULTY** — removed SWIM from passive LB; core nodes don't know about the LB as a SWIM peer, so indirect probes always fail, cascading to false FAULTY for all core nodes. LB gets health info through consensus data stream instead
- **SWIM selfAddress corruption** — `CoreSwimHealthDetector` used `0.0.0.0` as selfAddress, which would corrupt member addresses when piggybacked via SWIM refutation updates. Now uses actual host from topology config

## [0.19.2] - 2026-03-08

### Added
- **`jbct add-slice`** — scaffold new slice into existing project (creates source, test, routes, config, manifest in sub-package)
- **`jbct add-event`** — generate pub-sub event annotations + auto-append messaging config to `aether.toml`
- **`jbct init --version`** — override dependency versions for pre-release testing
- **Unified installer** (`install.sh`) — downloads jbct, aether CLI, and aether-forge
- **Scaffold scripts** — `run-forge.sh`, `start-postgres.sh`, `stop-postgres.sh`, `deploy-forge.sh`, `deploy-test.sh`, `deploy-prod.sh`, `generate-blueprint.sh`
- **ALL_OR_NOTHING deployment atomicity** — default for all blueprint deployments; no partial deploys
- **Blueprint auto-rollback** — on deployment failure, all slices revert to previous state automatically
- **Cause-based deployment retry** — error propagation through KV store with `SliceLoadingFailure` hierarchy
- **Database URL inference** — type, host, and database name inferred from JDBC URL; explicit fields optional
- **Optional database port** — URL-only configuration supported (no separate port field required)
- **Config service factory methods** — record validation via factory methods in config records

### Fixed
- CLI REPL mode with `-c` connection flag now works correctly
- CLI missing `/api/` prefix on 31 management API paths
- Double JSON serialization in management API responses (pre-serialized strings no longer re-wrapped)
- Scale command preserves existing `minInstances` from blueprint
- Rollback route/endpoint/subscription cleanup via `forceCleanupSlice`
- Reactivation failure cleanup — full cleanup chain on slice reload failure
- Topology graph edge routing — links start right, arrows enter left
- `Verify.Is.blank()` null-safe (no longer throws on null input)
- Format-check error message now includes file names
- Slice processor error messages include file reference and slice name
- Domain error recovery from failed Promises in `SliceRouter`
- Infinite reconciliation loop for deterministic deployment failures
- `install.sh` uses semver sort instead of `/releases/latest`

### Changed
- `TimeSpan` instead of `Duration` in `PoolConfig` (plain-number-as-seconds support)
- Partial nested record merge with `DEFAULT` strategy
- HelloWorld scaffold in own subpackage (consistent with `add-slice`)

## [0.19.1] - 2026-03-05

### Added
- **postgres-async integration** — native async PostgreSQL driver wired into Aether resource provisioning
  - `asyncUrl` config field on `DatabaseConnectorConfig` for transport selection (priority 20, preferred over JDBC/R2DBC)
  - `postgres-r2dbc-adapter` module — R2DBC SPI adapter over postgres-async (ConnectionFactory, Connection, Statement, Result, Row, RowMetadata)
  - `db-async` module — `AsyncSqlConnector` using postgres-async directly (zero adapter overhead) with LISTEN/NOTIFY support
  - `db-jooq-async` module — `AsyncJooqConnector` via R2DBC adapter for full jOOQ compatibility
- **Configurable IO threads for postgres-async** — `io_threads` field in `[database.pool_config]` controls Netty event loop thread count. Default `0` = auto-detect (`max(availableProcessors, 8)`). Removes single-thread serialization bottleneck that limited throughput to ~3500 req/s
- **PubSubTest** — Forge-based cross-node pub-sub integration test: deploys url-shortener + analytics slices, verifies click event delivery (single, multi-click, leader failover)
- **Dashboard topology graph** — Deployments tab now shows endpoint→slice→resource data flow graph (SVG, column-based DAG layout). Compile-time topology data: HTTP routes, resources, pub-sub topics extracted from `.manifest` files (envelope v6). REST endpoint `GET /api/topology`, included in WebSocket `INITIAL_STATE`
- **Topology swim-lane layout** — complete rewrite of topology graph renderer with per-slice swim lanes, Manhattan routing for cross-slice topic connectors (right gutter) and dependency edges (left gutter), HSL color-coded topic groups, hover highlighting (dims non-related elements), and search filtering
- **Per-slice topology wire format** — topology nodes carry `sliceArtifact`, edges carry `topicConfig`. Resources and topics are now per-slice (no more shared nodes). Cross-slice pub-sub matching connects all publishers to all subscribers with the same config (many-to-many)
- **Route declaration order preservation** — `RouteConfig`, `RouteConfigLoader`, and `TomlDocument` now preserve TOML declaration order using `LinkedHashMap` instead of `Map.copyOf()`

### Performance
- **postgres-async driver optimizations** — single-buffer DataRow (N+1→3 allocations per row), connection pool lock consolidation (3→1 lock acquisitions per getConnection), static protocol constants, ByteArrayOutputStream elimination in wire protocol parsing. Benchmarked: **50% lower p95 at 2000 req/s** (4.78ms→2.38ms), **35% lower p95 at 5000 req/s** (180ms→117ms)

### Changed
- **E2E test suite reduced from 13 to 2 classes** — removed 11 tests that fully overlap with Forge equivalents (ClusterFormation, NetworkPartition, NodeDrain, SliceDeployment, ManagementApi, SliceInvocation, RollingUpdate, GracefulShutdown, Metrics, Controller, Ttm). Kept ArtifactRepositoryE2ETest (unique DHT coverage) and NodeFailureE2ETest (simplified to 2 focused container-specific tests)
- **Forge tests moved to class-level cluster setup** — 8 test classes converted from per-method to `@BeforeAll/@AfterAll` with `@TestInstance(PER_CLASS)`, reducing ~300 cluster starts to ~50
- **Sleep-based stabilization replaced with health endpoint polling** — removed all `Thread.sleep()` stabilization in Forge tests, replaced with awaitility polling on `/api/health` ready+quorum status
- **CI restructured** — Forge tests run in `build-and-test` job (no Docker needed); E2E job slimmed to 20-min timeout with 2 focused test classes. 5 heavy Forge tests (`@Tag("Heavy")`) excluded from CI 2-core runners
- **NodeFailureE2ETest simplified** — rewritten from 3 ordered shared-cluster tests to 2 independent tests (single node failure + leader failover) extending AbstractE2ETest
- **E2E default cluster size reduced from 5 to 3** — `AbstractE2ETest.clusterSize()` returns 3; NodeFailureE2ETest overrides to 5
- **E2E timeouts reduced** — DEFAULT_TIMEOUT 30→15s, DEPLOY_TIMEOUT 3min→90s, RECOVERY_TIMEOUT 60→30s, QUORUM_TIMEOUT 120→60s, CI multiplier 2.0→1.5
- **Forge pom.xml** — `reuseForks=true` (was false), process timeout 1800s
- **postgres-async tests skipped by default** — all 15 test classes require Testcontainers/Docker; `<skipTests>true</skipTests>` in module pom

## [0.19.0] - 2026-03-02

### Added
- **Ember** — embeddable headless cluster runtime extracted from `forge-cluster` into `aether/ember/` module with fluent builder API (`Ember.cluster(5).withH2().start()`)
- **Remote Maven repositories** — resolve slices from Maven Central or private Nexus repos (`repositories = ["local", "remote:central"]`). SHA-1 verification, local cache to `~/.m2/repository`, auth from `settings.xml`
- **Passive Load Balancer** — cluster-aware `aether/lb/` module: passive node joins cluster network, receives route table via committed Decisions, forwards HTTP requests via internal binary protocol (no HTTP re-serialization). Smart routing to correct node, automatic failover on node departure, live topology awareness
- Load balancer integration in Ember/Forge — auto-starts passive LB on cluster boot, configurable via `[lb]` TOML section
- **NodeRole** — `ACTIVE`/`PASSIVE` roles in `NodeInfo` for cluster membership. Passive nodes excluded from quorum and leader election but receive committed Decisions
- **HttpForwarder** — extracted reusable HTTP request forwarding from `AppHttpServer` into `aether-invoke` module with round-robin selection, retry with backoff, and node departure failover

### Fixed
- `InvocationMetricsTest` — fixed stale factory name `forgeH2Server` → `emberH2Server`
- Passive LB topology bootstrap — self node now included in `coreNodes` list (required by `TcpTopologyManager`)
- Passive LB topology manager lifecycle — `start()` now activates topology manager reconciliation loop, enabling cluster peer connections and Decision delivery

### Changed
- **PassiveNode abstraction** — extracted reusable passive cluster node infrastructure (`DelegateRouter`, `TcpTopologyManager`, `NettyClusterNetwork`, `KVStore`, message wiring) from `AetherPassiveLB` into `integrations/cluster` module (`PassiveNode<K,V>` interface). Follows `RabiaNode` pattern: interface + factory + inline record + `SealedBuilder` routes
- k6 test scripts default to routing through passive LB (`FORGE_NODES` → LB URL). Per-node scripts use `FORGE_ALL_NODES`
- `RepositoryType` converted from enum to sealed interface with `Local`, `Builtin`, and `Remote` record variants
- `forge-cluster` module deleted — all cluster management code now in `aether/ember/` with `Ember*` naming
- `ForgeCluster` → `EmberCluster`, `ForgeConfig` → `EmberConfig`, `ForgeH2Server` → `EmberH2Server`, `ForgeH2Config` → `EmberH2Config`

## [0.18.0] - 2026-02-26

### Added
- **Unified Invocation Observability (RFC-0010)** — sampling-based distributed tracing with depth-to-SLF4J bridge
  - `InvocationNode` trace record with requestId, depth, caller/callee, duration, outcome, hops
  - `AdaptiveSampler` — per-node throughput-aware sampling (auto-adjusts: 100% at low load, ~1% at 50K/sec)
  - `InvocationTraceStore` — thread-safe ring buffer (50K capacity) for recent traces
  - `ObservabilityInterceptor` — replaces `DynamicAspectInterceptor` with sampling + depth-based SLF4J logging
  - `ObservabilityDepthRegistry` — per-method depth config via KV-store consensus with cluster notifications
  - `ObservabilityConfig` — depth threshold + sampling target configuration
  - Wire protocol: `InvokeRequest` extended with `depth`, `hops`, `sampled` fields
  - `InvocationContext` — ScopedValue-based `DEPTH` and `SAMPLED` propagation across invocation chains
  - REST API: `GET /api/traces`, `GET /api/traces/{requestId}`, `GET /api/traces/stats`, `GET/POST/DELETE /api/observability/depth`
  - CLI: `traces list|get|stats`, `observability depth|depth-set|depth-remove`
  - Forge proxy routes for trace and depth endpoints
- Liveness probe (`/health/live`) and readiness probe (`/health/ready`) with component-level checks (consensus, routes, quorum) for container orchestrator compatibility
- RBAC Tier 1: API key authentication for management server, app HTTP server, and WebSocket connections
- Per-API-key names and roles via config (`[app-http.api-keys.*]` TOML sections or `AETHER_API_KEYS` env)
- SHA-256 API key hashing — raw keys never stored in memory
- Audit logging via dedicated `org.pragmatica.aether.audit` logger
- WebSocket first-message authentication protocol for dashboard, status, and events streams
- CLI `--api-key` / `-k` flag and `AETHER_API_KEY` environment variable for authenticated access
- `InvocationContext` principal and origin node propagation via ScopedValues + MDC
- App HTTP server `/health` endpoint (always 200, for LB health checks on app port)
- Node lifecycle state machine (JOINING → ON_DUTY ↔ DRAINING → DECOMMISSIONED → SHUTTING_DOWN) with self-registration on quorum, remote shutdown via KV watch, lifecycle key cleanup on departure
- Disruption budget (`minAvailable`) for slice deployments — enforced in scale-down and drain eviction
- Graceful node drain with CDM eviction orchestration respecting disruption budget, cancel drain support, automatic DECOMMISSIONED on eviction complete
- Management API endpoints for node lifecycle operations (`GET /api/nodes/lifecycle`, `GET /api/node/lifecycle/{nodeId}`, `POST /api/node/drain/{nodeId}`, `POST /api/node/activate/{nodeId}`, `POST /api/node/shutdown/{nodeId}`)
- CLI commands for node lifecycle management (`node lifecycle`, `node drain`, `node activate`, `node shutdown`)
- **Class-ID-based serialization for cross-classloader slice invocations** — deterministic hash-based Fury class IDs eliminate `ClassCastException` across slice classloaders
  - `Slice.serializableClasses()` — compile-time declaration of all serializable types per slice
  - `SliceCoreClasses` — sequential ID registration for core framework types (Option, Result, Unit)
  - `FurySerializerFactoryProvider` rewritten with `requireClassRegistration(true)`, hash-based IDs [10000-30000), recursive type expansion, collision detection
  - Envelope format version bumped to v4

### Fixed
- **Fury → Fory migration** — upgraded from `org.apache.fury:fury-core:0.10.3` to `org.apache.fory:fory-core:0.16.0-SNAPSHOT` (patched fork with cross-classloader fixes)
- Removed speculative `HttpRequestContext` decode from `InvocationHandler` — eliminated `ArrayIndexOutOfBoundsException` during cross-node slice invocations
- Removed debug logging from consensus `Decoder` and `Handler` (InvokeMessage trace noise)
- Removed SLF4J dependency from `slice-processor` annotation processor — eliminated "No SLF4J providers" warning during compilation
- Configurable observability depth threshold via `forge.toml` `[observability] depth_threshold` — set to -1 to suppress trace logging during local development
- `InvocationContext.runWithContext()` signature alignment in `AppHttpServer` and `InvocationContextPrincipalTest` (missing `depth`/`sampled` params)

### Changed
- `examples/url-shortener` upgraded from standalone 0.17.0 to reactor-integrated 0.18.0 (inherits parent POM, managed versions, installable for forge artifact resolution)
- `InvocationMetricsTest` forge integration test: deploys url-shortener multi-slice (UrlShortener + Analytics), generates 1K round-trip requests, validates invocation metrics, Prometheus, and traces across 5-node cluster
- **BREAKING:** Removed `DynamicAspectMode`, `DynamicAspectInterceptor`, `DynamicAspectRegistry`, `DynamicAspectRoutes`, `AspectProxyRoutes` — superseded by Unified Observability
- **BREAKING:** Removed `/api/aspects` REST endpoints and `aspects` CLI command — use `/api/observability/depth` and `observability` command instead
- Removed `DynamicAspectKey`/`DynamicAspectValue` from KV-store types — replaced by `ObservabilityDepthKey`/`ObservabilityDepthValue`
- **BREAKING:** `SerializerFactoryProvider.createFactory()` signature changed from `List<TypeToken<?>>` to `(List<Class<?>>, ClassLoader)` for class-ID-based registration
- Removed `CrossClassLoaderCodec`, `decodeForClassLoader()`, deprecated `sliceBridgeImpl()`/`sliceBridge()` factory methods

## [0.17.0] - 2026-02-23

### Added
- DHT anti-entropy repair pipeline — CRC32 digest exchange between replicas, automatic data migration on mismatch
- DHT re-replication on node departure — DHTRebalancer pushes partition data to new replicas when a node leaves
- Per-use-case DHT config via `DHTClient.scoped(DHTConfig)` — artifact storage (RF=3) and cache (RF=1) use independent configs
- SliceId auto-injection into ProvisioningContext for resource lifecycle tracking
- Scheduled task infrastructure — `ScheduledTaskRegistry`, `ScheduledTaskManager`, `CronExpression` parser, KV-Store types (`ScheduledTaskKey`, `ScheduledTaskValue`), deployment lifecycle wiring, management API (`GET /api/scheduled-tasks`), CLI subcommand, 29 unit tests
- 67 new unit tests: DHTNode (12), DistributedDHTClient (19), DHTAntiEntropy (10), DHTRebalancer (8), ArtifactStore (9), DHTCacheBackend (3), pub-sub (18: TopicSubscriptionRegistry 10, TopicPublisher 4, PublisherFactory 4)
- Blueprint membership guard on `POST /api/scale` — rejects scaling slices not deployed via blueprint
- Blueprint `minInstances` as hard floor for scale-down — enforced in auto-scaler, manual `/api/scale`, and rolling updates
- Pub-sub messaging infrastructure and resource lifecycle management (RFC-0011) — `Publisher<T>`, `Subscriber<T>`, `TopicSubscriptionRegistry`, `TopicPublisher`, `PublisherFactory`
- Pub-sub code generation in slice-processor — subscription metadata in manifest, `stop()` resource cleanup, envelope v2
- RFC-0010 Unified Invocation Observability (supersedes RFC-0009)
- Envelope format versioning for slice JARs — `ENVELOPE_FORMAT_VERSION` in ManifestGenerator, runtime compatibility check in SliceManifest
- Properties manifest (`META-INF/slice/*.manifest`) now included in per-slice JARs for full metadata at runtime
- JaCoCo coverage infrastructure across 6 aether modules (427 tests)
- Cluster event aggregator — `/api/events` REST endpoint (with `since` filter), `/ws/events` WebSocket feed (delta broadcasting), CLI `events` command. 11 event types collected into ring buffer (1000 events)

### Fixed
- ProvisioningContext sliceId propagation — resource lifecycle tracking now works correctly for consumer reference counting
- UNLOADING stuck state — CDM `reconcile()` now calls `cleanupOrphanedSliceEntries()`, NDM `handleUnloadFailure()` properly chains Promise
- Rolling update UNLOADING stuck state and missing SliceTargetKey creation
- Monotonic sequencing on `QuorumStateNotification` to prevent race condition during leader failover
- Slice JAR manifest repackaging for rolling update version mismatch
- JBCT compliance fixes for HttpClient JSON API
- Fast-path route eviction on node departure
- 20K/50K/100K rate buttons on Forge dashboard

### Enabled
- 5 previously disabled E2E tests: partition healing, quorum transitions, artifact failover survival, rolling update completion, rolling update rollback

### Changed
- **BREAKING:** Removed individual slice `POST /api/deploy` and `POST /api/undeploy` endpoints — use blueprint commands instead
- **BREAKING:** Removed `deploy` and `undeploy` CLI commands — use `blueprint apply` and `blueprint delete`

### Removed
- Individual slice deploy/undeploy from REST API, CLI, and Forge proxy
- `handleSliceTargetRemoval` from ClusterDeploymentManager (unreachable after deploy/undeploy removal)

## [0.16.0] - 2026-02-18

### Added
- `aether/resource/` module group consolidating all infrastructure resources
- `MethodInterceptor` interface in slice-api for per-method concerns (retry, circuit breaker, rate limit, logging, metrics)
- `ProvisioningContext` in slice-api for passing type tokens and key extractors to resource factories
- 5 interceptor `ResourceFactory` implementations in `resource-interceptors` module
- `integrations/statemachine` module (relocated from infra-statemachine)

### Fixed

### Changed
- **BREAKING:** Renamed packages `org.pragmatica.aether.infra.*` → `org.pragmatica.aether.resource.*`
- **BREAKING:** Resources no longer implement `Slice` interface — `DatabaseConnector`, `HttpClient`, `ConfigService` etc. are pure resource types
- Consolidated 10 infra-slices + infra-api + infra-services into 8 resource modules (api, db-jdbc, db-r2dbc, db-jooq, db-jooq-r2dbc, http, interceptors, services)
- Flattened db-connector hierarchy: `infra-db-connector/{api,jdbc,r2dbc,...}` → `resource/{api,db-jdbc,db-r2dbc,...}`

### Removed
- `aether/infra-api/` — merged into `resource/api`
- `aether/infra-slices/` — 10 modules dropped or relocated:
  - `infra-aspect` (unused JDK proxy factories; config types preserved in resource/api)
  - `infra-database` (toy in-memory SQL, superseded by db-connector)
  - `infra-scheduler` (thin JDK wrapper)
  - `infra-ratelimit` (duplicated core/RateLimiter)
  - `infra-lock` (in-memory only, no distributed backend)
  - `infra-pubsub` (in-memory only, no distributed backend)
- `aether/infra-services/` — merged into `resource/services`

## [0.15.1] - 2026-02-12

### Added
- ClusterEventAggregator for structured event collection (topology, leader, quorum, deployment events)
- MetricsCollector invocation metrics in cluster-wide gossip
- MetricsCollector topology change handlers for departed node cleanup

### Fixed
- SliceStore.unloadSlice() stuck in UNLOADING state when slice loading had previously failed
- Shared dependency loading fails for runtime-provided libraries (e.g. `core` embedded in shaded JAR)
- Orphaned SliceNodeKey entries not cleaned up after undeploy during leader change
- E2E multi-instance deployment test used hardcoded instance count instead of cluster size
- E2E BeforeEach cleanup now retries undeploy to handle leader changes during teardown
- Pre-populate DHT ring with known peers and harden distributed operations
- Distributed DHT wiring — DistributedDHTClient replaces LocalDHTClient for cross-node artifact resolution

### Changed
- Disabled TTM E2E tests (trivial checks not worth 90-minute 5-node cluster overhead)

## [0.15.0] - 2026-02-02

### Added
- Monorepo consolidation of three projects:
  - pragmatica-lite (v0.11.3) - Core functional library
  - jbct-cli (v0.6.1) - CLI and Maven plugin for JBCT formatting/linting
  - aetherx (v0.8.2) - Distributed runtime
- Unified version management across all modules
- Consolidated documentation structure
- Moved `cluster` module from aether to integrations (generic distributed networking)
- AppHttpServer immediate retry on node departure (no more 5-second timeout wait)
- Production tinylog configuration for aether/node
- Tinylog format now includes thread name: `[{thread}]`
- Request ID logging for critical log statements in AppHttpServer and SliceInvoker
- Blueprint CLI commands: `list`, `get`, `delete`, `status`, `validate` (also in REPL)
- Blueprint REST API endpoints: GET/DELETE `/api/blueprint/{id}`, GET `/api/blueprints`,
  GET `/api/blueprint/{id}/status`, POST `/api/blueprint/validate`
- Consolidated startup banner showing node configuration (ID, ports, peers, TTM, TLS)

### Changed
- All modules now use version 0.15.0
- Root POM provides dependency management for entire ecosystem
- Unified CI workflows at monorepo root
- E2E and Forge tests moved to `-Pwith-e2e` profile (require examples to be installed first)
- Standardized tinylog configurations across all modules (24 files)
- Added Fury and Netty logging suppression to all test configs
- Comprehensive logging level overhaul in aether module:
  - Hot paths (SliceInvoker, InvocationHandler, AppHttpServer) moved to DEBUG/TRACE
  - Routine operations (deployment, slice lifecycle) moved to DEBUG
  - Important events (leader/quorum changes, rolling updates) kept at INFO
  - Production logs are now scannable and concise

### Technical Notes
- Group IDs preserved for Maven Central compatibility:
  - `org.pragmatica-lite` for core, integrations, jbct modules
  - `org.pragmatica-lite.aether` for aether modules
- Build: `mvn install -DskipTests` (bootstraps jbct-maven-plugin automatically)
- E2E tests: `mvn verify -Pwith-e2e -pl aether/e2e-tests,aether/forge/forge-tests`

---

## Pre-Monorepo History

Historical changelogs for individual projects:

- [Pragmatica Core CHANGELOG](core/CHANGELOG.md)
- [JBCT CHANGELOG](jbct/CHANGELOG.md)
