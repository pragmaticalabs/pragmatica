### Fixed (2026-09-27 — #1550: stream partition ownership never left a killed owner)
- **After a stream partition's owner died, every survivor kept resolving the dead node as owner and no
  replica was promoted, although the survivors held the data.** Since #1390, `TopologyObserver.coreNodes()`
  returns the installed voter configuration, which is consensus identity and deliberately does not change
  with health. The stream `ReplicaSetController` placed partitions over that set, so a killed voter stayed
  the HRW winner for good: the membership FSM marked it DEAD and `NodeRemoved` triggered a reconcile, but
  the reconcile read a member set that could not shrink. Stream placement (and the entity-ownership and
  backfill views derived from it) now reads the installed voters narrowed to the membership FSM's counted
  core members (MEMBER + SUSPECT). What consensus reads is unchanged.
- `LivePlacementMembersSeamTest` pins the placement set in the per-PR suite. The Heavy
  `StreamOwnerFailoverTest`, which the regression escaped, passes again.
- A fresh-identity core replacement enters stream placement only once it is an installed voter, as on
  the rc4 line before this fix.
