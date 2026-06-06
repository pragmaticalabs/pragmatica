# PR #239 — Issue 7 (consensus re-election wedge): root-cause finding + decisive experiment

**Branch:** `feature/stream-namespaces-rebuild` @ `021181277` · **Base:** rc1 `37e4e257b`
**Status:** RC-BLOCKER, mechanism NOT yet confirmed — needs one Docker/CI experiment (below).
**Owner split:** #239 code fixes — stream lane (this doc's author); kill-leader repro + the experiment — validation lane.

## Symptom (reproduced by validation lane, `02-chaos/Kill_leader_and_re-elect`)

4 healthy quorum-capable nodes; kill the leader → survivors submit leader proposals with epoch
climbing 57→103+ (~9s/round) that **never commit** → permanent no-leader, quorum 0, all
leader-bound routes 503, auto-heal blocked (`current=0`). **Zero** `Network is unreachable`.
**Passes on rc1 → #239 regression.**

## What static analysis CONCLUSIVELY rules out (two exhaustive passes, file:line proof)

1. **#239 does not touch the consensus engine.**
   `git diff --name-only 37e4e257b 021181277 -- integrations/consensus integrations/cluster integrations/messaging`
   is **empty**. `RabiaEngine`, `TopologyObserver`, `KVStore`, `MessageRouter`, `ConsensusBridge`,
   `LeaderManager` are byte-for-byte identical to rc1.
2. **No throwing/blocking/re-entrant #239 code on the Rabia apply/election thread:**
   - cluster-events emit is **fire-and-forget** — `Promise<?> ignored = publisher.publish(event)`
     (`ClusterEventAggregator.java:248`); `publishLocal` = ring append + fire-and-forget
     `replicateEvent` (`DefaultReplicationManager.java:57-98`, `transport.send` non-blocking).
     `awaitReplication` exists but emit never calls it.
   - `ReplicaSetController` membership/quorum handlers offload to a **dedicated** `replica-set-controller`
     executor (`ReplicaSetController.java:142-185`); `reconcileNow` does **in-memory** registry writes
     only, **no `cluster.apply`**, and early-returns while `passive` (`:188`).
   - `KvBackedStreamRegistry` refcount + `SystemStreamBootstrap` registrations call `cluster.apply`
     but **fire-and-forget** (no `.await()`; `register` returns `Result.success` at
     `KvBackedStreamRegistry.java:111`) and run on the slice-ACTIVE/bootstrap path.
   - Codec tag is **content-derived, registration-order-independent** (`SliceCodec.java:141`); a
     collision **throws at construction → fail-fast at boot** (`:248-250`), which contradicts the
     symptom (nodes boot and run election). KV serializer changes are TOML-snapshot-only.
   - `KVStore.process` router fan-out **swallows** handler exceptions (`MessageRouter.java:135-144`).

**Therefore the wedge is a runtime LOAD / TIMING / RESOURCE interaction, not a code line on the
apply path.** Timeline check confirms it is *live*: the RC-blocker note (`cd82d011b`, 07:54) postdates
HEAD `021181277` (07:39), and every commit after the tested state is a pure naming refactor
(ResourceAddress/pub-sub) touching no consensus code.

## Candidate mechanisms (cannot be distinguished statically)

1. **Single-Rabia-executor queue contention** from #239's added `cluster.apply` traffic
   (system-stream bootstrap + refcount). *Weak* — on a leader-kill specifically, #239 adds little
   *sustained* consensus load.
2. **Scheduling jitter** — #239 spawns extra executors (replica-set-controller, replication batcher,
   catch-up) perturbing the *randomized* Rabia protocol's timing. Plausible; shouldn't block
   convergence for 40+ rounds.
3. **Harness staleness (Issues 5/6)** — chaos harness may poll now-leader-bound endpoints (Issue 8)
   and/or the "passes on rc1" baseline differs.

## DECISIVE EXPERIMENT (validation lane — needs Docker/CI)

Run ONE A/B against the same `02-chaos/Kill_leader_and_re-elect` repro:

1. **A/B stub:** disable `SystemStreamBootstrap` / `system:cluster-events` provisioning (don't register
   the system streams; keep the rest of #239). Re-run the kill-leader repro.
   - **Passes now** → mechanism is #239's consensus-adjacent provisioning load (candidate 1). Fix:
     move all system-stream `cluster.apply` off the consensus hot path / make it idempotent &
     one-shot, and gate stream consensus interaction while no-quorum.
   - **Still wedges** → not the stream provisioning. Proceed to (2)/(3).
2. **Thread-dump** the `rabia-*` thread (and the engine's `LinkedBlockingQueue` depth) **during** the
   hang; count queued `cluster.apply` tasks vs phase/vote/decision tasks. Confirms/kills candidate 1.
3. **Baseline sanity:** confirm `02-chaos` is deterministic on rc1 `cd82d011b` (rule out candidate 3 /
   a flaky baseline) before treating it as a hard #239 regression.

Report back: which of A/B passes, and the queued-task split. That names the mechanism in one run.

## Issue 8 (compounding, CONFIRMED — fix is independent of Issue 7)

`/api/events` + `/api/alerts` + `/api/traces` now read through owner/replica placement
(`PartitionedStreamAccess` read-forward `:434-487`), which is PASSIVE-gated stale during leader loss
→ 503 with no leader. rc1 served these from a node-local in-heap buffer (deleted in #239,
`ClusterEventAggregator.java:71-80`). **Fix (planned, stream lane):** serve these observability reads
node-locally / from any caught-up replica — never leader-bound — preserving #210's replication while
restoring rc1's read availability.

## Task list (harness/tracker)

39 root-cause (this doc) · 40 fix Issue 7 [blocked on experiment] · 41 fix Issue 8 [independent] ·
42 validate kill-leader [Docker] · 43 migrate stale harness (Issues 5/6) · 44 run 04-streaming
[Docker] · 45 C6 JBCT sweep · 46 rebase onto rc1 · 47 follow-up unify publisher ownership under HRW.
