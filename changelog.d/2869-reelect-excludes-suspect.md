### Fixed (2026-11-08 — #2869: re-election proposed the silent ex-leader again after its lease tripped)
- **After the leader-acting lease tripped, the majority re-elected the same silent node.** Candidate selection took
  the lowest NodeId of `expectedCluster ∩ transportTopology`, and a blackholed leader keeps its QUIC channels open, so
  it stayed in the topology and, as the lowest NodeId, was proposed and committed again on every re-election until a
  liveness sweep finally evicted it.
- `ReElecting` now carries a suspect: the leader whose lease tripped (the no-pre-vote path) or whose doubt the
  electorate confirmed (the pre-vote path). The proposal leaves the suspect out of the sorted candidate pool, and the
  staircase ranking leaves it out too, so the next-lowest node proposes at the base delay. When the suspect is the
  only candidate the unfiltered pool is used, so the proposal is still made. The suspect lives for one `ReElecting`
  instance only. [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/leader/fsm/ReElectionSuspectTest.java`
  — single-FSM unit tests, not a multi-node run]
- Unchanged: the `NodeGone` path (the departed leader is already out of the topology), the cold-boot self-fence
  suppression, and adoption. A consensus commit that names the suspect is still adopted, because rejecting a
  committed decision would split views. A node that does not suspect the ex-leader (for example, one entering
  `ReElecting` from `QuorumLost`) can still propose it. [mechanism: the suspect is local to the node that observed
  the silence; Rabia applies concurrent proposals in its log order, and the `LeaderValue` fence refuses a second write at the same `viewSequence` (`KVStore.java:714`), so one leader per view]
