### Fixed (2026-09-27 — #1550: stream partition ownership never left a killed owner)
- **After a stream partition's owner died, every survivor kept resolving the dead node as owner and no
  replica was promoted, although the survivors held the data.** Since #1390, `TopologyObserver.coreNodes()`
  returns the installed voter configuration, which is consensus identity and deliberately does not change
  with health. The stream `ReplicaSetController` placed partitions over that set, so a killed voter stayed
  the HRW winner for good: the membership FSM marked it DEAD and `NodeRemoved` triggered a reconcile, but
  the reconcile read a member set that could not shrink. Stream placement now has one source,
  `AetherNode.livePlacementMembers`: the installed voters narrowed to the membership FSM's counted core
  members (MEMBER + SUSPECT). The replica-set controller and the backfill orchestrator read it directly;
  the ownership writer, entity-ownership reconciler, consumer-group ownership, cluster-events owner gate and
  placement role read it through the controller. What consensus reads is unchanged.
- `LivePlacementMembersSeamTest` pins what the source computes, and `LivePlacementMembersWiringTest` fails if
  any placement consumer reads the voter set directly again. Both run per PR. The Heavy
  `StreamOwnerFailoverTest`, which the regression escaped, passes again.
- **Intended behaviour:** a core replacement that joins under a fresh identity enters stream placement once
  the membership add installs it as a voter, not before.
