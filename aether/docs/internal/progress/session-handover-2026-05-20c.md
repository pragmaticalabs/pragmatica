# Session handover — 2026-05-20c (Wave 7: four fixes for 8/15 failing suites)

Branch: `release-1.0.0-rc1`
HEAD: `7dc8935a0` (unchanged — **no commits this session yet**)
Working tree: dirty (4 fixes implemented, awaiting final consolidation + commit)

## Topline

Picked up from 2026-05-20b's open issues: Cluster B 0/5 + Cluster A residuals (04-streaming, 09-artifacts, 15-delegation). Investigated all four in parallel, implemented all four fixes. Three are test-infra / minor; the fourth (artifact 1MB push 500) led into a deeper Promise/Result composition bug that pre-existed and was only exposed by adding retry coverage.

**Status:** all four fixes coded; artifact-repo module's 25 tests green (including 3 new `ChunkRetryTests`). Integration suite has NOT been re-run end-to-end. Nothing committed — user is having another agent add a `Result.firstFailureOrAll`-style helper to `Result` core (since this is recurring functionality), after which the local helper in `ArtifactStore.java` should be replaced and the four fixes committed together.

## Fixes implemented

### Fix 1 — Cluster B entry-point rotation

**Problem:** When 02-chaos kills Cluster B's entry-point node, `MGMT_ENTRY_POINT` still points to the dead host:port. Every subsequent suite's `wait_for_cluster_ready` then waits the full 60s timeout before fast-failing.

**Fix:** Added `_refresh_mgmt_entry_point` in `lib/common.sh` that mutates the env vars in place (NOT a subshell `$()` return — the previous shape lost the export). `wait_for_cluster_ready`'s fast-fail probe in `lib/cluster.sh` (line ~445) now calls this helper before giving up.

Also: cleaned stale `aether-{a,b}-mgmt-gateway` sidecar comments from both compose files and `run-tests.sh` — gateway was removed but comments still referenced it. (See open question below: should it come back?)

### Fix 2 — stream_list multi-word CLI

**Problem:** 04-streaming's "Stream visible in list" test called `stream_list()` in `lib/cluster.sh`, which executed `aether_json streams`. Picocli rejects bare `streams` — needs `streams list`.

**Fix:** `aether_json streams` → `aether_json "streams list"` in `lib/cluster.sh:1971`. Single-line change. Same class of bug as Wave 6's `1d29a4949` (multi-word commands).

### Fix 3 — 15-delegation start_node removal

**Problem:** `test-02-reassignment.sh::test_node_failure_reassignment` killed a node, then called `start_node "$scaling_node"` to revive it, then `wait_for_cluster_ready 240`. With the single-writer rule + CTM auto-heal, the killed NodeId is DECOMMISSIONED and a replacement is provisioned at a fresh port (5156+/5166+). Reviving the original container produces a stale-id zombie that prevents convergence.

**Fix:** Removed `start_node` call. Test now just calls `wait_for_cluster_ready 240` and lets CTM auto-heal provision the replacement. Same semantic correctness as the rest of the chaos suite.

`lib/cluster.sh::start_node` deprecation notice (lines ~1453-1463) still references this as a "lone caller" — should be updated to mention the cleanup, but harmless if left.

### Fix 4 — Artifact 1MB push (the deep one)

**Problem (reported):** 09-artifacts/test "1MB artifact push returned HTTP 500".

**Layer 1 — missing chunk retry.** Metadata writes had `dhtPutWithRetry`; chunk writes called `storage.put` raw. With N=16 chunks for 1MB and a single replica's transient `BackpressureRefused` surfacing as `DHTError.PeerUnreachable`, any one chunk's backpressure failed the whole deploy. Added `storagePutWithRetry`/`handleStoragePutFailure` mirroring the existing DHT retry pattern, used in deploy's chunk fan-out.

**Layer 2 — `Result::unwrap` antipattern in production code.** Line 220 of `ArtifactStoreImpl.deploy`:

```java
.map(results -> results.stream().map(Result::unwrap).toList())
```

When any chunk-put returns `Failure`, `Result::unwrap` throws `IllegalStateException` from inside the `Promise.map` lambda. That throw either leaves the Promise stuck (caller observes outer `.timeout(30s)`) or surfaces synchronously through `.await()`. This path was **latent** in practice: the `StorageInstance.claimBlock` dedup short-circuit meant a failing chunk's retry would never re-hit the failing tier, so chunk-Failures rarely reached this site. The new `FlakyStorage` test fixture exposed it.

Same antipattern at line 283 in `resolveChunksFromStorage`. Both replaced with a local `firstFailureOrAll` helper that surfaces the first chunk's cause directly.

