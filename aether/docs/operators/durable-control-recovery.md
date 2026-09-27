# Control state recovery

Cores and workers run consensus in memory (owner ruling, session 28). There is no local vote
journal and no `cluster.consensus_path`: a node needs no control-storage path to boot, and the
shipped image and templates configure none. A configured `[backup]` path keeps a git-backed
snapshot of the consensus state, as before #1390.

**Terminal removal is the identity model.** A node whose process dies does not return under the
same NodeId. Every process draws a random boot token at start and carries it on its SWIM
announcements and process evidence. A peer that already knows a NodeId treats a different token
for it as a different process: the known process is marked dead and the new one is refused.
`[mechanism: SwimProtocol retires an identity on a boot-token conflict]` — pinned on a single
protocol instance by `SwimBootTokenTest`, not yet exercised on a multi-node cluster.

A process that stays alive through a network partition keeps its token and heals as before
(higher SWIM incarnation, same token).

**Recovery action:** replace a dead node with a new node under a fresh NodeId. Restarting a
container or VM under the old NodeId produces a process the running cluster refuses. A
whole-cluster restart is the exception, because no running peer remembers the old tokens.

The node status `voterReconfiguration` field distinguishes stalled handoff stages and state-transfer
failures. After a committed barrier, restoring connectivity to a successor quorum is required;
there is no timeout rollback to the previous electorate.
