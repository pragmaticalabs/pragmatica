# Session Handover — 2026-06-26 (clean foundation: SWIM + perf + core-Promise fixes; cloud 15/15 ready to launch)

**Branch `release-1.0.0-rc2` · HEAD `2b2e21f0a` · pushed.** Candidate tag `v1.0.0-rc2-candidate` moved to `2b2e21f0a` (CI image build triggered — has the fence + all three fixes below). Tree clean. Continues `session-handover-2026-06-25b.md` (fence P1 + Phase 2 detail there).

## ⚡ TL;DR — three foundational fixes landed since 25b; the cloud 15/15 re-run is the next action
1. **SWIM `OBSERVED`-birth fix merged** (#361, squash `38dde3c2b`) — fixes #336 node-add eviction; **also cleared #359's 45-min build-and-test hang** (its build-and-test passed clean post-fix).
2. **Per-key serial executor** lock-free fix (`625e3b515`) — the hot-key super-linear cliff is gone.
3. **Core `Promise` lost-wakeup fix** (`2b2e21f0a`) — a CONFIRMED orphaned-completion wedge in the async substrate; **potential upstream cause of the intermittent wedges** (cloud 02-chaos "unrecoverable", #359 hang, random CI/Forge flakes).
- Plus **#345 Phase 2 entity 2b-ii** (`5d6fe40a6`): per-partition entity fence proven.

## 🎯 NEXT ACTION — cloud 15/15 re-run on the clean foundation (start with FRESH context — it's a long monitored run)
The fence was already cloud-validated **13/15**; the 2 fails (03-scaling scale-up 6/7 provisioning shortfall; 02-chaos "unrecoverable" no-reform) were SWIM/membership-under-churn — exactly what the SWIM fix + (maybe) the Promise fix address. Re-run to get the real 15/15 verdict.
- **Wait** for the candidate image build (Release workflow, ~15 min) to finish + push `ghcr.io/.../aether-node:1.0.0-rc2-candidate`.
- **Launch:** `source /tmp/aether-test-pg.env && bash tools/pg-firewall.sh open && bash aether/tests/integration/run-tests.sh --env cloud --runtime container --skip-image-push --skip-teardown` (background; monitor suite tallies + the smoke gate). HCLOUD_TOKEN is set; test-PG VM `aether-test-pg`/88.198.147.80 is up.
- **Reaper discipline (critical):** reap cluster VMs by **explicit VM id** (they carry only `aether-node-id`, NOT `aether-cluster` — the bare reaper kills the PG). Preserve PG `144169086`. `pg-firewall close` after. `--skip-teardown` always.
- **If a suite wedges/hangs:** it may now be the SWIM or Promise fix's job done — or a remaining issue. The blueprint-FQN class is already gated (don't re-chase it).

## 🔧 The core Promise fix (`2b2e21f0a`) — what + why
`PromiseImpl.push(Completion)` (`core/.../Promise.java:3383`): its `if (result != null)` guard was checked at the TOP of the CAS loop, BEFORE the CAS. In the **empty-stack case**, a concurrent `resolve()` could CAS-result + drain + exit between push's result-check and a *successful* `STACK.compareAndSet(this, null, completion)` → the completion is orphaned, never runs → dependent chain WEDGES. Bite is on `onResult`/`fold`/`map` (not `await`, which re-checks `result`). **Fix:** an 8-line post-CAS guard — `if (result != null) processActions();` (drains via the atomic stack-claim → exactly-once whether resolver or pusher wins). **Proven:** deterministic in-`push` hook → OLD 2000/2000 orphaned on all three paths, NEW 0/2000; full `mvn -pl core test` = 822/0. (No committed stress test — perf testing deferred until clustering stable.)

## 🧱 #345 Phase 2 entity — where it stands (detail in 25b)
2a (per-key SPI module) + 2b-i (coarse-fence) + **2b-ii (per-partition fence, `5d6fe40a6`)** done: entity fences each write by its `(keyspace,partition)` epoch (new `aether-dht` `PartitionOwnerEpochGate`/`EntityPartitionArc`/`KvPartitionOwnerEpochSource`; `PartitionFencedDurableEntity`); proven a deposed partition-owner is rejected on a SAME-generation reshuffle. Additive — production factory still `InMemoryDurableEntity`. **Remaining 2b is #277-coupled** (AetherNode wiring): live ownership-record minting for entity keyspaces (`StreamPartitionOwnershipWriter`), owner-routed `update` invocation (net-new mechanism), quorum replication, factory wiring → then 2b-iii get-routing, 2c timers, P3 durability.

## 🤝 Open threads (not mine to close)
- **#359** (#241 slice-3) — the SWIM fix cleared its hang; aether-clone can refresh + re-run its CI, then it lands.
- **#356** (#277 PR1) — still awaiting aether-clone's per-injection-point rework.
- **#16** typed-path LINEARIZABLE (P2 enhancement, NOT a 2b blocker).
- **Perf testing** (durable-entity throughput, distributed entity path) — deferred until clustering is stable end-to-end (repo-owner directive).

## 📌 Discipline
`mvn install` fires HetznerCloudIT → always `env -u HCLOUD_TOKEN` + `-DskipTests`. Forge via `-Pwith-e2e ... integration-test -Dit.test=… -Dfailsafe.failIfNoSpecifiedTests=false`, never `verify`. CI red on `release-1.0.0-rc2` is the `/data` forge env-flake — **build-and-test is the real gate** (it's green). **Test-blueprint platform.version must be bumped each release** (rc2→rc3…) or they deploy stale (caused the cloud blocker). aether/** = BSL-1.1; core/durable-entity = Apache-2.0. Single-line commits, no trailers.
