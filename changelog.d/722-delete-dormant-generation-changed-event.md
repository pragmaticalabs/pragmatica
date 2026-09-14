### Removed (2026-09-14 — #722: `GENERATION_CHANGED` was documented and consumer-wired but had no producer)
- **Decision: delete, not wire.** `OperationalEvent.GenerationChanged.generationChanged(...)` had zero call sites,
  `GenerationChangedSink` had no implementation and no user beyond its own file, and the emission the v1
  spec described belonged to a leader-resident reconciler that was never built. Wiring it "at the real
  epoch-advance sites" would not give an operator what the doc promised: the epoch the cluster actually keeps
  is `(leaderTerm, tenure-tick)` and `AetherNode.bumpGenerationIfLeader` advances the tick once per ping
  interval of leadership — deliberately unlogged, "elapsed leadership time in ping intervals, never an event
  count" — so an event per advance is a 1 Hz stream saying "still the leader", and the only other advance,
  a term bump, already surfaces as `LEADER_ELECTED`/`LEADER_LOST`. The dashboard/CLI quad rule therefore
  cuts the other way: nothing to ship on four layers. Deleted: `OperationalEvent.GenerationChanged`,
  `ClusterEvent.GenerationChanged`, `ClusterEventAggregator.onGenerationChanged` and its `AetherNode` router
  entry, `GenerationChangedSink`, `GenerationChangedNotice`, `GenerationReason`. Wire tags 271
  (`ClusterEvent.GenerationChanged`) and 839 (`GenerationReason`) are RETIRED in `SystemTags` — the pins stay
  with a retirement note, never reused, per the `DHTNotification` precedent (#571).
  [mechanism: enforced by the compiler; `git grep 'GenerationChanged|GenerationReason|GENERATION_CHANGED'`
  over `*.java` returns only the two retired pins; `WireAssignmentTripwireTest` re-recorded — the baseline
  diff is exactly the `TAG … GenerationChanged 271`, `TAG … GenerationReason 839` and the `ENUM …
  GenerationReason` lines, and its enum-count floor moves 26 → 25 with the count re-derived from the
  generated codecs (25 `*Codec.java` carrying the enum sentinel under every module's generated-sources)]
- Docs corrected: `management-api.md` no longer lists `GENERATION_CHANGED` and says where the epoch is read
  (`GET /api/v1/cluster/generation`) and which events a leader change does raise;
  `cluster-generation-spec.md` §14.4 annotated as superseded.
- [unverified: no cluster run — the deleted route was never fired, so no runtime behaviour changes; inferred
  from the zero call sites, not observed. Root build evidence is `mvn clean install -Dmaven.test.skip=true`
  145/145 because the rc4 tip's `aether-deployment` test sources do not compile independently of this change
  (`SchemaOrchestratorLockClaimRaceTest` vs `ArtifactStore.metadata`, #1132 × #1138 — reported to the CTO);
  `aether/node`, the touched test module, ran its suite.]
