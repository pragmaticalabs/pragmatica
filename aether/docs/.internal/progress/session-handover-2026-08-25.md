# Session handover — 2026-08-25: 02w closes fully green (run7), then queue items 1–3 — the tri-floor surface, the silence-killers, and two rulings

> **Stream: `aether-main` (release / integration / cloud stream). Written for the aether-main agent.**
>
> Two streams write handovers here on the shared branch — check the banner before reading one as
> your own state. This stream keeps the UNSUFFIXED name.

**Branch:** `release-1.0.0-rc3` · pushed through `1d095b1ba`. Candidate tag at `1d095b1ba`.
Uncommitted residue (deliberate, NOT ours to touch): `core/.../utils/Causes.java` + untracked
`core/docs/typed-error-construction.md` + `jbct/docs/typed-error-lint-spec.md` (the OWNER's
in-progress typed-error arc — exclude from every commit), and untracked
`aether/tests/integration/failure-logs/` (run5/6/7 evidence, on-disk only).

**The owner-approved GOAL (2026-08-24, verbatim ruling "all and in exactly that order"):**
1. ✅ #634-3+4 tri-floor operator surface — DONE
2. ✅ #634 structural follow-ups batch — DONE
3. ✅ Owner rulings S3 + #634-5 — OBTAINED, recorded on #634
4. **NEXT: #634-7 remainder (WAL fsync-failure injection + crash-mid-compaction tests), #598, #628,
   #596 read half (`BOUNDED_STALE` on a non-owner returns EMPTY, reads as ABSENT)**

---

## §1 02w is FULLY GREEN — run7, first completely clean run in the suite's history

The hosting-set fix (`f42bd1530`: per-node `EntityKeyspaceRegistrationKey(keyspace, node)` records,
retract-on-unload + prune-on-restart, `EntityOwnershipReconciler` as the EXCLUSIVE `entity:*`
ownership writer — review caught the stream driver as a competing writer, filtered via
`withoutEntityArcs`; keyspace `/` refused at bind) took every suppressed number to its ceiling:
**14/14 assertions, 40/40 pre-kill acked (was 22/40), 40 acked DURING the kill (was 3), 80/80
survived SIGKILL exact-valued, convergence 31s (was FAILED at 989s), failover 2s.** Evidence:
`failure-logs/02w-run7-green/`. The checkpoint driver reported alive on exactly the 3 hosting
nodes — the fix's shape visible in operations. PR #632 (jbct shape-census/RET-05/REC-01) merged
`e4a6beec4`; post-merge gate green. REC-01 scope question (try/catch invisible to it — only
`.recover(...)` is inspected) routed to the owner, unanswered.

## §2 Queue item 1 — #634-3+4 tri-floor surface (`542f1589e` + `06e4fa48f`)

Items 3+4 were one piece of work per the ticket ruling. Landed: `[storage.streams] wal_path`
first-class key (absent = byte-identical derivation, explicit paths get the mandatory `/<nodeId>`
suffix — pinned); `PartitionWal.stats()` with per-GROUP-COMMIT fsync timing;
`StreamPartitionManager.walSnapshot()` (hydrationSnapshot pattern; ringTail `-1` for an EMPTY
materialized ring — review catch, the restarted-empty blind spot); `GET /api/storage/retention` +
`aether storage retention` — the tri-floor join with `coveredFrom` (min-of-starts, the NECESSARY
half of reachability, documented as such) and violated =
`checkpoint >= 0 && (coveredFrom < 0 || coveredFrom > checkpoint+1)`; `RetentionInvariantWatch`
(5 min, TWO-consecutive-tick debounce — the join is a non-atomic cut — raising `retention-invariant`
severity `CRITICAL`, a review catch: the lowercase literal was rejected by the case-sensitive
validator, the whole periodic half INERT); `GET /api/entity/keyspaces` hosting view (pure projection
over `EntityOwnershipReconciler.scanRegistrations` — single merge authority); WAL bytes in the
storage capacity view local + cluster (`StorageStatusValue.walBytes`, both codec halves pinned —
the binary codec had ZERO coverage; the TOML path is ephemeral-excluded, tests drive the
package-visible arms per the `activation` precedent, and value-codec tests MUST layer
`FrameworkCodecs` as parent or every list-bearing value fails). Docs with recovery actions;
dormant-slots recorded on #494. Review: 4 MAJOR / 9 MINOR / 3 NIT, all fixed, 16/16 verified.

## §3 Queue item 2 — structural follow-ups (`6c5ed495e` + `1d095b1ba`)

