# Worker replica feed, W3 audit: what changes on a worker

Read at the W3 head. W3 lands the worker client DARK: nothing below happens in production until the cutover (W4). The table records what the cutover will change so it is reviewed before it ships. `[verified]` = read in the source at this head; `[unverified: ...]` = not exercised.

## 1. Key classes a worker sees for the first time

The projection scoped a worker's view (`WorkerMetadataIndex.scopesFor`); the replica holds everything outside `ReplicaFilter`. `ClusterConfigKey` and `StreamCursorCheckpointKey` stay excluded.

First-time visible `AetherKey` classes `[verified: ReplicaFilter is an exhaustive switch over the sealed AetherKey with no default, so a class cannot be added unclassified]`: `DeploymentOutcome`, `ArtifactVersions`, `ArtifactContent`, `Deployment`, `AbTest`, `ApiKeyAudit`, `ClusterIncarnation`, `DhtReplicationChange`, `DhtReplicationReport`, `StreamPartitionRecovery`, `CapacityLedger`, `CapacityReservation`, `NodeReplacement`, `UpgradeRun`, `UnconfirmedTermination`, `CapacityAdmission`, `CommunityPlacementAvailability`, `ProvisioningSlot`, `AutoHealState`. Also visible: `LeaderKey` / `LeaderValue` (stored beside `AetherKey`) and every other node's `NodeArtifact`, `NodeRoutes` and placement rows. Several are cloud-facing (`ProvisioningSlot`, `NodeReplacement`, `UnconfirmedTermination`, `Capacity*`, `UpgradeRun`, `AutoHealState`, `ApiKeyAudit`); the classification table was committed for review in W1.

## 2. Post-restore listeners

`RabiaNode.onStateRestored` registrations in main code. A worker never ran `RabiaEngine.applyRestoredState`, so none fired. W3 adds `RabiaEngine.completePassiveRestore()` (a passive client only), which the feed client calls after every snapshot install; from the cutover on every listener below fires on a worker once per install. All are idempotent by contract `[verified: RabiaEngine.onStateRestored javadoc]`.

| Listener | Registered in | What it does on a worker after the cutover |
|---|---|---|
| `refreshCommittedLeader` | `TopologyAssembly` | re-reads `LeaderKey` from the replica and refreshes the local leader view; the key is now present on a worker |
| `restoreRetirementIndex` | `TopologyAssembly` | rebuilds the community-retirement index from the replica (index existed, was never restored on a worker) |
| `nodeReplacements.restore` | `TopologyAssembly` | rebuilds the node-replacement index from the replica; the key class is newly visible |
| `upgradeRuns.restore` | `TopologyAssembly` | rebuilds the upgrade-run index from the replica; the key class is newly visible |
| DHT replication resolve + `dhtSettlement.reapply` | `DhtAssembly` | reads `ClusterConfigKey`, which stays EXCLUDED from the replica, so it resolves the built-in factors; the cluster's factors reach a worker through `ClusterDirectory.dhtReplication` (installed by `applyWorkerDhtReplication`). `[unverified: ordering of the two on a first install; the directory install is idempotent and re-applies]` |
| community directory restore + membership refresh | `WorkerHierarchyAssembly` | rebuilds the community directory from the replica and refreshes the governor nomination view |
| `workerMetadataChannel.onStateRestored` | `WorkerHierarchyAssembly` | the projection channel itself; deleted in W5 |

## 3. `ClusterIncarnation.current(kvStore)` on a worker

It returns 0 on a worker today (its store holds no committed state; `EmberReplicaTripwireTest` pins this) and the real value once the replica is installed. The mirror `ObservedIncarnation` is seeded from the store at construction and kept current by KV notifications (`ClusterIncarnationKey` put/remove, replayed after each snapshot install), so a worker's mirror moves from 0 to the cluster's value on the first install `[verified: routeEntries are registered for every node]`.

Consumers `[verified: grep of main code]`:
- `ObservedIncarnation` itself, read inline on the QUIC event loop and the SWIM UDP loop through `AetherNode.epochSources`.
- `generationEpoch` = (incarnation, Rabia term) for the stream-partition ownership writers (`StreamAssembly`, `EntityAssembly`), the consumer-assignment writer, and the bootstrap module (`ClusterBootstrapAssembly`). These run on cores; a worker holds no partitions and no leader role.
- `epochSources.incarnation()` passed to `MembershipAssembly` (membership FSM epoch source) and, as `::current`, to the stream cursor store (`StreamAssembly`) and the projection server (`AetherNode`).

`[unverified: with a non-zero incarnation on a worker, the QUIC attach and SWIM epoch comparisons that today see (0, term) on the worker side. The values on both sides now agree with the cores', which is the intended end state, but the comparison sites were not exercised on a LIVE worker beyond the Ember join test.]` The cutover (W4) must exercise a worker join, a re-genesis and a restore under LIVE before it removes the DARK path.

## 4. What W3 itself changes in production

Nothing observable. `ReplicaCutover.production()` is `DARK`; an assembled DARK worker holds no replica client, routes none of the feed's messages and keeps the engine latch as its only readiness setter (`ReplicaCutoverGateTest`). `RabiaEngine.setPassiveClientReady` and `completePassiveRestore` exist but no production wiring calls them. `HierarchyPeerPolicy.coreUplinks` became public so the client picks the same two uplinks as the health policy.
