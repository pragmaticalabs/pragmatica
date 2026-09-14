### Changed (2026-09-14 — #517, closed as already refused: the refusal is now pinned at node level and the dead placeholders are invariant checks)
- A node whose `self` is absent from `topology.coreNodes()` was never booting silently: `TopologyObserver.topologyObserver`
  refuses it (`TopologyError.SelfNodeNotInCoreNodes`) inside `RabiaNode.rabiaNode`, before `AetherNode.assembleNode`
  runs. Nothing at node level pinned that ordering, and `findSelfAddress` / `resolveHostname` still carried
  `.orElse(new NodeAddress("", 0))` / `.orElse("localhost")` — dead branches that would have advertised a
  placeholder silently if assembly were ever reordered ahead of the observer factory.
- `AetherNodeSelfAbsentFromTopologyBootTest` boots a real node with `self ∉ coreNodes` and asserts the refusal names
  the self id and is the observer factory's; both placeholders are now `Option.expect(...)` naming the invariant and
  the guard that enforces it, and `resolveHostname` derives from `findSelfAddress`
  [verified: the test is green at the tip; disabling the guard in `TopologyObserver` makes it fail (the boot
  proceeds into the observer and dies on a missing self entry), so the tripwire is load-bearing].
