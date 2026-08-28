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
>
> **#496 progress, 2026-08-28 (surfaces 2 & 3 — KV/durability, deployment/blueprint) — both closed,
> zero further fixes.** Surface 2 (KV/durability): a repo-wide grep for flag-on-sight phrases
> (`strongly consistent|highly available|never loses|fully durable|always consistent|exactly.once`,
> excluding streams/pub-sub/durable-entity/archive territory) found nothing left unaddressed beyond
> what surface 1 already fixed (D1) and what stays correctly deferred (D13 durable-entity, the #676
> backup row). Everything else the grep hit was already honest (`durable-pubsub-spec.md:98`,
> `durable-entity-primitive-spec.md:1107`), a false positive (`http-routing.md` "try every node
> exactly once" is a retry-count bound, not a delivery claim), or squarely in deferred streams
> territory (`in-memory-streams-spec.md`, `streaming-spec.md`, `hierarchical-storage-spec.md` — all
> D8–D12/D16 already logged above). **Concluding surface 2 as substantially covered by surface 1.**
> Surface 3 (deployment/blueprint semantics): read all 8 candidate docs
> (`architecture/02-deployment.md`, `slice-developers/deployment.md`, `guides/deploy-guide.md`,
> `guides/rolling-upgrade.md` [D15, already fixed in surface 1], `specs/unified-deploy-spec.md`,
> `operators/{multi-cluster-deployment,docker-deployment,deployment-recovery}.md`,
> `operators/runbooks/deployment.md`). The `ALL_OR_NOTHING`/blue-green atomicity claims already earn
> their wording — mechanism named (single consensus-batch `KVCommand` write across all slices' keys,
> `InFlightBlueprint` rollback tracking, "~100ms via single Rabia round" for the blue-green switch) —
> so no fix needed. `deployment-recovery.md:73` is already exemplary: it explicitly explains why
> "highly available with automatic restart" does **not** describe Aether's node-recovery model
> (terminal-removal membership, fresh-ULID reprovision, not restart), naming the mechanism precisely —
> flagged as a good/honest example, not a finding. `multi-cluster-deployment.md`, `docker-deployment.md`,
> `runbooks/deployment.md` carry no guarantee-language claims at all (pure operational procedure).
> `unified-deploy-spec.md`'s own atomicity claim (REQ-4) is likewise well-grounded (batch consensus
> apply), but its `/api/deploy/*` route-namespace content is entangled with the deferred `/api/v1`
> hard-cutover territory (stream-operator, #300) and was left untouched for that reason, not a
> guarantee-wording reason. **Concluding surface 3 as audited, clean, zero fixes.**
> **#496 scoped pass (consensus/cluster-core, KV/durability, deployment/blueprint) is now complete.**
> Remaining open items in this file (D6–D13, D16–D19) all sit in explicitly deferred territory
> (streams, pub-sub, durable-entity, archive) and are correctly left for whichever stream owns that
> re-grounding pass next.
>
> **#496 progress, 2026-08-28 (pub-sub — D6/D7/D18) —** re-audited against current `HEAD` per
> guarantees.md §5 (not deferred: the original deferral was scope-naming, stream B being unlaunched
> is not a reason for pub-sub claims to stay dishonest meanwhile). **Applied:** D6 and D7
> (`feature-catalog.md` rows 24/23 — both had drifted to new line numbers since rc2; rewritten in
> place rather than pasting the stale rc2-era boilerplate). **New finding beyond the original rows:**
> D21, `slice-developers/resource-reference.md`'s subscriber-facing Behavior section omitted all
> delivery-loss information — read as reliable-by-omission; fixed. **Blocked, not applied:** D18 —
> its target (`aether-coder/.../pub-sub.md`) is not part of this repo's git-controlled territory at
> all (empty `git log --all` for that path, `.claude/` gitignored here); it lives only in the
> separate main `pragmatica` clone and local skill-cache directories. Flagged to team-lead for
> routing rather than silently edited cross-repo or silently dropped.
>
> **#496 progress, 2026-08-28 (archive — D17/D19) —** cheap, closed. **Applied:** D17 (Outbox
> "exactly-once" — confirmed genuinely unimplemented via `infra-slices-progress.md`'s unchecked box
> and a repo-wide search finding no `Outbox` class, then marked "Planned / not implemented"), D19
> (artifact-repository "Always available" — annotated as superseded, with the design decision's real
> justification named instead of the false universal-availability claim). All D-rows gated on another
> stream (D8–D13/D16, management-API routes, backup) remain correctly untouched.

