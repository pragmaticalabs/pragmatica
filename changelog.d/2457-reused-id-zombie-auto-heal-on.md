### Fixed (2026-11-06 — #2457: with auto-heal on, a never-joined instance reusing a confirmed node id kept billing in silence)
- **An instance booted under a node id whose reap was already confirmed was neither deleted nor announced while
  auto-heal was enabled.** A second reap of a confirmed node made no provider call, so the new instance was never seen.
- A reap of a confirmed node now looks at the provider again (a read-only listing) when its trigger itself reports an
  instance under the id: a join-grace reap (the id booted and never joined) or the orphan sweep (two reads listed it).
  An instance outside the set the confirmation deleted is reaped like any other. Other repeat reaps of a confirmed
  node (NodeRemoved, the drain-grace backstop of the same drain) still make no provider call.
  [verified: aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerAutoHealDisabledReapTest.java]
