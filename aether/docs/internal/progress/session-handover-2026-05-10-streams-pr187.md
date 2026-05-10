# Session Handover — 2026-05-10 (PR #187 streams thread)

**Companion to** [`session-handover-2026-05-10.md`](session-handover-2026-05-10.md) (main agent — D2 structural fix). This document covers the parallel session thread focused on PR #187 final completion, the architectural assessment of membership/topology that triggered D2, and the implications for the open PR.

---

## ⚡ TL;DR for next session

- **PR #187 (event-stream-namespaces impl) is feature-complete** on `feature/stream-namespaces-impl` head `a1308696e`, but built on **pre-D2** rc1 base (`82ef6cea1`).
- Main agent landed D2 structural fix + 2 postgres-async PRs (#199, #200) + spec PR #186 between my last rebase and session end. Current rc1 is `1faf27573` (tagged `v1.0.0-rc1-candidate`).
- **PR #187 needs another major rebase onto current rc1.** Expect substantial conflict resolution because Wave 5B-ii's `ClusterEventAggregator` rewrite (which consumed the now-deleted `TopologyChangeNotification`) is superseded by main agent's typed-streams version.
- Last CI on `a130869` is RED on the SAME stale tests main agent rewrote in `fbccf3b7a`. **Rebase will clear those failures.**
- 3 follow-up issues filed: **#205** (cross-ns RBAC, on-demand), **#207** (postgres-async CI flake), **#212** (SSE/WS tail, rc2).

---

## 1 · Branch state at session end

| Branch | Head | Status |
|---|---|---|
| `release-1.0.0-rc1` | `1faf27573` | tagged `v1.0.0-rc1-candidate` |
| `feature/stream-namespaces-impl` | `a1308696e` | PR #187 OPEN; built on pre-D2 base |
| `feature/event-stream-namespaces-spec` | `47e27561e` | PR #186 **MERGED** (now on rc1 as `5506af952`) |

Working tree clean. CI on impl branch RED on stale tests (will clear post-rebase).

---

## 2 · This session's #187 commits

```
a1308696e feat(cli): aether stream tail polling loop against /events endpoint
a647fd5d1 feat(streams): paginated /events read endpoint for stream tail polling
b5efe93a9 test(streams): add coverage gaps + minor cleanups (stale flat key, leading-hyphen check, fetch caching)
254e2950f refactor(streams): JBCT compliance polish — narrow suppressions, remove dead code, simplify chains
460f5faa6 fix(streams): KvBackedStreamRegistry refcount mutations confirm consensus before returning success
055ad9c18 feat(cluster-events): replace RingBuffer with system:cluster-events:1.0.0 stream subscription
22d600f62 feat(streams): sealed FrameworkStreamConsumer SPI for system stream reads
bdf90c2df feat(streams): KV-backed StreamRegistry with consensus-mediated refcount tied to slice ACTIVE state
dfdf2cb32 feat(cli): aether stream command group (list/show/tail/delete/group create/group delete)
e3ba0b000 test(streams): coverage for slice manifest role-hint flow + BlueprintArtifactParser
f7cdbb250 feat(streams): wire slice manifest role hints through to deploy-time validator
8329cc043 feat(streams): full HTTP route surface for stream namespaces (Wave 4A)
6ab98178b feat(slice-processor): infer stream role from StreamPublisher/StreamAccess parameter type
e8416fea3 feat(streams): blueprint-level StreamResourceValidator with Result.allOf() failure aggregation
7af5be211 refactor(cluster-events): sealed ClusterEvent interface + ExtendedEvent hatch + STREAM_REGISTERED/DELETED variants
291ad98ae feat(streams): sealed FrameworkStreamPublisher SPI for system stream writes
84f1d3ca5 refactor(streams): remove StreamNamespacesConfig feature flag (RC1-mandatory per spec)
0e5cfbdd4 feat(streams): TOML parser shortcut defaults for version field
42a121cb9 fix(streams): namespace charset lowercase-only + system.* prefix reserved
```

(Plus the original 14 PR #187 foundation commits beneath.)

Wave breakdown:
- **Wave 1** (3): charset/prefix tightening, parser shortcut, flag removal.
- **Wave 2** (2): sealed `FrameworkStreamPublisher` SPI; sealed `ClusterEvent` interface + `ExtendedEvent` hatch + `STREAM_REGISTERED`/`STREAM_DELETED` variants + `EventId`/`EventIdAllocator`.
- **Wave 3** (2): `StreamResourceValidator` (`Result.allOf` aggregation, structured failure shape, 422 status); slice-processor role inference.
- **Wave 4A** (1): full HTTP route surface (publish/groups/delete + 405 system + path-segment routing).
- **Wave 4B** (3): manifest pipe-through wiring (deploy-time `BlueprintStreamBindingsKey/Value`); bonus tests; `aether stream` CLI group.
- **Wave 5A** (1): KV-backed `StreamRegistry` + consensus refcount tied to slice ACTIVE state via `NodeDeploymentState.Active.handleActivating()`.
- **Wave 5B-i** (1): sealed `FrameworkStreamConsumer` SPI (mirror of publisher).
- **Wave 5B-ii** (1): `ClusterEventAggregator` refactor — RingBuffer replaced with `system:cluster-events:1.0.0` stream subscription via deferred-binding suppliers.
- **Wave 6A** (3): TOCTOU fix in registry (signature changed `Result` → `Promise` for acquire/release); JBCT compliance polish (narrowed suppressions, removed dead code, simplified chains); test additions for reviewer-flagged coverage gaps.
- **Wave 6B** (2): polling tail server endpoint `/api/streams/{ns}/{stream}/{version}/events`; CLI polling loop with `--interval`/`--from-offset`/`--max-events`/`--follow`.
- **Rebase** onto rc1 `82ef6cea1` with manual conflict resolution at 3 commits (Wave 2 F, Wave 4B CLI, Wave 5B-ii aggregator, Wave 6A polish).

---

## 3 · Spec edits committed (now in rc1 via PR #186 merge)

5 spec commits applied during this session, all in PR #186 (now merged):

```
47e27561e docs(spec): post-impl-review edits — §7.4 KV collapse note, §13.3-§13.4 system filter scope, §16 polling tail, #212 forward ref
b02683a7f docs(spec): correct §11.1.2 role inference — uses StreamAccess parameter, not StreamSubscriber/@OnEvent
fe079c3e5 docs(spec): correct closed-variant count from 26 to 27 (existing EventType has 25, not 24)
+ 16-item RC1 design walkthrough commit (earlier session)
```

The spec walkthrough resolved 16 design items in conversation with the user, covering: shortcut-form defaults for `[streams.X]`, cross-namespace RBAC scope (deferred to #205), sealed-SPI for system writes, principal-threading deferral, frozen-at-connect tail semantics, slice-instance refcount via consensus, lifecycle-event ordering rule (replacing the prior filter approach), reserved namespace patterns, namespace charset cap, KV-Store key shape (later collapsed per §7.4 implementation note), `aether stream` CLI surface, full HTTP route table, validation aggregation pattern, `ClusterEvent` envelope schema with `ExtendedEvent` extension hatch.

---

## 4 · CRITICAL: PR #187 rebase requirements

`feature/stream-namespaces-impl` is built on pre-D2 rc1 base. Current rc1 has:
- `TopologyChangeNotification` **deleted entirely** (`f04ef03c8`)
- 15 subscribers migrated to typed `MembershipDecision` / `TransportObservation`
- New types in `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/`

**Wave 5B-ii's `ClusterEventAggregator` rewrite is now stale.** It was based on the deleted `TopologyChangeNotification.*` variants. The rebase will need to:

1. **Re-resolve `ClusterEventAggregator.java` heavily.** Main agent already migrated this file to consume `MembershipDecision` (per their handover §3 — listed in the 11-subscriber consumer list). Wave 5B-ii's stream-publisher-based version needs to merge with the main agent's typed-stream consumer version. Likely the cleanest path: take main agent's structural refactor, then re-apply Wave 5B-ii's stream-publisher emit pattern on top.
2. **Verify SWIM observation handling.** Wave 2 F's `onSwimObservation` method (added during rebase to integrate with rc1's SWIM-source emission) — check whether main agent's typed streams now route SWIM observations through `TransportObservation` instead.
3. **Other waves should rebase relatively cleanly** — they're in `slice-api`/`slice`/`aether-deployment`/`cli` modules, mostly orthogonal to consensus topology layer changes.

### Suggested rebase approach

- Foreground rebase, dedicated jbct-coder agent.
- Pre-brief with main agent's handover §3 (typed-streams type definitions + producer/consumer mapping) as required reading.
- Expect significant effort on `ClusterEventAggregator` specifically; everything else should be javadoc-style or zero-conflict.
- Build verification via build-runner after rebase + before push.

---

## 5 · Investigate possible interaction with main agent's regression

Main agent's handover §5.B flags a regression: **"MembershipDecision.NodeJoined likely not reaching ClusterEventAggregator's NODE_JOINED emitter for CTM-provisioned replacements."** Symptom: `12-network/test-quic-connectivity` fails with "No NODE_JOINED event for replacement of node-2 within 90s".

This is in the file Wave 5B-ii heavily touched. The rebase will produce SOME version of `ClusterEventAggregator` that consumes `MembershipDecision`. Worth:

1. Reading main agent's migrated `ClusterEventAggregator` BEFORE rebasing — understand the current state and what regression is occurring.
2. Determining whether Wave 5B-ii's deferred-binding pattern (suppliers for publisher/consumer) interacts with the regression. The supplier-binding window during AetherNode bootstrap could plausibly miss early `MembershipDecision.NodeJoined` events.
3. Considering whether the rebase should explicitly add an `onNodeJoined` handler that emits `NODE_JOINED` cluster event, addressing main agent's regression as part of the merge.

The cluster-events flow is structurally affected by both Wave 5B-ii (stream-based publisher) AND main agent's typed migration. The rebase is the natural place to coordinate.

---

## 6 · Architectural assessment summary (for future reference)

aether-investigator agent + 8-point user walkthrough produced this picture of the membership/topology/leader-election area on `82ef6cea1` (pre-D2):

| Item | Status (after walkthrough) |
|---|---|
| **Spec R1** (Rabia Paused state) | **Rejected** — operational evidence shows reliable Rabia recovery through chaos. Spec text needs amendment to remove "chronic" framing. |
| **Audit Step 7** (cross-node observation quorum) | **Post-RC1** — SWIM indirect-ping already encodes K+1 reachability checks; aggregator's `quorumThreshold(onDutyCount) = 1` is acceptable RC1 gap. Comment cleanup at `ObservationAggregator.java:114-124`. |
| **Self-leader-eviction escape hatch** (`HealthReconcilerImpl:252-263`) | **Acceptable + needed** — fills inherent gap (leader can't write own decommission). Spec §4.3 reframe as explicit exception clause. Doc paragraph required. |
| **Three cold-boot suppression layers** (SWIM + HealthReconciler + CTM) | **Acceptable + composing at different abstraction levels** — signal/state-machine/orchestration. Subtle smell: all query same phase signal, so BOOTING→NORMAL latch is single point of failure. |
| **D2** (dual `NodeRemoved` emitters) | **Verified unresolved**, then **structurally fixed** by main agent (typed observation/decision streams) per user's "one-shot" call. |
| **SWIM-FAULTY-on-leader bridge** (`c84bc0607`) | **Acceptable + necessary** — fires synchronously in SWIM listener BEFORE `handleAggregatedEdge`. Asymmetric value: redundant for non-stalled case, load-bearing for consensus-stalled case. Phase-gated to NORMAL. |
| **2 failing tests** (cold-boot suppression) | **Stale, not regressions** — fixed by main agent in `fbccf3b7a`. |

User explicitly chose one-shot RC1 implementation of D2 over my conservative "RC1 docs + RC2 migration" recommendation. Rationale: budget available, no time pressure, quality-first mandate, "many other things to do — this part shouldn't keep coming back." Main agent then executed the full migration in 7 commits.

---

## 7 · Issues filed this session

- **#205** Cross-namespace stream RBAC: fine-grained access control for stream reads and system namespace (`on-demand`) — slice-level RBAC deferred from RC1; current model is open access.
- **#207** CI flake: postgres-async integration tests intermittently timeout at 10-minute limit (`bug`, `tech-debt`) — affects every branch including rc1; suspected testcontainers cold-start.
- **#212** SSE + WebSocket tail subscription for `/api/streams/{ns}/{stream}/{version}/tail` (`enhancement`, `rc2`) — RC1 ships polling tail; streaming protocols deferred until route framework gains chunked-encoding/long-lived-connection primitives.

---

## 8 · Process learnings

- **`jbct-reviewer` agent toolset gap:** crashed without Bash access. Workaround: pre-stage diff via `git diff release-1.0.0-rc1...HEAD > /tmp/diff.diff` and reference file path in brief. Worked second time.
- **Agent role-confusion pattern:** multiple jbct-coder agents on this branch initially refused tasks citing the `user_role` memory. Mitigation: include explicit scope clause + "Wave N completed equivalent work" reference upfront in briefs.
- **Stream-idle timeouts on long agent runs:** Wave 5/6 agents hit ~30 min wall time and terminated mid-task. Mitigation: split waves (e.g., 5B-i + 5B-ii rather than combined 5B).
- **Process gap:** rc1 shipped with `mvn test` red (cold-boot stale tests) for ~5 days. Either `./build.sh`/release-check doesn't catch this, OR pattern of "merge on partial validation, defer full to next session" let the gap recur. Worth surfacing in a postmortem.

---

## 9 · Pre-merge checklist for #187

1. ✅ All 6 implementation waves landed.
2. ✅ Spec PR #186 merged into rc1.
3. ✅ JBCT code review done (Wave 6A addressed all critical findings).
4. ✅ §16 acceptance checklist verified.
5. ⏳ **Rebase onto current rc1** (`1faf27573`) — major work, see §4 above.
6. ⏳ **Verify CI clears** post-rebase (stale tests now fixed on rc1, should clear).
7. ⏳ **Investigate ClusterEventAggregator regression** main agent flagged in §5.B of their handover.
8. ⏳ **E2E manual verification** (deferred — needs running cluster, separate session).

---

## 10 · Recall pointers

- This handover: `aether/docs/internal/progress/session-handover-2026-05-10-streams-pr187.md` (committed on `feature/stream-namespaces-impl`; to be cherry-picked to rc1 by user if desired).
- Main agent companion: `aether/docs/internal/progress/session-handover-2026-05-10.md` on rc1 head `1faf27573`.
- Spec (now in rc1): `aether/docs/specs/event-stream-namespaces-spec.md`.
- Membership v2 spec (D2 structural fix): `aether/docs/specs/membership-architecture-spec.md` (per main agent §3).
- Memory pointer: `~/.claude/projects/-Users-sergiyyevtushenko-IdeaProjects-pragmatica-store/memory/project_handover_2026_05_10_streams.md`.