**Layer 3 — `Result.allOf` returns composite.** First attempted fix was `Result.allOf(results).async()`. That correctly propagates failures through the Promise chain but wraps them in a `compositeCause`, hiding the chunk-level type (`DHTError.PeerUnreachable`, `NonTransientCause`) behind a wrapper. Bad for operator-visible diagnostics and breaks `instanceof` dispatch downstream. Replaced with `firstFailureOrAll`:

```java
private static <T> Promise<List<T>> firstFailureOrAll(List<Result<T>> results) {
    var values = new ArrayList<T>(results.size());
    for (var result : results) {
        switch (result) {
            case Result.Success<T> success -> values.add(success.value());
            case Result.Failure<T> failure -> { return failure.cause().promise(); }
        }
    }
    return Promise.success(values);
}
```

**Pending consolidation:** User noted this functionality belongs in `Result` core (not duplicated per-call-site). Another agent is adding `Result.firstFailureOrAll` (or similar). After that lands, the local helper in `ArtifactStore.java` should be removed and both call sites switched to the core API. Then commit all four fixes together.

**StorageInstance dedup-vs-retry — known latent bug.** Discovered but NOT fixed in this session: `DefaultStorageInstance.handlePut → metadataStore.claimBlock` succeeds on first attempt; on retry after a tier-write failure, the second attempt sees `claimBlock` returns false (already claimed) and short-circuits to `deduplicateBlock` → success. The block was never actually written. Symptom: a transient tier failure silently corrupts the artifact (metadata says block exists; resolve later fails with "block not found"). Out of scope for RC1 fix-this-week, but worth a GitHub issue for RC2. The test had to bypass this by mocking `StorageInstance` directly (`FlakyStorage`) rather than injecting a `FlakyTier`.

## Files touched (uncommitted)

### Production code

- `aether/resource/services/artifact-repo/src/main/java/org/pragmatica/aether/resource/artifact/ArtifactStore.java`
  - Added `storagePutWithRetry(byte[])` + `storagePutWithRetry(byte[], int)` + `handleStoragePutFailure(...)` mirroring `dhtPutWithRetry`.
  - Updated `MAX_DHT_PUT_ATTEMPTS` docstring to reflect chunk-retry coverage.
  - Deploy's chunk fan-out (line ~209): `chunks.stream().map(this::storagePutWithRetry)`.
  - Deploy's aggregation (line ~219) + `resolveChunksFromStorage` (line ~283): `.map(results -> results.stream().map(Result::unwrap).toList())` → `.flatMap(ArtifactStoreImpl::firstFailureOrAll)`.
  - Added private static helper `firstFailureOrAll` (pending replacement with core `Result.firstFailureOrAll`).

### Test infra

- `aether/tests/integration/lib/common.sh` — `_refresh_mgmt_entry_point` helper added.
- `aether/tests/integration/lib/cluster.sh` — `wait_for_cluster_ready` fast-fail probe calls `_refresh_mgmt_entry_point`; `stream_list()` uses `aether_json "streams list"`.
- `aether/tests/integration/docker-compose-a.yml`, `docker-compose-b.yml` — stale gateway sidecar comments removed.
- `aether/tests/integration/run-tests.sh` — gateway-related comments updated.
- `aether/tests/integration/suites/15-delegation/test-02-reassignment.sh` — `start_node "$scaling_node"` removed; comment explains CTM auto-heal path.

### Tests

- `aether/resource/services/artifact-repo/src/test/java/org/pragmatica/aether/resource/artifact/ArtifactStoreTest.java`
  - New `@Nested class ChunkRetryTests` with 3 tests:
    - `deploy_transientChunkFailure_retriesAndSucceeds`
    - `deploy_exhaustedRetries_failsWithLastCause`
    - `deploy_nonTransientFailure_failsWithoutRetry`
  - New `FlakyStorage implements StorageInstance` fixture (bypasses `DefaultStorageInstance` dedup machinery so retry behavior is observable).
  - New `NonTransientCause` record (for the non-transient test).

## Verification status

- ✅ `mvn -pl aether/resource/services/artifact-repo test -am` — 25/25 pass.
- ❌ Integration suite — NOT re-run end-to-end. Fixes 1-3 are isolated to specific suites; Fix 4 is module-level only.
- ❌ Wider build (`./build.sh`) — NOT run (HCLOUD_TOKEN is set in env; would risk a paid Hetzner server creation via failsafe).

## Open items for next session (in order)

