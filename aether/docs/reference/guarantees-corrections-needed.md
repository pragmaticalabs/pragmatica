# Guarantees — Corrections Needed (worklist)

> Overclaims and inaccuracies found while building [`guarantees.md`](./guarantees.md).
> Each row: location · quoted claim · why it's wrong · honest rewrite.
> Grounded at `release-1.0.0-rc2` @ `e320881f0`, 2026-06-29.
>
> **Status 2026-08-10 — mostly APPLIED.** This file opened with "Nothing here has been applied"
> for six weeks after that stopped being true; a worklist about claim accuracy was itself
> inaccurate. Of the seven issues filed from it (#378–#384), **six are CLOSED**: #378 (sync-replication
> off-by-one), #379 (KV `Remove` unfenced), #380 (DHTConfig strong-consistency docstring), #382
> (DurableEntity "Linearizable get" javadoc), #383 (in-memory/snapshot-only persistence — documented,
> build deferred to epic #349), #384 (DHT guarantee downgrade documented). **#381 remains open**
> (`ConfigNotificationManager.notifyChange` has no caller — runtime config-change push is dead code),
> milestone `v1.0.0-rc4`, and is a member of the dead-surface class tracked under #519.
>
> Re-grounding this file against current `HEAD` is part of **#496** (GA claims-vs-reality audit).
> Until that runs, treat the rows below as historically accurate at rc2, not as current findings.
>
> **#496 progress, 2026-08-28 (consensus/cluster-core surface, scoped pass) —** re-checked against
> current `HEAD`. D2/D3 (DHT durability disclosure) were already applied in the 2026-07-17 docs wave.
> **Applied this pass:** D1 (KV-Store row — added the write/read consistency split), D4 (quorum-loss
> "graceful degradation" euphemism — replaced with the pause/self-fence mechanism), D14
> (`operators/monitoring.md` SPOF claim — rescoped to the write path, the boilerplate rewrite in this
> worklist didn't fit that section's actual context), D15 (`rolling-upgrade.md` "zero downtime" — added
> the core-node quorum-margin caveat, which the guide never stated at all). Also fixed, not from this
> worklist: an unearned "Strong (all nodes agree)" leader-election claim in both `01-consensus.md` and
> `contributors/consensus.md` — same-order ≠ same-instant, now stated precisely.
> **Deferred, not audited this pass** (out of the scoped surfaces — logged so the next pass doesn't
> rediscover): D6/D7/D18 (pub-sub — not a named in-scope surface), D8–D12/D16 (streams — explicitly
> named as deferred "stream/data-plane" territory), D13 (durable-entity — #345/#352/#596 read as
> active cluster-core work, ownership ambiguous, held rather than guessed), D17/D19 (archive docs —
> low priority, untouched). D5 ("Battle-tested") intentionally left alone: the term is already defined
> precisely at the top of `feature-catalog.md`'s own legend, unlike the unqualified phrases above.



Two kinds of item:
- **DOC** — wording overclaim; fix the prose.
- **CODE** — the claim reflects a real behaviour gap/defect; needs an issue + code fix (or an honest downgrade of the claim). These are also listed in `guarantees.md` §7.

---

## Existing-ticket mapping (GitHub search, open + closed, 2026-06-29)

| Item | Filed issue | Epic | Note |
|------|-------------|------|------|
| **C1** sync-replication off-by-one | **#378** | — | #262 (CLOSED) adjacent — its test `selfInReplicaSet_isNotCounted` *encodes* the shortfall as expected. Confirmed live at HEAD. |
| **C2** KV `Remove` unfenced | **#379** | #345 | sub-issue of the fenced-single-writer epic |
| **C3** DHTConfig docstring / DHT reads | **#380** | — | no prior ticket |
| **C4** `config.notifyChange` dead code | **#381** | — | no prior ticket (#277 unrelated) |
| **C5** DurableEntity "Linearizable get" javadoc | **#382** | #345 / #352 | javadoc fix under durable-entity epics |
| **G6** in-memory / snapshot-only persistence | **#383** | #349 | extends storage-durability epic beyond streaming segments |
| **G7** DHT downgrade undocumented | **#384** | — | no prior ticket; companion to #380 |

