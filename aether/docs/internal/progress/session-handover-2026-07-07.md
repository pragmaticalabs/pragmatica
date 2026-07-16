# Session Handover — 2026-07-07

**Branch:** `release-1.0.0-rc2` · **Committed HEAD:** `1e3c383e0` + build.sh fix commit · **⚠ Working tree:** may hold UNCOMMITTED fix-batch edits (see "In flight") — check `git status` FIRST.

## TL;DR

rc2 build work was complete (see 2026-07-06 handover); this session ran the **Phase-1 cloud gate**. **Run 1** aborted at 00-smoke (stale fixture jars — trap fixed, `build.sh` step 5 now cleans). **Run 2: 10 of 15 suites GREEN**; every red root-caused to exactly **three issues**: two integration-debt fixes (in flight) and **one real product bug** (#415 — the gate's marquee catch). **Run 3 is the verdict run** once the fixes land. Then: release cut.

## Cloud gate run 2 scoreboard (HEAD `1e3c383e0`, remote `$TARGET_HOST`)

PASS: 00-smoke, 03-scaling (the #265 machinery under scale), 05-security, 07-cluster-mgmt, 09-artifacts, 10-database, 11-observability, 12-network, 14-storage, 15-delegation.
FAIL: 04-streaming 2p/2f, 06-deployment 2p/3f, 08-resources 3p/2f, 13-edge-cases 3p/1f, 02-chaos 5p/2f.
**Zero failures implicate the new rc2 substance** (fence/lifecycle/linearizable/observability). Owner-kill → membership removal PASSED.

## The three root causes

1. **Mgmt stream publish/read is node-local** → 500 `"Stream partition is not owned by this node"` on non-owners under #265 placement gating → 100% publish errors (04/08/13 + one 02 cascade). My inc-2 "out of scope" call, falsified by the gate. **Fix: owner-route the mgmt endpoints (in the running fix batch).**
2. **rc1-built example artifacts** (`org.pragmatica.aether.example:url-shortener:1.0.0/:1.0.1`, analytics) in `.m2` reference the deleted `Aspect` class → raw `ClassNotFoundException` at slice load → 06-deployment dead. Two sub-fixes in the batch: examples bumped/rebuilt to rc2 (+ jar verification), and **CNFE during factory resolution mapped to the named rebuild-together cause** (honesty gap: CNFE fires before `verifyParameters` can speak).
3. **#415 (NEW, product, rc2): QuorumLossDetector cold-boot suppression drops the drain intent with no re-arm.** A survivor killed-into inside the A6 ~75s window becomes a **permanent zombie** (ran 140+s past window-close in silence; co-survivor with closed window drained correctly — natural control). Root: `QuorumLossDetector.emitIntent():400-409` bare-return + one-shot edge-triggered producers (`:331`, `:314`); the Javadoc contract has no implementing code. Full fix plan on the issue: reschedule-on-suppression + the `phaseIsColdBoot`-pinning safety check + audit the symmetric SWIM FAULTY suppression. Production shape: an auto-heal replacement joining shortly before quorum loss zombifies.

## In flight at handover

- **Fix-batch coder** (background): owner-routing (scope-first: reuse the app publish path), CNFE→named cause, examples→rc2+verify. **Leaves edits UNCOMMITTED.** Next session: read its report (task output), review, commit. If it died: `git status`/`git diff` to assess, re-brief from the three root causes above.
- **#415 fix: NOT started** (one-code-track rule). Next after the batch lands. Fix plan verbatim on the issue.
- Gate evidence in THIS session's scratchpad (`cloud-gate-run.log`, `cloud-gate-run2.log`, `s19-survivor.log`) — session-scoped; the durable diagnosis lives in #415 + this handover. JSON report: `aether/tests/integration/test-results.json`. The wedged survivor container may still be running on the remote — reap cluster-B leftovers before run 3.

## ⭐ Next session — exact sequence

1. `git status` — handle the fix-batch tree (review + commit if report is green; the batch's jar-verification outputs are the acceptance).
2. **#415 fix** (jbct-coder, the issue's 3-point plan; in-JVM unit test with flipping cold-boot supplier).
3. Full local test pass on touched modules + push.
4. **Gate run 3**: pre-flight orphan checks → reap remote leftovers → `cd aether/tests/integration && env -u HCLOUD_TOKEN ./run-tests.sh --env remote` (fixtures now clean-built by build.sh; run build.sh first if any codegen-adjacent change landed). Watchlist: 04/06/08/13 should flip green; 02-chaos S19 + Kill_node_during_active_load; then the **manual 1f fence probe** (`aether cluster ownership stream-partition` during an owner-kill: deposed node `fenced=true`, steady state `highWater==epoch`).
5. Gate green → `/pre-release-check` → `/release` (closes #403). Suggested checklist add: ticketing-posterchild 23-slice blueprint vs the built archive.

## Standing context

Gap-drain loop GO (MAILBOX transport — **re-arm the mtime Monitor**; memory `project_gap_drain_loop_mailbox`); Editor feed still quiet. All multi-agent hand-offs drained (#356/#391/#410/#359 resolved; #359 landed via authorship-preserving cherry-pick). rc2 open: #345 (cloud-gate item only), #403 (release cut), #415 (new). Envelope 1007, no-bump ruling stands. Fixture-staleness trap + verification recipe: memory `project_fixture_staleness_trap`.
