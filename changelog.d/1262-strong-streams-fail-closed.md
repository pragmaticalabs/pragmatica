### Fixed (2026-09-19 — #1262: STRONG-consistency streams were not refused)
- **No write path can honour a STRONG stream** (`ConsensusPublishPath` has no production caller), yet
  nothing refused one consistently. A blueprint's STRONG reaches the runtime only through the key the
  provisioning binder reads, `consistency_mode`. The deploy-time validator read `consistency` instead,
  which nothing binds, and `BlueprintService` swallowed validation failures into empty bindings. A stream
  that was STRONG got three behaviours: the slice `StreamPublisher` refused a single publish but
  acknowledged a STRONG `publishBatch` as success with nothing written, while `StreamAccess.publish` and the
  management publish wrote it as EVENTUAL.
- **Deploy.** A `[streams.X]` declaring `consistency_mode = "strong"` (or `consistency = "strong"`) now
  fails the deploy on both publish paths (artifact and body) before any command is applied, with
  `inert-stream-config-key` naming #1262 (`StreamResourceValidator.ensureHonourableConsistency`). Every
  other stream-validation failure keeps its existing non-gating behaviour.
  [mechanism: unit-level only, pinned by `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/BlueprintPublishOwnershipTest.java` and `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/validation/StreamResourceValidatorTest.java` — no multi-node run]
- **Writes.** A stream that is STRONG anyway (created by another route, or adopted into a committed config)
  is refused on every write with `CONSENSUS_PATH_UNAVAILABLE`, before routing and with the ring untouched.
  That covers the slice publisher (single and batch, including a publisher built EVENTUAL over a stream
  committed STRONG), `StreamAccess`, the management publish, and the owner-side forwarded publish on both
  its first attempt and its post-materialize retry.
  [mechanism: unit-level only, pinned by `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StrongConsistencyFailClosedTest.java` — no multi-node run]
- The same guard fails closed on an `UNKNOWN` consistency mode (#964), which a node running a newer
  `ConsistencyMode` may have written as STRONG. Before, only the slice publisher refused it; now every entry
  point refuses it with the shared `UNREADABLE_CONSISTENCY_MODE` cause.
- Operator action: a blueprint that declares STRONG must drop the key or set it to `eventual` before it
  deploys. Feature catalog row #192 now reads Partial with the gap stated, not Complete.
- [unverified: a failed STRONG deploy is shown at the Management API as a failed publish; the operator-facing rendering was not exercised on a running node]
