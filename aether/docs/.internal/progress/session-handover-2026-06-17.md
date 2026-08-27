# Session Handover — 2026-06-17

## ⚡ TL;DR

Two threads: (1) shipped + cloud-validated a **stream forward-read fix** and a **cloud zone-resilience feature**; (2) ran a **tracker-vs-branch reconciliation** that collapsed the apparent backlog — **rc2: 27 → 4 open**, all closures evidence-backed.

- Branch `release-1.0.0-rc2`, HEAD **`bdb642d1e`**, everything pushed. Tree clean (only the untracked session-handover files remain).
- **rc2 = 4 genuinely open:** #333 (our backfill bug), #210 (cloud), #260/#261 (fold into #333). Everything else was already fixed in code and just never closed.
- Two issues filed this session: **#333** (backfill gap-repair, rc2), **#334** (auto-heal zone-rotation, rc3).
- Candidate image/tag still at **`3bf2578fb`** (the forward-read fix). The two later commits (zone-fallback, docs) don't change the node image.

---

## 1. Commits this session (release-1.0.0-rc2)

| Commit | What |
|---|---|
| `cfc4dd274` | fix(stream): system-stream replication factor = all cores (`systemReplicationFactor → clusterSize`) |
| `3bf2578fb` | fix(stream): forward replica reads to HRW owner; converge `PartitionedStreamAccess` + `StreamReadRouter` into one `ForwardingReadRouter` |
| `6dcbe2083` | feat(cloud): zone-fallback in bootstrap provisioning — retry next zone on capacity exhaustion |
| `bdb642d1e` | docs: add Aether Knowledge Bundle spec + projector/lint brief (#314) |

(Earlier in the same continued session: the Tier-A management APIs + cloud-harness fixes + the stream cold-start / slice-override fixes `e32f0fcfc`…`13e9eb9f6` — see session-handover-2026-06-16.md.)

---

## 2. Stream forward-read fix (the headline product work)

**Design (user-ratified):**
- System streams replicate to **all cores** (RF = clusterSize), not `max(3, N-2)`.
- Replica reads **forward to the deterministic HRW owner** (every node computes it locally; the owner self-promotes first).
- Durability model: **quorum-CAUGHT_UP** is the hard floor, **all-cores** the eventual target, **forward-read** bridges the lag (eventual consistency). Per-write stays `minSyncReplicas=1`.

**Root cause fixed:** peer CAUGHT_UP state is **never propagated cross-node** — the production `ReplicaRegistry` uses `WatermarkStore.NOOP` and `rebuildFromWatermarks` has no prod caller. So a non-owner's `caughtUp` set is always empty → it fell through to reading its own empty local partition. The forward-capable consumer (`SystemStreamFactories.systemStreamConsumer`) also had **no owner resolver wired** (`Option.none()`). Fix wires `ReplicaSetController.ownerFor` and routes the fallback to the owner. The two duplicated routers were converged into one generic `ForwardingReadRouter<E>`.

**Cloud-PROVEN 4/5:** per-node `/api/events` distribution went `3 0 0 0 3` → **`1 16 16 16 16`** (was 2 nodes serving, now 4). `aether-stream` 464/0 incl. 10 new owner-forward tests.

**The residual = #333 (SEPARATE bug).** Node 0 (boot-hiccuped: "stream config consensus commit failed" + governor failover at offset 1) got stuck at local offset 2: its backfill picks a **blind source** (no cross-node CAUGHT_UP visibility), **false-promotes CAUGHT_UP at offset 0** with `applied 0 events`, then `ReplicationReceiveHandler.handleGapAhead` rejects every live batch (offset 4+ vs expected 2) forever. The false CAUGHT_UP then defeats the read fix via `selfCoversPartition`. **Fix direction:** extend the HRW-owner principle to `PartitionBackfill` source selection + never promote CAUGHT_UP while a gap exists; gate with a **deterministic gap-repair unit test**, not a flaky cloud boot-race. #260/#261 fold into #333.

---

## 3. Cloud zone-resilience (`6dcbe2083`)

Hetzner `nbg1-dc3` couldn't place 5× cx33 (412 `resource_unavailable`). Rather than re-pin the zone, made provisioning zone-resilient:
- `SourceProfile` takes an ordered `zones = ["fsn1","nbg1","hel1"]` list + `effectiveZones()` (back-compat: single `zone` → `[zone]`).
- `BootstrapPhaseProvision.provisionCloudRoleGroup` rotates to the next zone on `EnvironmentError.CapacityUnavailable` (mapped from Hetzner `resource_unavailable` in `HetznerComputeProvider.toProvisionError`), per-role-group cursor, fail-fast on non-capacity errors.
- All 4 cloud TOMLs updated to the zones list. Tests: SourceProfile 3, Hetzner 34, ZoneRotation 6 — all green.
- **#334** (rc3) tracks the deferred auto-heal `provisionReplacement` zone-rotation.
- **Before the next cloud run: rebuild the local `aether` CLI** (`mvn -pl aether/cli install -am`) — zone-fallback is CLI-side; the node image is unaffected.

---

## 4. The tracker reconciliation (the big cleanup)

**Discovery:** a parallel "Wave 1" of 6 worktree lanes turned out fully redundant — the lanes worktreed off **`main`** (Agent `isolation:worktree` defaults to the default branch), but `rc2` is **60 commits ahead**, and **all 8 Wave-1 issues were already fixed in rc2** and just never closed. Discarded all 6 worktrees.

**Pivot → verify-and-close audit.** Audited all 27 rc2 issues against the branch (commit + current-code evidence). **23 closed as already-fixed**, each with its rc2 commit cited:
- Wave-1 set: #251, #256, #266, #293, #295, #301, #302, #308
- Reconciler/consensus/CTM/pubsub: #331, #329, #325, #258, #274, #236, #148, #259, #166
- Security: #282, #290, #289, #299, #287, #209
- (Plus #267, #262 closed earlier in the session.)

**rc2 now = 4 open:** #333, #210, #260, #261.

**Security default FLIPPED to ON** (#290, `cd6a82dba`): `ConfigLoader` defaults `securityMode` to `API_KEY` when unset (explicit `none` still opts out). This **inverts** the earlier-session README "OFF by default" correction and ticket **#316**'s premise — flagged on #316; docs need reconciling to secure-by-default. (Safe direction: docs under-claim security.)

---

## 5. Documentation specs committed (`bdb642d1e`)

Two design specs under `aether/docs/specs/`, referenced in **#314** (the doc-rework epic):
- **`aether-knowledge-bundle-spec.md`** — target doc architecture: the AKB, a strict lintable OKF profile (two-tier IA, closed `type`/`consumption_mode` vocab, typed relation DAG, authored-vs-`projected` provenance, in-code knowledge store projecting code→docs so the volatile tier can't drift). §10 migration + §11 maintenance playbooks.
- **`akb-projector-lint-brief.md`** — projector (`generate`/`verify` from the jbct-parser CST) + conformance lint (L1–L10). Mapped onto the existing Phase 1–4 tickets: the AKB conformance lint *is* #324's doc-lint guard; #323's mkdocs IA should target AKB's two-tier structure.

---

## 6. Backlog reconciliation (rc3 + unassigned)

| Bucket | Before | After |
|---|---|---|
| rc2 | 27 | **4** |
| rc3 | 93 | **102** (real forward backlog; +9 pre-GA moved in) |
| unmilestoned | 28 | **19** |
| total open | 148 | **125** |

- **rc3 is genuine forward work** (~0% stale by commit cross-ref): docs (14), dashboard (10), cloud providers (8), interceptor/resource SPI (12), storage wiring (8), membership/streaming (10), features+tech-debt (~20).
- **9 pre-GA unassigned → rc3:** #135/#136/#137 (stream read routing + read-your-writes), #215 (stream metadata via consensus), #214 (CTM cleanup), #216 (cloud test-infra), #217 (SchemaRoutes safety guard), #219 (AlertsResponse JSON), #223 (Netty NodeInfo parity).
- **19 deferred (unmilestoned)** — core-lib polish (#2–#9, #227), big features (#76 Forge DSL, #82 IDE plugins, #119 Vault, #123 DO/Vultr, #125 2PC, …), #205 (stream RBAC), #231 (φ-accrual spike). **Post-GA bucket name still undecided** (v1.1.0 / backlog / rc4).

---

## 7. Open items / next steps

1. **#333** — backfill gap-repair (the only real rc2 product fix). HRW-owner backfill source + never-CAUGHT_UP-on-gap + deterministic repro test. Closes #260/#261 as superseded.
2. **#210** — cloud NODE_LEFT/FAILED not detected (cloud-only; needs a Hetzner run).
3. **Post-GA bucket naming** for the 19 deferred unassigned.
4. **Security-default doc reconciliation** — README + #316 say "NONE default"; code is now API_KEY (secure-by-default).
5. **JVM cloud runtime** never validated; **#59** (B4 abort-path runtime proof).
6. Candidate tag/image: move to HEAD only if a cloud run needs zone-fallback/docs in the image (they don't change it); rebuild local CLI for zone-fallback.

---

## 8. Gotchas / lessons

- **Agent `isolation:worktree` bases on `main`, not the session branch.** That made Wave 1 redundant. Future lanes MUST base on rc2 (set `worktree.baseRef=head` or pre-create worktrees), and **always verify rc2 doesn't already fix an issue before launching a lane** — the tracker was ~85% stale on rc2.
- **rc2 tracker ≠ branch:** open ≠ unfixed. The burn-down was verify-and-close, not re-code.
- **Agent infra was flaky today** — several audit sub-agents stalled (600s watchdog); fell back to inline work. A per-issue rc3 deep audit is cheap to re-run when infra is healthy.
- **`--runtime container`** (not docker); `nbg1` capacity is unstable → zone-fallback now handles it; gh `issue list` defaults to `--limit 30` (pass `--limit` for counts).
- **Verify subagent claims:** all closures were gated on the cited commit actually existing on rc2 + spot-checking the surprising ones (e.g. #290's default flip read directly in `ConfigLoader.java:294`).
