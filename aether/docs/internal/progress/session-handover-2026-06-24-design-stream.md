# Design-Stream Session Handover — 2026-06-23 / 24

| Field | Value |
|-------|-------|
| Stream | **design-stream** (clone of `../pragmatica`; `upstream` = local sibling, `origin` = `pragmaticalabs/pragmatica`) |
| Workflow | one design branch per topic off the release tip → PR into `release-1.0.0-rc2` |
| Output | **6 design specs (PRs #340–#347)** · 1 epic (#345) · filed #345 · reframed+closed #190 |
| Status | all specs are **Draft, in PR, awaiting review** — none merged |

> Continuity doc for the design-stream's first major session. The stream's job is design-heavy work so
> the main stream can implement/test. Everything below is **design only** — no production code changed.

---

## ⚡ TL;DR

- **Six specs out as PRs**, each on its own branch off the release tip: security subsystem, #265
  placement-aware stream hydration, #277 runtime observability aspects, durable-entity primitive,
  #345 ownership fence (+ this handover).
- **The through-line is a durable-execution vertical:** #190 (Persistent Workflow) was reframed into a
  **`DurableEntity` primitive** (workflow + saga as facades), gated on a **per-key ownership fence**
  (#345). Chain: **#345 fence → `DurableEntity` → {workflow, saga}**. #190 closed as superseded; #345
  filed then promoted to the **epic** for the whole stack.
- **Two critical substrate findings surfaced (both latent today):**
  1. **Ownership is unfenced** — governor/stream/partition ownership is advisory HRW, unchecked on the
     write path → split-brain double-writes possible. Now epic #345 / PR #347 (rc2).
  2. **Persistence is memory-only** — nothing in the prod stream/DHT path survives a full-cluster
     restart; the durable tiers (disk/S3/Pg) exist as code but are wired as no-op. "Committed" = "in
     RAM." Now **epic #349** (path-(a) minimal viable: wire sealer + `LocalDiskTier` + index rebuild,
     ~10 lines; bundles #248/#249/#250/#252/#264 + completion + integration tests).
- **Two foundations gate the durable entity:** the **#345 fence** (correctness, PR #347) and **#349
  persistence wiring** (durability). Both are wiring/extension of existing code, not build-from-scratch.
  The entity spec's state model was **re-weighted to log-on-stream** (rides #349 path a) — PR #346.

---

## 1. Deliverables — the six PRs

| PR | Branch | Topic | Notes |
|---|---|---|---|
| **#340** | `design/security-subsystem` | **Unified security subsystem** | 5 planes (root-of-trust/identity, certs, keys, secrets, policy+audit) + runtime/slice boundary + credential-less consumption. Cloud-authored, then **reviewed & fixed via /fix-all** (corrected #88/ScopedValue overstatements, citation drift) + added **§5.7 crypto inventory & quantum-resistance posture**. Companions: `session-transcript-2026-06-23-security-subsystem-design.md`, `security-subsystem-spec-plan.md`. |
| **#341** | `design/placement-aware-hydration` | **#265 placement-aware stream hydration** | Only HRW replica-set nodes materialize a partition's ring; ISR-gated reshuffle (materialize→backfill→release); derived partition cap; **folds in #261** backfill fix. |
| **#342** | `design/observability-aspect-switching` | **#277 runtime observability aspects** | Two layers: user interceptors (frozen) vs **system observability aspect** (always-on, runtime-switchable). Push-on-KV-event to a per-instance `volatile` snapshot (no re-provision, no per-call lookup). Realizes RFC-0010. |
| **#346** | `design/durable-entity-primitive` | **Durable single-writer entity** (supersedes #190) | The primitive; workflow + saga as facades. Fenced KV-snapshot state (state-as-truth, no replay); fenced-log evolution. Virtual-actor model. |
| **#347** | `design/ownership-fence` | **#345 per-key ownership fence** | Fencing tokens from the already-CP `Epoch`, enforced at the per-replica commit point, per ownership domain, + owner-routed linearizable reads. **1a** (generalize `staleLeaderWrite`) + **1b** (data-plane fence) — **both rc2**. |
| (this) | `design/session-handover-2026-06-24` | **This handover** | — |

All target base `release-1.0.0-rc2`; review/merge independently.

---

## 2. The durable-execution vertical (the spine)

```
  EPIC #345 — durable single-writer entity
   ├─ piece 1: per-key ownership FENCE (rc2)  ── PR #347 ──┐ correctness
   ├─ piece 2: per-key serialization queue                 │
   ├─ piece 3: durable per-entity timers                   │
   ├─ piece 4: DurableEntity core  ── PR #346 ─────────────┤ the primitive
   ├─ piece 5: Workflow facade (was #190)                  │
   ├─ piece 6: Saga facade + run-once step                 │
   └─ piece 7: observability + audit                       │
                                                            ▼
            DURABILITY (separate foundation): persistence wiring — §3
```

**Reframe rationale:** #190's correctness rested entirely on the missing fence; once fenced, a workflow
is "an entity whose update is an FSM transition" and a saga is "an entity orchestrating steps." So the
unit is the **entity** (Orleans/Restate/Dapr convergence), not a workflow engine. Adopting
Temporal/Restate was rejected — a second failure domain + partition model beside Aether's own.

**Execution-model decision (settled):** state-as-truth / **no replay** / no SDK / no determinism
contract — the most defensible no-SDK position (server-side state machine family; Restate/DBOS still
require deterministic replay).

---

## 3. The persistence / durability finding (critical, latent today)

**Aether's prod storage is memory-replicated, not durable to disk/object-storage.** Two subsystems both
bottom out in `ConcurrentHashMap`: the per-write tier waterfall (memory→DHT, live, but DHT is in-memory)
and the AHSE lifecycle (sealing/promote/demote/GC/disk/S3 — built but wired as no-op).

| Data | survives single-node failure? | survives full-cluster restart? |
|---|---|---|
| Stream data | ✅ in-memory RF replication | ❌ sealing is `EvictionListener.NOOP` |
| Stream cursors | ❌ node-local map | ❌ `PgCursorStore` unwired |
| Stream/ref metadata | ⚠️ rebuilt on replicas | ❌ `InMemoryMetadataStore`, no snapshot |
| DHT KV (entity state) | ✅ RF=3 + anti-entropy | ❌ `MemoryStorageEngine` (`AetherNode.java:392`) |

Durable code exists but is unwired in prod: `EvictionListener.NOOP` (`AetherNode.java:2461`),
`DelegatedStorageAdapter.noOp()` (`:1791`), `MemoryStorageEngine` (`:392`); `RemoteTier`/S3 test-only;
`PgCursorStore`/`PgStreamStore` dead code. `LocalDiskTier` wired **only** for the `artifacts` instance.
Tracked by **#248** (sealing), **#249** (RemoteTier/S3), **#250** (GC noOp), **#252** (metadata→KV),
**#264** (cursors). **Implication:** a "durable entity" built today is HA, not restart-durable. The
durable-entity spec's "durable KV" needs this caveat (not yet edited — see next steps).

---

## 4. Tickets touched

- **Filed #345** → promoted to **EPIC** "Durable single-writer entity — fenced substrate → entity
  primitive → workflow & saga" (piece-1 fence = rc2; full breakdown + spec link in the issue).
- **Closed #190** (Persistent Workflow) as superseded; design carried forward as the workflow facade
  (epic piece 5 / spec §6). Reopenable if the facade wants its own sub-issue.

---

## 5. Key decisions (locked vs. recommended)

- **Security (#340):** all-clouds + on-prem first-class; complete GA (no deferral); native+federate;
  credential-less slices; in-process classloader+JPMS isolation; ScopedValue Principal. (User-mandated.)
- **#265:** placement-gated hydration; ISR-gated reshuffle; derived create-time partition cap; fold #261.
- **#277:** interceptors stay frozen; only system observability is switchable; push-on-event volatile
  snapshot (no re-provision, no per-call lookup); per-injection-point × facet.
- **Entity/#345:** entity is the primitive (not a workflow engine); state-as-truth/no-replay; fence =
  fencing tokens on the already-CP epoch; **owner-routed linearizable reads**; per-domain granularity;
  full fence (1a+1b) in **rc2**.

---

## 6. Open threads / next steps

1. **Stream persistence deep-dive** — ✅ done. Finding: it's complete code broken at one wire
   (`EvictionListener.NOOP`); the "durable" tier is in-memory DHT; durable reads return empty. Path-(a)
   (sealer + `LocalDiskTier` + index rebuild) is the ~10-line minimal viable fix.
2. **Persistence-wiring foundation** — ✅ filed as **epic #349** (bundles #248/#249/#250/#252/#264 +
   completion reconciliation + integration tests; path-(a) recommendation; durability sibling to #345).
3. **Durable-entity spec re-weight** — ✅ done (PR #346): state model now prefers **log-on-stream** for
   durability (rides #349 path a); added #349 as a dependency; "durable" downgraded to "HA until #349".
4. **Remaining standalone design candidates:** #144 (distributed rate limiting), the API-contract
   cluster #226/#339 (partial), cloud #298/#306 (partial).
5. **To make the entity stack pickup-ready:** file epic #345's pieces 2–7 (serialization queue, durable
   timers, `DurableEntity` core, workflow facade, saga facade, observability) as discrete sub-issues —
   not yet done. Otherwise specs carry reconciliation tables + acceptance criteria.
6. **Per-spec open questions** live in each spec's "Open Questions" section.

---

## 7. How to pick up

- Each spec is a PR into `release-1.0.0-rc2`; review/merge independently. None are merged.
- The design-stream clone lives at `IdeaProjects/pragmatica-clone`; sync with
  `git fetch upstream && git rebase upstream/release-1.0.0-rc2`; one design branch per topic off the
  release tip; PR to origin (shared repo).
- Specs are in `aether/docs/specs/`; this handover + the security transcript/plan in
  `aether/docs/internal/progress/`.

---

*Design-stream session 2026-06-23/24. Six specs, one epic, two foundational substrate findings
(fence + persistence). Nothing merged; nothing in production touched.*
