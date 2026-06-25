# Session Handover — 2026-06-25b (autonomous run: #345 fence delivered + cloud-validated, Phase 2 started)

**Branch:** `release-1.0.0-rc2` · **HEAD `fe0ef0884`** · pushed. Candidate image `f2619de3a` (fence-complete). Supersedes the earlier `session-handover-2026-06-25.md` for everything after the fence was queued.

## ⚡ TL;DR
- **#345 ownership fence (P1) is DONE, Forge-gated, and CLOUD-VALIDATED.** All of 1a–1f + DHT-parity shipped. The cloud acceptance ran **13/15 green**; the **2 failures are SWIM/membership-under-churn (aether-clone's lane), NOT the fence** — the fence passed everywhere it's exercised (streaming, scale-down-under-load 0.00% error, no-data-loss).
- **A pre-existing non-fence cloud blocker was found + fixed:** stale rc1 test-blueprints (moved-FQN `ClassNotFoundException`) + a harness false-pass. Plus a regression gate so it can't recur silently.
- **#345 Phase 2 started:** first slice (durable-entity resource module + per-key serialized in-memory core) committed.

## ✅ Fence P1 — delivered (commits on `release-1.0.0-rc2`)
| Item | What |
|---|---|
| 1d-iii `7d7564e05`+`66a9e8072` | stream ownership driver wired → fence **live in prod** |
| epoch-gap fix (in 1d-iii) | epoch was advancing only on leader re-election; now `Epoch(rabiaTerm, ownershipTerm)` → advances on **every** owner change (same-term reshuffle closed). Forge-proven: deposed-but-alive owner rejected at same term. |
| DHT-parity `7becea24f` | identical fix in `BootstrapModule` (fix the class, not the case) |
| 1f `7becea24f` | `GET /api/ownership/{domain}` triad (REST+CLI+docs) |
| 1e `f2619de3a` | owner-routed linearizable reads + catch-up gate + typed `NotCurrentOwner`/`StaleEpochRead` (raw/HTTP path; typed-path is the #16 follow-up — NOT a P2 blocker) |

Fence model: per-domain epoch high-water; CP applier + DHT + stream data-plane all advance epoch on owner change. Full-reactor green after every increment.

## ☁️ Cloud acceptance — 13/15, the 2 fails are SWIM-domain
Run via `bash aether/tests/integration/run-tests.sh --env cloud --runtime container --skip-image-push --skip-teardown` (source `/tmp/aether-test-pg.env` + `pg-firewall open` first). Candidate image is `f2619de3a` (blueprints are NOT in the image — they're pushed by the harness from local `aether/tests/blueprints/*/target`).
- **Green (13):** cluster-A all 10 (00,04,06,07,08,09,10,11,14,15) + cluster-B 05,13,12.
- **03-scaling FAIL** — only the scale-up-to-7 step (got 6/7; **provisioning breaker tripped at 17 failures**). Scale-down-under-load passed 0.00% error + no-data-loss. → reconciler-under-load/provisionNode-stall (RC backlog) + possible Hetzner cx33 zone capacity.
- **02-chaos FAIL** — `restart_all_nodes also failed; cluster is unrecoverable` after the chaos kill (no reform). → SWIM/membership-under-churn.
- **Both are the class aether-clone's SWIM fix + community-membership should close** (per repo owner). Re-run cloud after that lands; it'll go green or expose what SWIM was masking.
- **Reaper:** cluster VMs carry only `aether-node-id` (no `aether-cluster`) labels → the bare reaper would kill the PG. **Reap by explicit VM ID, preserve PG `144169086` (`aether-test-pg`/88.198.147.80).** `pg-firewall close` after. Did this; only PG remains.

## 🔧 The cloud blocker that was fixed (NOT the fence)
First cloud run aborted at 00-smoke: every slice 404'd. Root cause = `ClassNotFoundException: org.pragmatica.http.routing.HttpError` in `HttpRoutePublisherImpl` → route publish throws → no routes register. `HttpError`/`HttpStatus`/`ContentType`(+2) moved `org.pragmatica.http.routing` → `org.pragmatica.http` (`http-types`) in commit `76a2a6b91`. Codegen was already correct; the **deployed test-blueprint JARs were stale**: `aether/tests/blueprints/{test-echo,test-persistence,test-full}` **pinned `platform.version=1.0.0-rc1`** AND aren't in any reactor (so root builds never refreshed them). Forge passes (single full classpath) — only the distributed `SliceClassLoader` envelope path trips it.
- **Fix `926cafc2b`:** bumped the 3 blueprint poms rc1→rc2 (regen against current slice-processor — javap-verified new FQN) + fixed harness `app_route_wired` (cluster.sh:1312) to match the 404's `detail` field, not `title` (it was silently passing real route-missing 404s).
- **Regression gate `d56d2a372`:** `aether/node/.../SliceRoutingApiResolveTest` (real `SliceClassLoader` resolves all 5 moved routing classes; negative-control on the old FQN) + a codegen-import assertion in `slice-processor-tests`. Proven to bite.
- **⚠ Release-checklist gap:** the 3 test-blueprints are external-style fixtures pinned to a published platform version — **bump their `platform.version` every release** (rc2→rc3…), or wire a freshness check. They are NOT in the reactor by design.

## 🏗️ #345 Phase 2 — started (durable entity, HA-first)
Spec `durable-entity-primitive-spec.md` §5 API; plan Phase 2 = 2a per-key serialization, 2b fenced KV-state, 2c timers. **HA-only (in-memory→fenced-KV), Forge-sufficient, no cloud gate.** #16 typed-path LINEARIZABLE is NOT a P2 prerequisite (entity reads via the owner/DHT path already wired in 1e).
- **First slice `fe0ef0884` (2a + SPI):** new module `aether/resource/durable-entity/` (wired into `aether/resource/pom.xml`). `DurableEntity<K,S>` interface (§5), `InMemoryDurableEntity` per-key serial core (`ConcurrentHashMap.compute` tail-append + `Promise.fold` chaining, non-poisoning, no locks/threads), `DurableEntityFactory` SPI (mirrors `resource-http`, pure SPI — no framework/envelope edits), typed `DurableEntityError`. 16 tests. Timers return `TimerNotSupported` (2c). Note: single hot-key throughput ~20ms/op (executor hop) — inherent per spec §1.3, dominated by the DHT commit in 2b.
- **2b-i `81659d602` (fenced state foundation):** `FencedDurableEntity` over the proven `StorageEngine`+`OwnerEpochGate`+`OwnerEpochSource` seam — fenced commit via `putVersioned` stamped with the owner epoch; deposed-owner write REJECTED → typed `StaleOwner`. 30 tests (5 fence-reject + 9 behavior + the 16). **Additive: the production factory still returns `InMemoryDurableEntity` (not yet wired).**

### NEXT — 2b-ii/iii: TWO design forks for the repo owner (don't big-bang past these)
The 2b agent's scope surfaced two non-trivial decisions:
1. **Owner-routed updates need a NET-NEW mechanism.** Verified: stream-publish forwards data to the owner, linearizable-read forwards to the committed owner, but **nothing runs an arbitrary mutator on the partition owner.** 2b-ii must build owner-routed `update` invocation (route key→partition→owner via `ReplicaSetController.ownerFor`, run the per-key queue + pure mutator ON the owner). This is the substantive 2b work.
2. **The fence granularity question (load-bearing for correctness).** 2b-i fences via the **coarse `"core"` governor arc** (the 1c DHT fence) → gives governor-handover split-brain correctness (spec §4.4 / plan 2b scope). But the entity's *per-key single-writer* guarantee needs **per-partition fencing** — the per-stream-partition epoch (1d-style, `StreamPartitionOwnershipValue.ownerEpoch`), which advances on a key's partition-owner change (a same-generation reshuffle the coarse fence misses). **Decision needed: does 2b move the entity onto the per-partition fence (correct per-key), or is governor-arc + reliable owner-routing sufficient for HA-first?** I lean per-partition (it's the same gap we closed for the stream fence), but it's the repo owner's call — flag before building 2b-ii.
- **Also for 2b-ii:** provisioning wiring (inject DHT/fence/owner-resolver into `FencedDurableEntityFactory` via `ProvisioningContext.extension` — touches `AetherNode`, sequence after #277); quorum replication via `DHTClient` (2b-i is single-local-replica); extract a shared `PerKeySerialExecutor` (the per-key tail-chain is duplicated in `InMemory`+`Fenced`). Then **2b-iii** owner-routed linearizable `get`, **2c** timers.

## 🤝 Collaboration
aether-clone owns **SWIM fix + community membership management** → enables scaling beyond 5-7-9 cores + closes SWIM issues; should fix (or unmask) the 03-scaling/02-chaos cloud failures. PRs #357/#358 (#241 slices 1-2) merged earlier; **#359 (#241 slice 3) was HELD** — its `build-and-test` hung 45 min (likely a deadlocked integration test); flagged for the agent, not merged.

## Build/discipline reminders
`env -u HCLOUD_TOKEN mvn install -DskipTests` always (HetznerCloudIT). Forge via `-Pwith-e2e ... integration-test -Dit.test=… -Dfailsafe.failIfNoSpecifiedTests=false`, never `verify`. aether/** = BSL-1.1; resource module Java headers BSL, pom `<licenses>` Apache (matches `resource-http`). Single-line commits, no trailers.