**All 7 filed 2026-06-29** (milestone `v1.0.0-rc3`). Priority (blocking / rc2) left to triage — C1 and C2 are confirmed correctness gaps worth pulling forward.

---

## CODE-level (defect or gap — needs an issue, not just a doc edit)

| # | Location | Claim | Why wrong | Action |
|---|----------|-------|-----------|--------|
| C1 | `aether/aether-stream/.../replication/ReplicaSetController.java`; catalog row 139 ("Complete") | "Sync replication ack waits for N replica acks" | RF=`clamp(minSyncReplicas,1,N)` ⇒ peers=`minSyncReplicas-1` < acks needed ⇒ **always `NOT_ENOUGH_REPLICAS`** for `minSyncReplicas≥1`. | ✅ RESOLVED — **#262 two-knob** (supersedes the interim #378 `RF=minSyncReplicas+1`): `replicas` is the placement knob (`RF=clamp(replicas,1,N)`), `min-sync-replicas` sets the barrier (`minAcks=min-sync-replicas−1`). Guarded by `ReplicaSetControllerTest.replicationFactorDerivesFromReplicasNotMinSyncReplicas`. |
| C2 | `integrations/cluster/.../kvstore/KVStore.java:131` (`handleRemove`) | (implicit) epoch fence protects KV writes | `staleWrite` guards **Put only**; `Remove` is unfenced — a deposed owner can delete a fenced key. | File issue: fence `Remove` symmetrically, or document the asymmetry deliberately. |
| C3 | `aether/aether-dht/.../DHTConfig.java:162-167` | "Full replication is **always strongly consistent**. R + W > N ensures any read will see the most recent write." | `FULL` is W=R=1 ⇒ R+W=2 ≤ N; and `QuorumCollector.selectBest` returns first-non-empty, never max-version. | Fix docstring (DOC) **and** consider read-repair/version-aware read (CODE) if strong reads are wanted. |
| C4 | `aether/aether-deployment/.../config/ConfigNotificationManager.java:72-77`; catalog row 176 | "Runtime notification via single-threaded executor with record diff" | `notifyChange` has **zero callers** in main — runtime config-change push is dead machinery; only initial (ACTIVATE) notification fires. | File issue: wire `notifyChange` or remove it; correct catalog 176. |
| C5 | `aether/resource/durable-entity/.../DurableEntity.java:27,53` (shipped javadoc) | "**Linearizable get** … reflects the last committed state" | Wired impl is a per-node local map (no owner-route, no replication) → not linearizable, not even cross-node consistent. | Correct javadoc to "local read; linearizable owner-routed read PLANNED (gated on 1e)". |

---

## DOC-level (wording overclaim — fix the prose)

