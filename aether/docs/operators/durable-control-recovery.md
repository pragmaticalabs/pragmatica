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

The node status `voterReconfiguration` field reports the installed voter epoch, a requested change,
and added voters still catching up (#1526). A voter change agreed at slot R governs from R+1; the
new roster decides as soon as a majority of it is live, so replacing a dead core has no write pause.
`GENESIS_PENDING` means a node is still agreeing the genesis roster. Genesis needs every configured
core: epoch 0 starts only when the view of authenticated cores has exactly the configured core count and
every member reported that view in two consecutive rounds (#1526). A consequence to plan for: a cold
restart with a core permanently lost never forms on its own. The waiting node logs a WARN every ten
rounds naming the configured cores not yet visible and the members not yet reporting.

**Recovery action:** start or reconnect the missing cores; or lower the cluster core count to the cores
that exist; or set `cluster.genesis_voters` to the intended roster on every node. With more core
candidates visible than configured and no `cluster.genesis_voters`, the node refuses to choose and logs a
WARN with the candidate set: set `cluster.genesis_voters`, or stop the extra candidates. Do not relaunch a
node under its old NodeId to clear its view: under #1545 that identity is retired for good; replace it
with a node under a fresh identity. A configured core that comes back after genesis joins as an observer
and is voted in through a Rabia §4 add.

**Cold restart after a replacement.** If a core was replaced through a §4 swap and the retired old core is
still running and visible when the whole cluster cold-restarts, the view holds more candidates than the
configured count: every node waits in `GENESIS_PENDING` with the EXCEEDS WARN. Recovery action: stop the
retired core, or set `cluster.genesis_voters` to the intended roster.

*Test-harness note (Ember only):* Ember gives the initial nodes a fixed `cluster.genesis_voters`. A
harness cold restart after swapping out an initial node therefore waits for that fixed roster (the
swapped-out node is part of it). Production renders no `cluster.genesis_voters` for replacements.
