# Session Handover — 2026-06-28 (A6 full-cluster crash-durability CLOSED; NEXT: durable-entity primitive implementation)

**Branch `release-1.0.0-rc2` · HEAD `669cd1c15` · LOCAL commits NOT pushed (see §Divergence).**

## ⚡ TL;DR
The A6 gate (`StreamCrashDurabilityTest`, full-cluster-restart WAL crash-durability) is **CLOSED and verified** (10+ consecutive in-JVM green, ~68s each). The prior handover's "read-routing only" diagnosis was wrong on the root cause. Fixed, committed (7 commits this session), and PR #364 merged. Next session's assigned task: **implement the durable-entity primitive per the now-committed v0.2 spec.**

## What closed A6 (corrected root cause)
On a simultaneous full-cluster cold restart the cluster reaches quorum (3/5) and flips `COLD_BOOT→NORMAL` ~14s in, but SWIM's first probe-acks lag the QUIC attach — so the **`QuorumLossDetector`'s SWIM-alive count momentarily decays below threshold and HEALTHY nodes self-drain** before convergence. Terminal-removal makes them unrecoverable → cluster wedges at 3/5 → with RF=1 the data-holding HRW owner is stranded/reassigned to an empty-WAL survivor → reads return 0. (The prior "owner replays WAL @49" evidence was the PRE-restart promotion, misread.) Ownership is HRW-from-membership, NOT KV — no KV persistence needed; the fix is to stop the premature self-fence.

**4-part fix, one shared bounded cold-boot window (`COLD_BOOT_CONVERGENCE_WINDOW_MS` = 75s, covering the transport 60s force-dial):**
1. **`QuorumLossDetector` cold-boot gate (root)** — `emitIntent` suppressed while `swimIsBootingSupplier` active; genuine minority still self-fences after the window. (`0d9419f63`)
2. **SWIM cold-boot window** — `AetherNode.swimIsBootingSupplier` stays true 75s past boot (reuses the tested COLD_BOOT FAULTY-suppression branch). (`0d9419f63`)
3. **`NEAREST` reads made LOCAL-FIRST** (`ForwardingReadRouter`) — read local; forward to the HRW owner only on a local MISS. Closes the original `GOVERNOR`-never-forwards gap AND fixes the `StreamFanoutConsumerTest` regression that the first NEAREST attempt introduced (replicated-stream reads must not forward away from a node that holds the data). (`7f80bc795`)
4. **Test full-membership gate** — `StreamCrashDurabilityTest` waits for leader `/api/health` `nodeCount`=N before publish/read. (`816974aa5`)

Regression net (all green): aether-stream 528, aether-deployment 744 (+`QuorumLossDetectorTest.ColdBootSuppression`), integrations/swim 170, `AetherNodeColdBootWindowTest`, forge `StreamCrashDurabilityTest` (10+) + `StreamFanoutConsumerTest`. Reconciled: plan §A6, feature-catalog #15, CHANGELOG rc2 (`3a67a8287`).

## This session's commits (local, on `release-1.0.0-rc2`)
- `669cd1c15` docs: durable-entity primitive spec v0.2 (#345/#349)
- `32ede5250` fix: suppress intentional JBCT-RET-07 discard in PerKeySerialExecutor.chainOnto
- `b743ef09c` chore: apply jbct formatter canonical normalization (~38 files tree-wide — see §Format)
- `3a67a8287` docs: reconcile A6 closure
- `816974aa5` test: re-enable StreamCrashDurabilityTest (A6) + full-membership gate
- `7f80bc795` fix: NEAREST stream reads local-first
- `0d9419f63` fix: gate cold-boot self-fence on a bounded post-boot window
- PR **#364** (slice-processor codegen fully-qualify) squash-merged to `release-1.0.0-rc2` (remote).

## ⭐ NEXT TASK — durable-entity primitive implementation
The v0.2 spec is now committed: [`aether/docs/specs/durable-entity-primitive-spec.md`](../../specs/durable-entity-primitive-spec.md) (`669cd1c15`). It pins the author-facing API and resolves the open design questions; **§14 still needs Sergiy sign-off** before/while implementing.

**Current prod state (the gap to close):**
- `DurableEntityFactory.java:31` returns `InMemoryDurableEntity` — in-process state only, HA-oriented, **NOT restart-durable**.
- The `FencedDurableEntity` and `PartitionFencedDurableEntity` variants exist and are tested but are **unwired** in the bootstrap path.
- The KV *correctness* fence already landed in the Rabia applier (`staleEpochWrite`, #345): a stale-owner-epoch write is rejected by every replica. Per-key serial execution is `PerKeySerialExecutor` (lock-free tail-swap).
- Catalog: feature-catalog #217 (`Durable entity — Partial`).

**Scope to implement (per spec):** §5 `@DurableEntity` API + provisioning (custom qualifier annotation, config section, manifest, injection) + error types; §6 `PersistentWorkflow` facade + the `OrderProcess` FSM worked example; §7 Saga / run-once journaled step. Wire the fenced/persistent variant into `DurableEntityFactory` (the restart-durable path), gated on the #345 ownership-fence substrate (1d-iii → 1f). Cross-refs: #345 (ownership fence), #349 (durable-entity persistence substrate).

## ⚠️ Divergence — local commits not pushed
My 7 commits (`0d9419f63`→`669cd1c15`) are **local only**. The remote `release-1.0.0-rc2` advanced via the PR #364 squash-merge, so the branches diverged. To push: `git pull --rebase` onto the PR #364 merge (collision-free — #364 is slice-processor, disjoint from these files), then push. Not done this session (no push was requested).

## §Format — tree-wide jbct normalization committed
`b743ef09c` applied the jbct formatter's canonical output across ~38 files (verified idempotent fixed point). Reason: build.sh's `process` (format+lint) had been **aborting at the `PerKeySerialExecutor` JBCT-RET-07 lint error**, so only modules before that point were ever formatted; the rest had accumulated format drift. With the lint fixed (`32ede5250`) + this normalization, `./build.sh` should pass its format/lint gate cleanly.
- **Known formatter bug:** `Dialects.java` — the formatter RELOCATES two inline `// CREATE INDEX...` regex comments onto the `").*"` line (comment-mangle). It is a STABLE fixed point (idempotent), committed as canonical, but the comments now misdescribe their line. Real fix belongs in the jbct formatter (comment-trivia placement), tracked separately — see [[project_jbct_formatter_bugs]].

## ⚠️ Shared working tree / multi-agent
A concurrent **design** session is actively editing `durable-entity-primitive-spec.md` (committed here at the user's request; if they continue, re-commit the delta). No concurrent *code* edits were in the tree — the earlier "20 concurrent code files" alarm was build.sh format noise, now resolved. The prior `session-handover-2026-06-27b.md` is committed alongside this one.

## Verify / build
- `env -u HCLOUD_TOKEN ./build.sh` (HCLOUD_TOKEN must be stripped — `mvn install`/`verify` fire `HetznerCloudIT`). Forge A6: `env -u HCLOUD_TOKEN mvn -Pwith-e2e -pl aether/forge/forge-tests integration-test -Dit.test=StreamCrashDurabilityTest -Dfailsafe.failIfNoSpecifiedTests=false` (verdict from the failsafe "Tests run: 1, Failures: 0" line, NOT BUILD SUCCESS).
