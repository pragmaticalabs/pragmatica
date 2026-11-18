### Fixed (2026-11-18 — #3391: two cluster tests waited on a stand-in for the state they assert)
- **`EmberNodeReplacementTest` parked for 30 s as "the cluster settled" before it began a replacement.** The state the settled
  tests rely on is that every live node's membership has seen the victim healthy through SWIM (which lifts the cold-boot
  suppression that hides a crash). The test now waits for exactly that, with a deadline and a message naming it. The cold-boot cases still begin immediately, on purpose.
- **`DurableEntityForgeTest` read arc owners from one node after a convergence probe that any node could satisfy.** The
  ownership endpoint reports the committed records the asked node has applied, while the probe passes once a create is
  accepted anywhere. The setup now also waits until that node's view carries every entity arc, and fails naming the arcs seen.
- **Three other sites were read and left as they are**: the undeploy wait in `SliceInvocationTest`, the version-registry gate in
  `SliceVersionLifecycleTest` and the listing wait in `PerSliceDecisionSnapshotProbeTest` cannot lag what they assert, and each
  now says why. Test-only change; no product behaviour changed.
