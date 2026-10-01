### Fixed (2026-10-01 — #1797: leader election double-proposed after a "submitted" settle; LeaderTerm lagged the committed viewSequence)
- **The election's in-flight proposal guard was cleared when the proposal settled as SUBMITTED, not when it COMMITTED.**
  `LeaderElectionState.handleProposalSettled` called `clearProposalInFlight()` on a success settle, so an
  `ElectionTick` or a topology-change reschedule landing between the submit and the commit passed `tryStartProposal()`
  and proposed again at `nextViewSequence()` — the committed `LeaderKey` advanced twice for one election round
  (observed on the rc4 tip: seq 3 then seq 4, 2 ms apart, from `LeaderTermFailoverTest`). The success settle now
  leaves the guard held; it is released by leaving `Electing` / `ReElecting` on the commit (`onExit`), or by the
  proposal timeout settling as a failure, which also reschedules the retry — so a proposal that is lost and never
  commits is re-proposed after `proposalTimeout`, not never.
- **`LeaderTerm` now re-adopts on any committed `LeaderKey` naming this node** (`onLeaderKeyCommitted`, driven by the
  `ValuePut<LeaderKey>` notification), not only on the leadership-gain edge. Same max-merge as `onLeaderGained`: it
  never lowers the term and ignores a record naming another node. Defence in depth: the settle path no longer
  re-proposes, but a same-leader re-commit emits no gain edge, so the term could otherwise sit below the committed
  `viewSequence`.
- **A tick racing the commit no longer proposes from the state it just left (same double-proposal class, found in
  review).** An `ElectionTick` landing after the commit is observed but before the FSM transitions ran its KV-pull
  adoption (moving the FSM to `Led`, whose old `onExit` cleared the guard) and then, in the same handler, called
  `tryStartProposal` and proposed on the exited record: a second proposal, its timeout stored on a dead record, the
  guard left set, so the next `ReElecting` could not propose until another node committed. `submitProposalWith` now
  re-checks that the owner is still the current state after taking the guard (releasing it if not), and
  `Electing` / `ReElecting` `onEntry` clear a leaked guard. The check is a single read, not a lock: `Fsm.dispatch`
  runs on the caller thread, so a transition on another thread between the check and the send is narrowed, not
  excluded. [verified: `LeaderProposalInFlightTest#electing_tickWhoseAdoptionLeavesTheState_proposesNothingFromTheLeftState`
  — the verifier's probe, red at base and at the PR's first head, green after; dropping the re-check reddens it.]
- **The `ValuePut<LeaderKey>` route registration is pinned.** `LeaderKeyCommitWiringBootTest` boots a real node and
  commits a self-naming `LeaderKey`; the node's `leaderTerm` component reaches that sequence only through the
  registered route (a lone node never elects itself, so no gain edge can supply it).
- [verified: `LeaderProposalInFlightTest` — 5 red of 5 at the unmodified base (second tick and topology reschedule
  after a submit settle re-propose; the guard is released at submit), 5 green after. Mutations on the fixed code:
  restoring the early `clearProposalInFlight()` on the success settle reddens 5 of 5; turning the timeout settle into
  a "submitted" one (no release) reddens exactly `reElecting_proposalThatNeverCommits_isReProposedAfterTheTimeout`
  (1 of 5); `LeaderTerm.onLeaderKeyCommitted` returning the held term reddens 3 of 14 `LeaderTermTest`; dropping the
  `AetherNode.onLeaderKeyCommit` call reddens `onLeaderKeyCommit_notification_raisesTheTermOnlyForTheLeaderKey`
  (1 of 14).] [verified on the live path: `LeaderTermFailoverTest` 5 of 5 green after the fix, on a quiet box. The
  base failure was a single recorded red, so 5 green is NOT a measured rate change.]
- Behaviour change worth knowing: a stale `LeaderCommitted` skipped by the entry-baseline fence no longer lets the
  next tick re-propose at once; the retry now waits for `proposalTimeout` (10 s default).
