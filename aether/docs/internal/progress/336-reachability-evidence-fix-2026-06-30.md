# #336 — cluster-sync missed-pong as reachability evidence (complements #361)

> On `feat/336-reachability-evidence`, branched off `release-1.0.0-rc2` (which already has #361). Two
> commits (`e26c2f809` SWIM refutation hardening, `9156887a8` option-1 cluster-sync reachability feed).
> Hand-off to aether-main for review/merge. 2026-06-30.

## Relationship to #361 (already merged)
`#361` ("SWIM `OBSERVED` birth state") fixes #336 for a **freshly-added node at join** — it's born
OBSERVED (probe-eligible, not death-armed) so it isn't evicted before its first probe-ack. This change
fixes the **complementary, disjoint case #361 leaves open: an *established* HEALTHY peer transiently
SUSPECTED under churn/jitter.** Reconciliation confirmed all changes here are **additive** to release
(release has no equivalent of any of them) and graft cleanly.

## What changed (both needed, synergistic — do not split)
1. **Option-1 — cluster-sync feeds reachability evidence, not a destructive disconnect** (`9156887a8`).
   On release, `ClusterSyncContext.emitPingTimeoutIfExceeded` STILL did `network.disconnect` +
   `evictionHints.put` on `pingTimeoutThreshold` missed pongs while a peer was merely SWIM-SUSPECTED —
   false-evicting healthy peers under jitter (and the eviction-hint broadcast is an S20 self-drain-cascade
   trigger). It now calls an injected `reportUnreachable` → `recordTransportHint(PeerUnreachable(PING_TIMEOUT))`,
   driving SWIM's existing SUSPECT→3s-floored-FAULTY→DepartedObserved→DEAD pipeline (the same path the QUIC
   `onPeerLeft` listener already uses) — **refutable** when pongs resume. New `PING_TIMEOUT` `QuicTransportCause`;
   the eviction-hint broadcast goes naturally inert (sole writer removed; `evictionHints`/`processEvictionHints`
   left for a follow-up sweep). Preserves `drainTargets`/`drainNodes` and the QUIC-disconnect→`onLivenessGone` tap.
2. **SWIM refutation hardening** (`e26c2f809`). (a) **At-risk proactive self-refutation**: `refreshSelfAlive`
   advances own incarnation when inbound reachability goes stale, so a healthy peer out-ranks a `Suspect(self)`
   it never received (release's refutation is reactive-only — needs the suspect gossip to arrive, which the
   failing link drops). (b) **Co-confirmation kill-gate**: a NORMAL-phase first-hand FAULTY for an ever-HEALTHY
   peer is held (`coConfirmedFaulty`) until ≥2 distinct accusers OR a transport-unreachable hint — so one
   prober's transient SUSPECT can't terminally depart an established peer. **Option-1's hint is what trips this
   gate's transport-veto arm** — the two are mutually dependent. (c) `log(N)` suspect-window scaling.

## Validation
- **Units:** 170 tests green on this branch (`integrations/swim` + `aether/aether-metrics`) incl. the SWIM
  Wave-6/Tombstone/DeathPathCoConfirmation suites and `ClusterSyncFsmTest`; clean compile incl. `AetherNode`.
- **Real infra (on the predecessor branch):** `run-tests.sh --env remote --suites 02` (S20 excluded there) —
  all 5 kill/membership tests pass; a killed node is removed from membership in ~6s and the cluster
  re-elects/fails-over. The dropped-broadcast convergence risk did NOT materialize (SWIM gossip propagates the
  verdict). **Recommended pre-merge:** re-run the FULL 02-chaos on this branch — release has the S20
  `restart_all_nodes` fix, so the suite's baseline-restore should no longer cascade.

## Notes for aether-main
- **In-JVM `CommunityFormationProbeTest` is NOT a valid gate** — it stays red purely from single-JVM CPU
  starvation (8 nodes on 8 cores → SWIM probe-acks late → genuine FAULTY; LHM sawtooth = late, not absent).
  Validate on real infra or relax in-JVM SWIM timeouts for density.
- **Follow-up:** delete the now-inert `evictionHints`/`currentEvictionHints`/`processEvictionHints` plumbing.