The five silences around the #492 class, closed:
1. **Boot guard** `AetherNode.verifyRoutedTypesEncodable` — every ROUTED `Message.Wired` type needs
   a codec or boot refuses naming ALL missing + the aggregation hint; `Message.Local` structurally
   exempt (sealed discriminator). Guard runs at the assembly TAIL because `aetherEntries`
   ACCUMULATES (an earlier guard would pass against a partial set); a failed guard runs
   `cancelArmedWork` (13 periodic tasks + both samplers) so Forge/Ember hosts get no #499 zombies.
   Main routes assembly failures through `abortBoot` FATAL. Residual recorded: supertype-fallback
   acceptance; sent-but-never-routed types covered only by the loud-encode net.
2. **`WriteOutcome.EncodeFailed`** + ERROR at both transport encode sites (there was NO catch
   anywhere — sync sends died unresolved, broadcasts silently cancelled their periodic task) + the
   wired router's `dispatchLoudly` (the override bypassed `dispatchOne`'s try/catch). Consumers:
   `DistributedDHTClient` + `EntityForwardService` fail fast; fire-and-forget is log-only BY DESIGN;
   worker `sendOutcome` default reports `Sent` unconditionally (pre-existing blindness, recorded at
   the default).
3. **Entity-forward wire budget**: `remainingMillis` on the three request records; owner refuses
   arrived-expired with typed `ForwardBudgetExhausted` before touching the entity.
4. **Invoke-layer caps**: caller-side `[verified]` — BUT the first cut was measured INERT
   (60,015ms under a 300ms budget): `Deadline.current()` at `SliceInvoker`'s arm sites reads an
   UNBOUND ScopedValue after the encode continuation's thread hop. Fix = capture-at-entry, threaded
   (param chain + `FailoverContext.deadline` component). Receiver-side (`InvocationHandler`) is
   honestly `[design intent — unverified]`: its only caller is inbound network dispatch and
   `InvokeRequest` carries no budget yet — mechanism pinned, engages when the wire step lands.
5. **1:4254 is NOT an anomaly** — the generation counter is a leadership-tenure tick (1/pingInterval
   while leader); 4254 ≈ 71 min tenure at term 1 = a STABLE cluster. Documented at the increment
   site and the generation surface; deliberately no per-bump log.

Review: 2 MAJOR / 4 MINOR / 1 NIT — all fixed/verified 7/7 (the M2 relocation premise was REBUTTED
with evidence and the underlying hazard fixed instead). Gate: 3,933 tests / 0 failures across ten
modules; 16+4 new pins.

## §4 Queue item 3 — rulings (recorded on #634, comment 2026-08-25)

- **S3 idempotency: DEFERRED, gated on I5/I6.** No spec counter (no consumer), no API token yet
  (design it against workflow/saga). Standing contract: S2 per-operation recovery.
- **#634-5 DurableLog move: BOTH GATES STAND.** Nothing until #349 DD-8-1; the `PartitionWal`
  BSL→Apache question returns to the owner when the technical gate clears.

## §5 Traps / calibration from this session

- **A cap/alert/surface can compile, lint, review clean and be INERT** — three instances in ONE
  session: lowercase alert severity vs case-sensitive validator; `Deadline.current()` after a thread
  hop; the receiver-side cap with no budget on the wire. The killers were ARMED tests (lower time
  bounds, reject-the-lowercase counterparts) and per-claim evidence tags. Tag discipline caught the
  third: a `[verified]` claim over a mechanism pin is FALSE for the live path.
- **Value-codec tests must layer `FrameworkCodecs` as the parent registry** (production:
  `NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs())`) — a bare `KvstoreCodecsSlice.CODECS`
  registry fails every list-bearing value with "No codec registered for ImmutableCollections$...".
  Documented in `StorageStatusValueCodecTest` with the measurement.
- **`storage-status` is EPHEMERAL** (`EphemeralKeys.EPHEMERAL_SECTIONS`) — toToml round-trips can
  never exercise it; drive the package-visible arms directly (the `activation` precedent).
- **Machine sleep kills foreground agent work** — two gate runs died overnight/mid-run. The fix that
  held: agents run builds detached (`nohup … & disown`, parent PID 1) and poll their own logs.
- **The formatter emits `- 1L` (unary-minus spacing)** — recorded in the formatter-bugs memory; do
  not hand-edit, it re-breaks on the next pass.
- jbct REC-01 inspects only `.recover(...)` — try/catch absorption is invisible to it; scope
  question with the owner.

## §6 Next (the standing queue, item 4)

1. **#634-7 remainder**: WAL fsync-failure injection + crash-mid-compaction (temp+rename window)
   tests — the two crash cases still owed from the ticket's gap list.
2. **#598** and **#628** — read the tickets first (not yet scoped this session).
3. **#596 read half** — `BOUNDED_STALE` on a non-owner returns EMPTY, indistinguishable from
   ABSENT; no client-side re-resolution.
4. Standing: cloud 02w stays the final gate for anything touching the entity/stream path; the
   remote host was healthy last run (`$TARGET_HOST` reachable, torn down clean).