1. **Wait for `Result.firstFailureOrAll` (or equivalent) in core**, then replace `ArtifactStoreImpl.firstFailureOrAll` with the core API.
2. **Run integration suite end-to-end** to validate the four fixes work together (Cluster B should be unstuck, 04/09/15 should pass).
3. **Commit** the four fixes. Suggest a single commit per fix for git hygiene:
   - `fix(test-infra): refresh entry-point on failure to unstick post-chaos suites`
   - `fix(test-infra): stream_list uses 'streams list' multi-word CLI`
   - `fix(test-infra): 15-delegation lets CTM auto-heal replace killed node`
   - `fix(artifact-repo): retry chunk puts; propagate first chunk failure through Promise chain (Result::unwrap antipattern fix)`
4. **File RC2 issues:**
   - `StorageInstance` dedup-vs-retry: a failed tier write leaves the block "claimed" but unwritten; subsequent retry short-circuits via `deduplicateBlock` and silently corrupts. Likely fix: reset the claim on tier-write failure, or move the claim to be conditional on tier-write success.
   - `resolveChunksFromStorage` failure-path is uncovered by tests. The `firstFailureOrAll` fix is symmetric and obviously correct by inspection, but a single test (one chunk read fails → resolve surfaces that specific cause, not a composite) would lock in the contract.
   - `Result.unwrap()` deprecation warnings in `ArtifactStoreTest` (lines 258, 274, 288, 331) — `Artifact.artifact(...).unwrap()` test-setup pattern; migrate to non-deprecated alternative.

## Side discussion — passive management gateway

User proposed (and I agreed): reuse existing passive-node mode + strip `AppHttpServer`, package like Forge as an ops/test utility. Zero new state machine — passive node already watches KV-Store, knows topology, is excluded from consensus.

**One open question** to confirm before filing: does passive mode currently expose `ManagementServer` (with proxy-to-leader for write routes), or only the app endpoint? If the former, this is a packaging task. If the latter, we add management route forwarding to passive — still small. **Action:** grep `PassiveNode` / `ManageableNode` for ManagementServer wiring next session, then file the issue.

## Key learnings worth retaining

1. **The `Result::unwrap` antipattern (JBCT-RET-02 — see user memory `feedback_promise_result_antipattern.md`).** `.map(results -> results.stream().map(Result::unwrap).toList())` looks innocent but throws `IllegalStateException` from inside a Promise lambda when any element is a Failure, with destination-dependent behavior (Promise sticks, or `.await()` throws). The correct shape is a `Result`-aware aggregator that surfaces the first Failure as a Promise failure.

2. **`Result.allOf` is the wrong shape for fan-out where you want operator-visible causes.** It composes failures into a Composite, which (a) hides the chunk-level type from `instanceof` dispatch, and (b) gives operators "Composite: PeerUnreachable, PeerUnreachable" instead of "PeerUnreachable: node-X unreachable". For fan-out, surface the first failure's cause directly.

3. **`StorageInstance` dedup short-circuits retry semantics.** A `FlakyTier` plugged into `DefaultStorageInstance` cannot exercise tier-retry because `claimBlock` (called once) makes the second attempt skip the tier entirely via `deduplicateBlock`. Tests of retry behavior on storage need to mock the `StorageInstance` interface directly. This is also a real latent product bug (failed write leaves a phantom claim).

4. **`Result::stream` is the wrong shape for fan-out with hard failures.** It silently discards Failures (Optional-style coercion: Success → element, Failure → empty). If 2 of 3 chunks fail, you get a 1-element list and an artifact is "successfully deployed" with one block, silently corrupted. Use only when partial-extraction is genuinely the contract you want.

5. **Subshell `$()` lose exports.** `_refresh_mgmt_entry_point` was originally written to return the new endpoint via `echo $endpoint` from a `$()`-call. Callers' `export MGMT_ENTRY_POINT="$(_refresh)"` works, but in-place mutation reads cleaner and removes a class of "did I capture the new value?" bugs. The helper now mutates the env in its own shell context.

## Session metadata

- Date: 2026-05-20 (third session of the day — Wave 7)
- Commits this session: **0** (all changes uncommitted, pending Result core helper)
- Net code added: ~80 lines production (ArtifactStore), ~120 lines test fixture, ~10 lines shell
- Module test runs: 4 rounds of `mvn -pl aether/resource/services/artifact-repo test -am` (final = green)
- Integration suite runs: 0 (deferred until after Result core helper lands and commits are made)
- Outstanding external dependency: another agent adding `Result.firstFailureOrAll` (or equivalent) to core

## Suggested next-session opener

If the Result core helper has landed by then:
1. Replace `ArtifactStoreImpl.firstFailureOrAll` with the core API call.
2. Run `mvn -pl aether/resource/services/artifact-repo test -am` to confirm.
3. Run integration suite end-to-end on remote Docker.
4. If green: commit four fixes in order (suggested messages above).
5. File the three RC2 issues from the "open items" section.

If the Result core helper has not landed: send the agent a status check, hold consolidation until it's available. In parallel, file the RC2 issues so they're not lost.
