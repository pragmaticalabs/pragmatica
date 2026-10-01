### Fixed (2026-10-01 — #1803: a replacement core dialed only its mint-time peers, and a fresh joiner could depose a live leader)
- **A joiner followed its own wall clock, not the leader it had already seen (b).** A replacement observes the committed
  `LeaderKey` while it is a passive observer of the formed electorate, then is admitted as a voter and enters
  `AwaitingKvSync`. That state's KV pull skips a leader equal to the one the FSM already knows
  (`adoptLeaderFromKvIfPresent`), so the leader the joiner had observed never ended the wait; after the 3 s grace it fell
  through to `Electing`, and a minted `aether-…` id sorts ahead of the configured `hetzner-…` ids, so it won rank 0 and
  deposed the healthy leader (`13-edge-cases/Reactivate_nodes` on JVM, `1e95ede3b`). `AwaitingKvSync` now (1) follows the
  leader the node already knows on entry, (2) re-reads the KV record at the grace timeout and follows a leader that is
  there even if its push never arrived, (3) while a node that JOINED a running cluster still has KV state catching up
  (`RabiaEngine.joinedFormedElectorate()` and `isPendingCatchUp()`), waits another grace window, bounded at ten windows,
  and (4) only a cluster that shows no leader and no sync in flight — or a sync that never settles — reaches `Electing`.
  A cold-boot or restarting node is not a joiner and keeps the plain grace. [mechanism: the incident log shows
  `LeaderCommitted` observed at viewSequence 1 while the node held the leader in `Passive`; the pull's equality skip is
  `LeaderElectionState.adoptLeaderFromKvIfPresent`.]
- **A replacement now reaches the voters it was never told about (a).** A replacement is minted with the peers that
  existed at mint time. After it joined the formed electorate, its SWIM view still lacked a later leader: the join ack
  (#1785) had been merged through a membership scope that did not yet include that voter, and gossip about it is a
  one-shot. The node therefore never dialed the leader and the leader never dialed it, so the leader's counted
  membership stayed below target. The core's hierarchy refresh now compares the installed electorate with its SWIM view
  and, for voters it does not know, re-sends ANNOUNCE to its seeds and every ALIVE member (`SwimProtocol.requestMembershipView`);
  each answers with its view (the #1785 reply), merged through the scope that now covers the electorate, and the learned
  voter is dialed through the ordinary SWIM-discovery path. The re-ask is paced by `MembershipResyncPolicy`: at most once
  per 2 s, at most 20 times for one set of missing voters, restarted when that set changes — a dead voter that is still in the
  electorate costs a bounded burst, not a permanent drip.
- **The bootstrap-initiator rule uses the committed electorate, not only the mint-time list.** A staged (observer) core
  initiates toward its transfer peers regardless of the single-dialer id order; that set was the mint-time list alone
  (`configuredVoters`). It is now the mint-time cores UNION the installed electorate (`AetherNode.transferPeers`), so an
  observer whose id sorts above the leader's still dials it instead of waiting for a leader that does not know it.
  Composition with the neighbouring fixes: #1758 (intended-peer Hello) is unchanged, the dialed address comes from the SWIM
  view and the acceptor still refuses a misdirected Hello; #1762 (receipt-silent incumbent) is unchanged, no new
  direction of dial is created beyond the existing bootstrap-initiator rule; #1785 (join ack carries the view) is reused,
  not duplicated.
- Test seam: `EmberCluster.addCoreNode(id, mintTimePeers)` configures a node with exactly the peers that existed when it
  was minted (every other harness path hands a new node the full current list, which hides both defects).
- [verified: `ReplacementStalePeerListTest` (Heavy, 2 tests: a replacement minted without the leader, id below / above
  the leader's; 5 cores, 1 stopped, auto-heal blocked, replacement listed 3 live voters). Red at the unmodified base in
  both ("leader stale-peers-1 must count the replacement and the survivors": the leader counts only the 4 survivors for
  the whole 3-minute budget); green 4 of 4 runs of the final test on the fix (about 38 s per test before the voter-admission wait, about 50 s after).
  Mutations on the fix: removing the `swim.requestMembershipView(...)` call at `AetherNode` reddens both; reverting
  the initiator rule to the mint-time list reddens only the above-the-leader test (the observer then waits for the
  60 s higher-id grace; the test's budget is 45 s); reverting the `AwaitingKvSync` hunks (entry adoption, timeout KV
  re-read, sync-pending wait) reddens both — the replacement DEPOSES the live leader (below: leader becomes
  `stale-peers-0-low`) or the committed `LeaderKey` sequence moves 1 to 2 (above) — i.e. the incident's (b), reproduced at
  cluster level.]
- [verified: `JoinerElectionTest` — 5 of 7 red against base-equivalent `AwaitingKvSync` behaviour (entry adoption, KV re-read
  at the timeout, observed-leader timeout, sync-pending wait), the 2 controls (a leaderless joiner still elects; a sync that
  never settles still elects at ten windows) green both ways; each of four single-hunk mutations reddens exactly its own test.]
  [verified: `SwimJoinSyncTest#requestMembershipView_afterTheScopeGrew_recoversTheMemberTheJoinAckDropped`,
  `CoreSwimHealthDetectorMembershipViewTest` (3), `MembershipResyncPolicyTest` (6), `TransferPeersTest` (2) — each reddens
  under its own mutation.]
- [unverified: the `RabiaNode` line that installs the sync-pending signal
  (`leaderManager.installKvSyncProgress(() -> consensus.joinedFormedElectorate() && consensus.isPendingCatchUp())`) is glue
  no automated test reaches: `JoinerElectionTest` drives the same install through `LeaderManager` directly, and the Forge
  scenario does not make a joiner's sync lag. Removing the line is not detected.]
- [unverified: the incident's one-fast / two-slow boot timing is not modelled; the scenario models the stale list, not the
  boot-time race.]
