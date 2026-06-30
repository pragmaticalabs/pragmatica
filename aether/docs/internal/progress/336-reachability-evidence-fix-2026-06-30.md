# #336 — cluster-sync missed-pong as reachability evidence (option 1)

> Design-stream implementation + real-infra validation, 2026-06-30. Hand-off to aether-main
> for review/merge (this stream never self-merges). Developed on `feat/241-worker-governor`
> (the #336 in-JVM repro + the validation both live there).

## TL;DR
- **Root cause of #336 (node-add eviction):** the cluster-sync app-level liveness path (RC1 "S01")
  did a **destructive `network.disconnect` + eviction-hint broadcast** on `pingTimeoutThreshold`
  missed pongs while a peer was merely SWIM-**SUSPECTED** (not FAULTY). Under any ack jitter —
  in-JVM CPU starvation *or* real network congestion — that false-evicts healthy peers.
- **The fix (option 1):** replace the destructive disconnect with **feeding a transport-unreachable
  HINT into SWIM** (`recordTransportHint(PeerUnreachable(PING_TIMEOUT))`). SWIM already drives the
  full `SUSPECT → 3s-floored-FAULTY → DepartedObserved → synchronous-DEAD → departurePermanent`
  pipeline (the same path the QUIC `onPeerLeft` listener feeds), and the hint is **refuted** when
  pongs resume — so a transient flap no longer evicts a healthy peer. One failure detector with
  multiple evidence sources, not two racing eviction paths.
- **Status:** unit-green; **validated on real infra** (remote docker-compose, separate containers).

## The in-JVM forge probe is an INVALID gate for this (important)
`CommunityFormationProbeTest` (the #336 repro) stays RED **purely from single-JVM CPU starvation**:
8 full `AetherNode`s on an 8-core host can't service SWIM probe-acks within `probeTimeout` (500ms),
so nodes genuinely reach FAULTY. Evidence: the LHM **sawtooth** (increments == decrements, score
capped at 1 → acks arrive *late, not absent*); no-ack count drifts run-to-run (15→25→38) with nothing
but rebuilds (load jitter). No eviction-path fix can change this — it's a harness fidelity limit, not
a product bug. **Validate #336 on real infra (separate containers) or relax in-JVM SWIM timeouts for
density — not on the 8-on-8 in-JVM probe.** See [[project-336-node-add-eviction-injvm-repro]].

## Real-infra validation (remote, suite 02-chaos minus pre-existing-broken S20)
5/6 scripts pass — **all 5 kill/membership tests** (`joining-window-kill`, `kill-leader`,
`kill-multiple`, `kill-node`, `kill-under-load`): a killed node is detected, **removed from
membership** (~6s), and the cluster re-elects/fails-over. The 1 failure (`stream-replica-failover`)
is **not option-1-attributable** — its membership assertions passed (killed owner removed, owner
re-resolved, replicas converged); it failed on a stream-replication precondition + the pre-existing
`restart_all_nodes` baseline-restore. **The dropped-broadcast convergence risk did NOT materialize**:
SWIM gossip propagates the FAULTY verdict cluster-wide; killed nodes are removed everywhere.

## What changed (2 commits)
1. **SWIM refutation hardening** (`SwimProtocol`/`SwimConfig`): at-risk self-refutation (advance own
   incarnation when inbound reachability goes stale, so a healthy peer out-ranks an unheard
   `Suspect(self)`); co-confirmation kill-gate (`MIN_FAULTY_CONFIRMERS=2` / transport-veto before
   terminally departing an ever-healthy peer); `log(N)` suspect-window scaling. **`transportVetoConfirms`
   is load-bearing for option 1** — the unreachable hint trips it to unlock FAULTY for an ever-healthy peer.
2. **Cluster-sync reachability-evidence** (`ClusterSyncContext`/`ClusterSyncCollector`/`AetherNode`):
   `emitPingTimeoutIfExceeded` now calls an injected `reportUnreachable` → `recordTransportHint`
   instead of `network.disconnect` + `evictionHints.put`. New `PING_TIMEOUT` `QuicTransportCause`.
   The eviction-hint broadcast is the sole writer, so it goes naturally inert (no wire-format churn;
   `evictionHints`/`processEvictionHints` left for a follow-up sweep). Preserves `drainTargets`/
   `drainNodes` and the QUIC-disconnect→`onLivenessGone` tap.

## Caveats for aether-main
- **Superseded interim work** (reverted, not committed): a join-grace guard and a FAULTY-gate on the
  cluster-sync path — both subsumed by option 1 (SWIM `everSeenHealthy` + the hint pipeline).
- **S20 self-drain recovery still fails** on `feat/241-worker-governor` — the `restart_all_nodes`
  `force-recreate` fix landed on `release-1.0.0-rc2` *after* this slice branch forked. Pre-existing,
  unrelated to #336. Rebase to pick it up, or run #336 suites with S20 excluded. See
  [[project-s20-root-cause-harness-quorum]].
- **Follow-up:** delete the now-inert `evictionHints`/`currentEvictionHints`/`processEvictionHints`
  plumbing once confirmed nothing else depends on the broadcast.
