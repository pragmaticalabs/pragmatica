### Fixed (2026-10-04 — #1895: `RabiaConsensusIntegrationTest` waited on the clock instead of on the condition, and four of its tests could pass having checked nothing)
- **Test-only change; no consensus property was violated and no production code is touched.** The harness
  delivered messages in fixed 50 ms hops while each engine runs on a single-thread executor. When two or
  more executors slipped about one hop behind, the round-2 votes stayed pending, no Decision formed, and
  `locked_value_propagates_through_phases` failed at its phase-0 assertion (one of 33 failed CI attempts
  in 200 runs, run 37186310616). With executor-only stalls of 30–150 ms injected, the original failed
  59 of 100 interleaved runs, all at the same line and message as CI; the fixed test failed 0 of 100.
- **Delivery now waits on a condition.** `deliverUntilQuiescent()` and `deliverUntil(condition)` settle every
  engine twice per hop through the existing `settleForTesting()` barrier, then deliver, up to a 100-hop cap.
  `activate*` polls `isActive()` instead of sleeping.
- **`deliverAllPendingMessages` no longer loses messages.** It snapshotted the pending list, delivered it,
  then called `clear()`, discarding anything broadcast in between. It now drains with `poll()`.
- **Four vacuous tests now assert what their names claim**, each reddened by a named production mutation
  and green on the original under the same mutation: `all_nodes_agree_on_same_proposal` (no round-1 vote
  broadcast at all), `multiple_consecutive_decisions_maintain_agreement` (decisions for phase > 0 never
  broadcast), `state_machine_receives_commands_on_v1_decision` (committed batch never applied), and the
  phase-1 vote test (phase > 0 round-1 vote removed).
- **`locked_value_propagates_through_phases` is renamed `phase1_initial_vote_is_v1_when_phase1_proposals_agree`.**
  No lock carries across phases: `PhaseData.evaluateInitialVote` reads only its own phase's proposals.
- [unverified: that CI load starved the executors the way the injected stalls do; inferred from the identical failure signature]
- Two `Thread.sleep` calls remain in this file, both inside bounded polling loops. [unverified: the 159 fixed sleeps across 24 other consensus test files; not audited here, tracked by #1895]