### Surface 3 file audit (2026-08-28) — deployment/blueprint, per-file disposition

All 8 candidate deployment/blueprint docs, so the read-vs-skip boundary is enumerable rather than
re-derived from the prose above:

| File | Guarantee-language claims? | Disposition |
|---|---|---|
| `architecture/02-deployment.md` | Yes — `ALL_OR_NOTHING`/`BEST_EFFORT` blueprint atomicity | Already earns the claim (batch-consensus mechanism named, `InFlightBlueprint` rollback tracked) — no fix |
| `slice-developers/deployment.md` | Yes — same `ALL_OR_NOTHING` claim, dev-facing | Already earns the claim — no fix |
| `guides/deploy-guide.md` | Yes — blue-green "atomic switchover" | Already earns the claim (mechanism + timing named: "~100ms via single Rabia round") — no fix |
| `guides/rolling-upgrade.md` | Yes — "zero downtime" | Fixed in surface 1 (D15) |
| `specs/unified-deploy-spec.md` | Yes — REQ-4 "atomic multi-slice transitions" | Already earns the claim (batch consensus apply); `/api/deploy/*` route content untouched — entangled with the deferred `/api/v1` cutover (#300), not a wording gap |
| `operators/deployment-recovery.md` | Yes — contrasts "highly available with automatic restart" | Already exemplary: names the actual mechanism (terminal-removal membership, fresh-ULID reprovision) precisely — credited, not a finding |
| `operators/multi-cluster-deployment.md` | No | Skipped — pure operational procedure |
| `operators/docker-deployment.md` | No | Skipped — pure operational procedure |
| `operators/runbooks/deployment.md` | No | Skipped — pure operational procedure |


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
| D6 | `:97` (row 24, message delivery — row moved from the rc2-era `:85`) | "Battle-tested … leader failover scenarios" | ✅ **APPLIED 2026-08-28** — row now states delivery is at-most-once/unordered/best-effort, no retry or persistence, silently dropped if no live subscriber instance (publish still reports success), registration (not the message) survives leader change. Cites guarantees.md §5. |
| D7 | `:96` (row 23 — row moved from the rc2-era `:84`) | "competing consumers (round-robin)" | ✅ **APPLIED 2026-08-28** — row now names `EndpointRegistry` as the mechanism doing the round-robin fan-out across a subscriber slice's live instances, distinct from `TopicSubscriptionKey`'s KV-Store registration bookkeeping. |
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
| D17 | `archive/infrastructure-slices-design.md:701,767` | "Reliable event publishing with **exactly-once** delivery" (Outbox) | ✅ **APPLIED 2026-08-28** — marked "Planned / not implemented" (confirmed: unchecked in `infra-slices-progress.md`, no `Outbox` class anywhere in the runtime), "exactly-once" reframed as the intended effectively-once outcome of the (unbuilt) pattern, not a shipped guarantee. |
| D18 | skill `aether-coder/.../pub-sub.md:52` | use case "Notifications, **broadcasts**" | ⛔ **TERRITORY-BLOCKED, 2026-08-28** — the target file does not exist anywhere in this repo's git history (`git log --all -- .claude/skills/aether-coder` is empty) and `.claude/` is itself gitignored here; it only lives in the separate main `pragmatica` clone and in local, non-version-controlled skill-cache directories. Not committable from `pragmatica-stream-e`. Flagged to team-lead for routing to whichever stream/session owns the skill's actual source; honest rewrite unchanged from the row above pending that routing. |
| D21 | `slice-developers/resource-reference.md:1034-1039` (Pub-Sub Messaging → Behavior) | (implicit, by omission) — Behavior list described registration and routing but said nothing about delivery loss, reading as if delivery were reliable | ✅ **APPLIED 2026-08-28** (new finding, not from the original rc2 grounding pass) — added an explicit at-most-once/no-persistence/no-retry/dropped-if-no-live-instance bullet, and reworded "routed to any node with a subscriber loaded" (ambiguous, readable as broadcast) to state one delivery per subscribing slice, round-robined across that slice's live instances. Cites guarantees.md §5. |
| D19 | `archive/infra-services.md:346` | "Always available: Every node can serve artifacts" | ✅ **APPLIED 2026-08-28** — annotated as a superseded claim (a minority/partitioned node halts and serves nothing, guarantees.md §3), with the actual property this design decision relies on named instead (no separate slice-deployment bootstrap dependency). |
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
