# Session Handover — 2026-06-25

**Branch:** `release-1.0.0-rc2` · **HEAD `8c8e8757c`** · **pushed**; candidate tag `v1.0.0-rc2-candidate` moved to `66a9e8072` (re-create on HEAD after the next batch). Envelope `1005` (will go `1006` when #277 reworks — see below). Working tree clean.

This was a multi-thread session: shipped the catalog example + **four platform fixes it surfaced**, set up the **multi-agent collaboration**, and drove **#345 ownership fence** P1 through 1d-ii (fence proven end-to-end), plus reviewed/merged collaborator PRs.

---

## ⚡ TL;DR
- **Catalog example (Task 0) → 4 platform fixes.** Building `examples/catalog` and *running it* surfaced real bugs in shipped rc2 features. All fixed, committed, pushed; issues filed + closed.
- **Multi-agent collaboration is live.** I (this session) **drive + own all merges**; **aether-clone** (in `../pragmatica-clone`) works **#277**; **another agent** works **#241**. Split **by feature**; hand-off + review via **GitHub PRs** (aether-clone watches GitHub). I never let them self-merge.
- **#345 ownership fence (mine):** STEP-0 + 1a–1d-ii committed & pushed. **The fence is proven end-to-end (STEP-0 flips on a live cluster) but DORMANT in production until 1d-iii wires the owner-change driver.** Remaining: 1d-iii, 1e, 1f, then cloud gate.
- **Open PR:** #356 (#277 PR1) — I posted **request-changes** (per-injection-point, not per-slice; envelope 1005→1006); awaiting aether-clone's rework.

---

## ✅ Shipped this session (all on `release-1.0.0-rc2`)

### A. Catalog example + the 4 fixes it dogfooded
| Commit | What |
|---|---|
| `e229255a3` | **fix #343** — arity-aware + per-version-router HTTP route selection (path **and** header mode) |
| `fcd25af99` | **fix #344** — slice-processor emits `spacer(...)` for static-segment-after-param routes; **envelope 1004→1005** |
| `e2e8c2dfe` | fix — `forge run` honors `aether.toml` `[app-http] api_versioning_detection` (was a test-only `EmberCluster` seam) |
| `c7f8cbabf` | docs — `examples/catalog` (media-types + API-versioning showcase, in-reactor) |
| `6bf403c69` | docs — CHANGELOG + reconcile #343/#344 + catalog |

- **#343** (router): an exact collection route (`GET /items`) was shadowed by a sibling `/{id}` route (arity-blind `findFallbackRoute`), 500ing in path mode; **header mode** had a separate bug (`selectVersionedRoute`'s `byVersion` `toMap` collapse). Fixed structurally: `Route.pathParamCount()` arity-aware selection (path) + **per-version routers** (header, deleting the collapse + `RequestRouter.findCandidates`).
- **#344** (codegen): `RouteDsl.basePath()` truncated paths at the first `{`, dropping any static segment after a param (`/items/{id}/image` → `/items/{id}`). Now emits `PathParameter.spacer("seg")` interleaved; lambda binds spacer slots to `_`. Also repaired `examples/url-shortener`'s `POST /{shortCode}/click` (verified; url-shortener pins rc1 so inherits on rc2 rebuild).
- Issues **#343 / #344 filed + closed** with fix references. Catalog live-verified in **both path and header mode** on a 5-node Forge cluster.
- **Gotcha learned (in memory):** the slice envelope packages ONLY the `@Slice` type's nested class graph + generated Factory/Routes — **sibling top-level classes are excluded** → `ClassNotFound` at deploy. Nest all helpers in the `@Slice` interface. Build + unit tests do NOT catch it; only a live deploy does. See `[[project_slice_envelope_packages_nest_only]]`.

### B. #345 — Durable single-writer entity / ownership fence (Phase 1)
Plan: [`issue-345-implementation-plan.md`](issue-345-implementation-plan.md) (READ IT — phasing, the resolved decisions, the 1d/#265 boundary).
| Commit | Item |
|---|---|
| `0833ea9fd` | plan doc |
| `cefa07f33` | **STEP-0** — split-brain Forge baseline (`OwnershipFenceBaselineTest`) documenting the bug |
| `442b10027` | **1a** — generalize `staleLeaderWrite`→`EpochBearing` in the Rabia applier (CP-plane fence; governor + DHT-partition ownership values) |
| `db8ba3c92` | **1b** — per-domain high-water table (`OwnershipDomain`/`OwnershipEpochHighWater`; community + DHT-partition; seeded/observed via KV notifications) |
| `799513a8d` | docs — 1d decision (per-stream-partition reshuffle epoch, #265-entangled) |
| `cf8bdffd1` | **1c** — DHT data-plane fence (owner-epoch gate at the replica; epoch as primitives across the `integrations/dht` boundary via `OwnerEpochGate` SPI; binding in `aether-dht`) |
| `7d7564e05` | **1d-i** — stream-partition ownership-epoch primitive (`StreamPartitionOwnershipKey/Value` `EpochBearing`, mirrors DHT) + leader-only `StreamPartitionOwnershipWriter` + `OwnershipDomain.StreamPartition` |
| `66a9e8072` | **1d-ii** — stream append epoch fence (`StaleEpochAppend`, enforced at replica on local + replicated-receive paths); **STEP-0 flipped accepted→rejected** on a live cluster via the real committed-ownership observe chain |

**The fence model (4 planes):** epoch from an `EpochBearing` ownership/governor KV value; CP-plane (KV applier, 1a) fences any `EpochBearing` write for free; data-plane (DHT 1c, stream 1d) gates each write against a per-domain monotonic high-water (1b) at the **replica**, rejecting a strictly-older (deposed) epoch. No envelope bump (KV-store consensus format, not slice envelope). Verified each step (unit + Forge); **full reactor green** after every code increment.

### C. Collaborator PRs reviewed/merged
- **#357 (#241 slice 1 — committed CommunityId substrate)** — **MERGED** (squash, `8c8e8757c`). Verified: clean 3-way merge with #345's KV changes (`AetherKey`/`AetherValue`/`KVStoreSerializer` — *file overlap*, but different types/arms) **and the merged result compiles**; build-and-test + CodeRabbit green; forge-tests failure ruled out as the `/data` env-flake (`ClusterFormationTest` + `OwnershipFenceBaselineTest` pass on its branch, additive/CORE-unchanged).
- Earlier: docs PRs **#342/#346/#347/#348** merged (design-stream specs for #277, #345 epic, observability).

---

## 🤝 Collaboration model (IMPORTANT — how to operate)
- **I drive + own ALL merges.** aether-clone (#277, `../pragmatica-clone`) and the #241 agent open PRs; **I review + merge — they never self-merge.** Both features are by the user's order.
- **Split by feature** (whole features per agent, not sub-tasks), so PRs don't collide *within* a feature. **But #345 and #241 BOTH edit `AetherKey.java`/`AetherValue.java`/`KVStoreSerializer.java`** — coordinate: always test-merge + compile before merging an overlapping PR (git auto-merges different arms, but verify it compiles).
- **#277 hand-off brief** for aether-clone: `scratchpad/277-observability-brief-for-aether-clone.md` (delivered). **Correction made mid-session:** my brief's "no codegen / no envelope bump" was WRONG — the spec (§7.2) requires **per-injection-point × facet** granularity and explicitly rejects per-slice; that needs the generated factory to emit per-injection-point aspects (codegen + **envelope 1005→1006**). PR #356 carries the request-changes with this direction.
- **PR-watch monitor** (`brue93tyf`) is running — emits each new open PR (with files) so I catch collaborator PRs. (May not survive into the next session.)
- **Same GitHub identity:** all agents commit under the user's account, so I can't formally `--request-changes`/approve — I post the review as a PR **comment** instead.

---

## 🎯 NEXT (in priority order)

**1. #345 — finish P1 (mine).**
- **1d-iii — wire the owner-change driver** (the leader membership-tail driver that iterates the stream catalog and calls `StreamPartitionOwnershipWriter.writeOwnershipChange` → applies the `Put` via `ClusterNode`). **Without this the stream fence is dormant in production** (the writer never fires → high-water never advances). Build it **behind a Forge gate** (a real owner-change fires the writer → high-water advances → deposed owner rejected end-to-end). Reconciler-under-load sensitive (one Put per moved partition) — load-optimization (batching) is #265's job; the cloud gate validates under churn. *(I overrode the 1d-i agent's suggestion to defer this to #265 — the epoch-advance is the fence, not the ring lifecycle.)*
- **1e** — owner-routed linearizable reads + takeover catch-up + typed `NotCurrentOwner`.
- **1f** — ownership/epoch observability triad (`GET /api/ownership/{domain}` REST→CLI→Docs) — per observability-first; also unblocks the cloud handover test (no public owner/epoch query exists today — STEP-0 had to reconstruct via pure HRW).
- **Cloud gate** — Phase-1 terminal validation under real governor handover + churn (reconciler-under-load class). Runs LOCALLY (Mac has Java 25 + `HCLOUD_TOKEN` + pg-env). Reaper discipline: `--skip-teardown` + cluster-scoped reap, preserve test-PG (`aether-test-pg`/88.198.147.80). See `[[project_cloud_acceptance_reaper_discipline]]`.
- **Then P2 entity-HA → P3 durability (#349 path-a) → P4 facades** per the plan.
- Open follow-ups in 1a/1b: two other epoch-carrying values (`SpokesmanValue.assignedEpoch`, `NodeRoutesValue.observedCoreEpoch`) left **un-fenced** (Open-Q4) — `NodeRoutesValue` is an *observation* (leave it); decide `SpokesmanValue` before P1 closes. `StreamConsensusCommand` (strong publish) unfenced because it's a dead path today (`CONSENSUS_PATH_UNAVAILABLE`) — fence it when a real consensus applier is wired.

**2. #277 (aether-clone) — PR #356 rework.** Awaiting aether-clone's per-injection-point version. Review when it re-pushes (verify the generated factory threads per-injection-point aspects, envelope 1005→1006, no per-slice).

**3. #241 (other agent).** slice 1 merged; next Phase-A slices (per-community FSM, growth comparator, etc.) will arrive as PRs — review for #345 KV-file overlap each time.

---

## 📌 Discipline / gotchas
- **Build safety:** `mvn install` (not just `verify`) fires `HetznerCloudIT` with `HCLOUD_TOKEN` set → ALWAYS `env -u HCLOUD_TOKEN` + `-DskipTests`. Forge tests via `integration-test -Dit.test=… -Dfailsafe.failIfNoSpecifiedTests=false` (needs `-Pwith-e2e`), NEVER `verify`. The `build-runner` agent owns Maven.
- **Forge CI env-flake:** `forge-tests` fails on the CI runner because `/data` (disk tier) is unavailable → consensus commit failures/backpressure → ~1 test timeout. Affects EVERY PR. `build-and-test` + CodeRabbit are the real gates; merge over the forge-tests red **after confirming the failure signature is the env-flake** (disk-tier/QUIC/consensus-timeout), not a real regression — extra care for PRs that touch deployment/consensus.
- **Streaming is success-critical** (`[[project_streaming_is_essential]]`): the durable-log substrate (#345 P3 / #349 path-a) + #265/#261 must be high-quality — no shortcuts.
- **Merge discipline:** test-merge + compile before merging any PR that overlaps #345's KV files. `--admin` to bypass the flaky forge required check; `--squash --delete-branch`.
- aether/** = BSL-1.1; integrations/** = Apache-2.0. Single-line commits, no trailers.

## Scratchpad artifacts (this session, not committed)
- `scratchpad/277-observability-brief-for-aether-clone.md` — the #277 hand-off brief.
- `scratchpad/pr356-review.md`, `pr357-*.md`, `issue-router.md`, `issue-codegen.md` — review/issue drafts.
