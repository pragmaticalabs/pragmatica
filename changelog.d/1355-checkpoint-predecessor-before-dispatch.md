### Fixed (2026-09-20 — #1355: the detach flush could overlap a periodic commit that was still being issued)
- **`issueCheckpoint` assigned the slot a detach flush chains behind only after the store call had been
  made.** `observedCommit` invokes `store.commit(...)` inline on the issuing thread (`lifted` does not
  hop threads: `Result.async()` is `Promise.resolved(this)` and `fold` on a resolved promise runs in
  place), registers the `TrackedCommit`, attaches three handlers and arms the 5s timeout — and only then
  did `ConsumerState.periodicCommit(...)` receive the promise. A detach flush arriving in that window
  read the previous, settled commit and was issued at once, beside the in-flight one: the overlap #1239's
  chaining exists to prevent. Outcomes were not misattributed (#1239's per-commit outcomes); the cost was
  the overlap itself. Seen as `StreamConsumerRuntimeClusterCursorTest.detachFlush_waitsForTheInFlightPeriodicCommit_…`
  reporting `expected: 1, but was: 2` once in a full `aether/node` run under host contention.
- `issueCheckpoint` now hands the slot a fresh promise FIRST — before its cancellation check and before
  the store call — and resolves it inline from the commit chain (`withResult`), ahead of the asynchronous
  `afterCheckpoint` event. On the bail path (closed or cancelled) nothing is issued and the slot is
  settled at once; only its settlement is ever read (`predecessor.fold(_ -> …)`). [mechanism:
  `ConsumerRuntimeState.issueCheckpoint`; pinned by
  `ConsumerRuntimeCheckpointPredecessorRaceTest.detachFlush_arrivingWhileThePeriodicCommitIsBeingIssued_waitsForThatCommit`]
- Assigning before the cancellation check also closes the narrower sibling interleaving
  check(not cancelled) → `cancel()` → flush reads the settled slot → the checkpoint is issued anyway
  (CTO ruling 2026-09-20: in scope). [mechanism: slot assigned before `closed`/`isCancelled()` are read;
  pinned by `ConsumerRuntimeCheckpointPredecessorRaceTest.detachFlush_arrivingBeforeTheCancellationCheck_isIssuedAsSoonAsTheCheckpointBails`]
- `close()` orders detach the other way round — flush first (`awaitFinalCursorCommits`), cancel after — and the
  same ordering covers it: its flush reads the pending slot and waits, and `close()` returns once both commits
  settle. [verified: `ConsumerRuntimeCheckpointPredecessorRaceTest.closeFlush_arrivingWhileThePeriodicCommitIsBeingIssued_waitsForThatCommit`,
  red under the original ordering]
- `ConsumerRuntimeState` gains a package-private test seam, `checkpointIssueProbe`, run at two named
  points of `issueCheckpoint` (`SLOT_ASSIGNED`, `BEFORE_STORE_CALL`); production never sets it. The tests
  park the issuing thread there and detach the consumer from the test thread, so each interleaving is
  entered by construction — the same shape as #1253's `readWindowProbe`. [verified:
  aether/aether-stream/src/test/java/org/pragmatica/aether/stream/ConsumerRuntimeCheckpointPredecessorRaceTest.java
  — red on the unmodified base with the defect's own signature (`expected: 1 but was: 2`), and red again
  under each of seven mutations: slot after the store call, slot after the check, bail path leaving the
  slot unsettled, flush ignoring the slot, slot never resolved, either probe point unwired]
- [unverified — recorded, not fixed (rev1370 NIT 1): between `state.periodicCommit(periodic)` and
  `.withResult(periodic::resolve)` there is no exceptional-exit settlement of the slot. Nothing in that span can
  throw today (`Result.lift` catches `Throwable`; the timeout scheduler is a JVM-lifetime daemon pool, never
  shut down; the probe is a no-op in production), so it is unreachable. If throwing code is ever put there, a
  never-settled slot would leave an interactive `unsubscribe` flush unissued — `periodic.fail(...)` in a catch is
  the cure.]
- [unverified: the `withResult` (inline) vs `onResult` (asynchronous event) choice for resolving the
  slot is not pinned by a test — both orderings pass the suite; inline was chosen so the slot never lags
  the commit it stands for.]