### feature-catalog.md
| # | Location | Claim | Honest rewrite |
|---|----------|-------|----------------|
| D1 | `:54` (row 17, KV-Store) | "Consensus-replicated store" (implies linearizable reads) | ✅ **APPLIED 2026-08-28** — row now states linearizable write order vs. non-linearizable local reads, cites guarantees.md §1. |
| D2 | `:105` (row 33, DHT) | "quorum R/W … Battle-tested" | ✅ **RESOLVED** (2026-07-17 docs wave, confirmed still live at current HEAD) — row states in-memory-only durability, cites guarantees.md §2. |
| D3 | `:270/271/281` (rows 94/95/152) | "moved from consensus to ReplicatedMap … O(3) vs O(N)" (perf-only framing) | ✅ RESOLVED — see guarantees.md §7 item 7 (#384 CLOSED); rows now carry the eventual/not-crash-durable downgrade pointer. |
| D4 | `:52` (quorum loss) | "graceful degradation on quorum loss, automatic restoration" | ✅ **APPLIED 2026-08-28** — row now states pause/reject-writes + minority self-fence explicitly, cites guarantees.md §3. |
| D5 | `:21/33/52` etc. | "Battle-tested" on Auto-healing / Quorum / DHT | "Validated in E2E + cloud chaos; quorum/self-fence paths hardened through 2026-06." (correctness fixes landed within the last week — A6 2026-06-28, self-drain 2026-06-21). |
| D6 | `:85` (row, message delivery) | "Battle-tested … leader failover scenarios" | "Message delivery — **at-most-once**, unordered, fan-out + round-robin. No retry/persistence; lost if no live endpoint. Subscriptions survive leader change (KV-backed)." |
| D7 | `:84` (row 23) | "competing consumers (round-robin)" | "competing consumers (round-robin) **via EndpointRegistry**." (the `TopicSubscriptionRegistry` RR/nodeId is vestigial dead code — cleanup candidate). |
| D8 | `:180` (row 139) | "Sync replication ack … Complete" | See **C1** — Partial / unsatisfiable. |
| D9 | `:177` (row 146) | "cursor persistence (push + pull)" | "in-RAM cursors with periodic checkpoint; durable persistence depends on the (unwired) cursor-store overload." |
| D10 | `:177` | "zero-copy MemorySegment reads" | "zero-copy **consumer-slice read**; the producer path copies into off-heap." |
| D11 | `:318` (row 192) | "Stream consensus publish path \| Complete" | "STRONG/consensus publish implemented + unit-tested; **not wired** into the node publish path — verify before claiming Complete." |
| D12 | `:311/185/317` | "EvictionListener is NOOP / `CursorStore` never constructed" | **Stale under-claim**: `AetherNode.java:2622-2632` now constructs the segment sink + cursor store + default-on WAL (Phase-A wired; these rows predate it). |
| D13 | `:140` (row 217, durable-entity) | "8 prod classes … HA-oriented" | "process-local in-memory (single-replica, **not** HA, **not** restart-durable); module **not yet a node dependency** — no deployed slice injects it; reachable only as a library." |

### Other docs / skills
| # | Location | Claim | Honest rewrite |
|---|----------|-------|----------------|
| D14 | `operators/monitoring.md:322` | "No single point of failure" | ✅ **APPLIED 2026-08-28** — rescoped to the write path specifically (thresholds aren't leader-pinned, unlike deploy/scale/auto-heal, so the original suggested rewrite here would have misfit the section); added the local-read-may-lag caveat. |
| D15 | `guides/rolling-upgrade.md:3` | "zero downtime" | ✅ **APPLIED 2026-08-28** — scoped to app-downtime; added the core-node quorum-margin caveat, which the guide previously never stated at all. |
| D16 | `streaming-performance-analysis.md:130` | "supporting exactly-once processing" | "**effectively-once** for same-DB side effects, **when** `PgTransactionalCursorCommit` is wired (not in node bootstrap today)." |
| D17 | `archive/infrastructure-slices-design.md:701,767` | "Reliable event publishing with **exactly-once** delivery" (Outbox) | Mark "Planned / not implemented" (archived/aspirational). |
| D18 | skill `aether-coder/.../pub-sub.md:52` | use case "Notifications, **broadcasts**" | "Notifications, fan-out to subscriber **types** (one instance each)" — not a true broadcast to all instances. |
| D19 | `archive/infra-services.md:346` | "Always available: Every node can serve artifacts" | Archived — annotate as superseded (a minority/partitioned node halts and serves nothing). |
| D20 | `architecture/01-consensus.md`, `contributors/consensus.md` — leader-election table/prose | "Strong (all nodes agree)" / "Strong consistency required" | ✅ **APPLIED 2026-08-28** (new finding, not from the original rc2 grounding pass) — the commit itself is linearizably ordered (same Rabia log + `viewSequence` fence as any KV write), but each node applies it as its own consensus round completes, not simultaneously — same-order, not same-instant. Both docs now state this and cite guarantees.md §1. |

### Already honest (no change — credit where due)
- `architecture/01-consensus.md:7,13` "leaderless crash-fault-tolerant (CFT)" — accurate fault-model scoping.
- `management-api.md:353` — explicitly says replica reads "are not linearizable."
- catalog row 206 — states the in-memory persistence default.
- `pg-notifications.md:160,169` — correctly say at-most-once / fire-and-forget.
- `notification-resource-spec.md:917,958` — correctly say at-least-once + no DLQ for `@Notify`.

---

## To make `guarantees.md` truly authoritative (optional follow-ups, not yet done)
- Add a one-line pointer at the top of `feature-catalog.md` → "for guarantee semantics, see `guarantees.md` (authoritative)".
- Reference it from CLAUDE.md project-invariant #2 (Feature Catalog + Changelog) so guarantee claims route through it.
- File issues for C1–C5.
