### Fixed (2026-10-02 — #1748: a follower that lost only its own link to a healthy leader deposed it)
- **A follower's private view could depose a leader the rest of the cluster still reached.** A follower that lost its
  own SWIM/QUIC view of the committed leader went `Led -> NodeGone(leader) -> ReElecting` at once, proposed, and any
  majority that could still reach it committed the challenger (rc4 cloud run, 12-network S05: a minority core won 3 of 5
  through links the provider firewall had not cut yet and the old leader adopted the swap; earlier, ~420 leader flaps in
  35 minutes). The follower now asks the electorate first (**leader pre-vote**): it stays in `Led(leader)`, sends
  `LeaderPreVoteRequest` to the other voters, and goes to `ReElecting` only when a majority of the electorate (itself
  included) affirmatively doubts the leader — i.e. follows another leader, has none, has lost it from its transport
  view, or has seen its leader ping silent for the full lease threshold (a freshly elected leader still warming up is not
  doubted). The same gate sits behind the existing leader-lease edge (`LeaderSilent`). [mechanism: `LeaderPreVote`,
  `LeaderElectionState.Led#loseLeader`]
- **Silence is not doubt.** Only an affirmative "I do not see it" counts, so a follower cut off from most of the cluster
  can never assemble the majority it needs from voters it cannot hear, and a node with no view of the leadership
  (booting, quorum lost, passive) does not answer. A round that does not reach a majority refrains and retries
  (`proposalRetryDelay` x [1, 1.5)) for as long as the follower still suspects the leader; the follower keeps following the
  committed leader meanwhile, so there is no leaderless interval and no `LeaderChange` churn on a false alarm.
- **Liveness cost, stated.** A genuinely dead or majority-partitioned leader is doubted by every survivor of the
  majority through its own failure detector, so replacement is delayed by at most the detection skew between this follower
  and the last survivor needed for the majority, plus one retry delay (500-750 ms) and, when answers are lost, one round
  timeout (1 s). That is bounded by the 4-check lease the follower already runs, not added to it. A leader that a
  majority still sees is not replaced, by design; a follower that cannot reach a majority stays in `Led(leader)` until
  quorum loss moves it. The pre-vote is off when the consensus wiring does not enable it (local-election mode keeps the
  immediate edge), and is skipped for a leader that is this node or was voted out of the electorate (#1815).
- **Scope, honestly.** This guards the well-behaved proposer, which is the observed failure. Voters do NOT refuse to commit a
  challenger: Rabia decides on proposal vectors, and a per-node veto there would make the decision depend on local state,
  so a proposer that skipped the pre-vote would still win a quorum. It adds two wire messages
  (`NetworkMessage.LeaderPreVoteRequest` / `LeaderPreVoteResponse`, tags 1721 / 1722, CONTROL lane, bound to the
  authenticated sender); both are exempt from the one-byte hot window like the refusal messages (a handful per
  suspicion, none in steady state). A node that does not know them cannot decode them, which is acceptable pre-GA.
  There is no Management-API surface for the pre-vote yet; its rounds and verdicts are INFO log lines
  (`Leader pre-vote round N: ...`).
- Preserved: #1797 / #1800 in-flight guard and `LeaderTerm` re-adopt (consensus 784 non-QUIC tests, aether/node 2190,
  aether-deployment 1458, all green), #1807 `AwaitingKvSync` follows the known leader (`AwaitingKvSyncTest`,
  `JoinerElectionTest` green), #1815 eligibility pin (a voted-out leader is lost immediately, pinned).
- [verified: `LeaderPreVoteTest` — 15 tests over five real `LeaderManager`s on an in-memory network that cuts single
  links. Pins: a follower losing only its own view of the leader does not depose it and never proposes; the S05
  shape (2-vs-3, staggered cut, the challenger still reaching two vouching voters) keeps the leader on the majority side and
  then, fully isolated, still cannot challenge; a silent leader ping seen by one follower only does not depose it.
  Controls: a dead leader is replaced inside 3 s; staggered detection proceeds once a majority doubts, without a new event;
  the majority side of a partition re-elects; the crossed-pointer wedge still recovers through the lease. Mutations on
  the fixed code, each reddening named tests of those 15 in `integrations/consensus` only: restoring the immediate
  `ReElecting` on leader loss (the unfixed behaviour) 4, majority of one 4, silence-as-doubt 2, voters that always doubt 6,
  no retry 2, asking about a voted-out leader 1, stance ignoring the leader's identity 1.]
