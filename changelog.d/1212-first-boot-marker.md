### Fixed (2026-09-14 — #1212: #667's raised adoption bound made a genuinely new node pay the amnesiac's price)

- **#667 closed a real hole and introduced a fresh-node bootstrap regression, because nothing
  distinguished "new" from "wiped".** `#1171` raises the response bound to `clusterSize / 2 + 1` for a
  joiner that cannot vouch for its own history. A brand-new node cannot: `AetherNode.resolvePersistence`
  falls back to `RabiaPersistence.inMemory()` when no `BackupConfig` path is set, so `ownStateFloor` is
  `Phase.ZERO` and `selfCanVouchForItsOwnHistory()` is false. At `emberCluster(3, …)` with the third
  node held back, the second response can never arrive and the cluster cannot form.
- **The carve-out, and why it spends nothing.** If a joiner provably never voted, no commit quorum ever
  contained it, so any commit quorum intersecting `{responders} ∪ {self}` must intersect at a
  *responder* — and that responder holds the commit. Admitting a provably-new node on `clusterSize / 2`
  responses is therefore safe by the same intersection argument that licenses the rest of the rule.
  The reasoning was already stated in `responsesRequiredWithALiveResponder`'s docstring; what was
  missing was only the OBSERVATION, which `ParticipationMarker` now supplies.
- **Absence means WIPED, never NEW — the marker is tri-state on purpose.** `NEVER_PARTICIPATED` is a
  positive durable assertion written before the node participates in anything; `PARTICIPATED` and
  `UNKNOWN` both deny the relaxation. Newness is never inferred from an empty disk, because the
  dangerous node — one that voted and lost its state — presents exactly that way. A node with no
  marker wired keeps #1171's behaviour unchanged, which is what production gets until the deployment
  path supplies one (follow-up ticket).
- **The marker deliberately does NOT live in `RabiaPersistence`, and the code says so at its
  definition.** That is the obvious simplification and it is wrong: for a node falling back to
  `inMemory()` the marker would die with the process, so the node would re-assert newness on every
  restart — reopening #667 wider than it is today, caused by the fix meant to close it. It has its own
  fsynced medium (write-temp, `force(true)`, `ATOMIC_MOVE`).
- **Written before first participation, recorded before first vote.** `FileBackedParticipationMarker`
  resolves EAGERLY at construction — before the node starts, before its first `SyncRequest` — because a
  marker read after the node began participating cannot be trusted at the moment it is read.
  Participation is recorded inside `RabiaEngine.activate()`, the single choke point, which is strictly
  stronger than recording on the vote path since a node cannot vote before it activates. Fail-closed
  where it buys something: a node CLAIMING newness that cannot record refuses to activate; a node
  already `UNKNOWN` or `PARTICIPATED` does not, because both already deny the relaxation and refusing
  would trade a non-existent safety gain for real unavailability on a read-only filesystem.
- **LIMITATION — the guarantee is relative to what the operator deleted, and there is no scope-free
  version of it.** A wipe is defined by removing exactly the evidence that would distinguish a new node
  from a wiped one, so the boundary must be stated rather than inferred. Tagged in the same namespace
  as the claims, so one sweep returns both:
  - `[verified: FileBackedParticipationMarkerTest.deletingTheConsensusStateDirLeavesTheMarkerIntact]`
    deleting the consensus backup dir — the marker lives elsewhere and still reads PARTICIPATED. **HOLDS.**
  - `[verified: FileBackedParticipationMarkerTest.aDeletedMarkerWithNoCreationAssertionReadsParticipated]`
    deleting the whole node data dir — absence is conservative. **HOLDS.**
  - `[unverified: out of scope, accepted by owner ruling session 20]` deleting the node data dir **AND**
    re-running node creation so the creation assertion is supplied again — the node presents as new
    having voted. **FAILS.** A node cannot refuse an identity its own operator asserts. Peer attestation
    does not rescue it: a sole responder partitioned while the joiner voted attests "new" wrongly.
  - Net against rc4 today, which has #667's hole open for *every* node, wiped or not: the #1171 + #1212
    pair closes it for everything but the delete-and-recreate case. A strict improvement carrying a
    named limit.
- **Pinned as what goes RED, in two arms whose red sets are disjoint.**
  `RabiaSyncAdoptionFirstBootMarkerTest.AProvablyNewNodeJoinsOnTheColdBound` (2 tests) reddens if the
  marker is ignored or the carve-out reverted — the bound returns to 2 and the held-back cluster never
  forms. `…AWipedNodeIsStillHeldToTheAmnesiacBound` (3 tests) reddens if absence is ever read as
  newness. The two arms are the same scenario differing ONLY in the marker, so they are a controlled
  comparison rather than two observations, and neither's red set is a superset of the other's.
  `…ActivationIsTheChokePointForVoting` pins the premise the recording point rests on — a node that
  never activates never votes — and drives BOTH arms with the identical inputs that make its positive
  control vote. An earlier version of that control asserted only activation, found zero votes and
  correctly failed itself: a Rabia node votes only once it holds a QUORUM of proposals, so activation
  alone proves nothing.
- **Three-arm forge measurement, whole-module and CI-shaped**
  (`mvn verify -Pwith-e2e -pl aether/forge/forge-tests -Dfailsafe.excludedGroups=Heavy`; an isolated
  run of these tests is a known false pass, so none was used). `NodeLifecyclePeriodicArmingForgeTest`:
  **3/3 pass** at rc4 tip `2d641ad13` (pre-#667, `Tests run: 66, Failures: 0`), **3/3 FAIL** at #1171
  head `455e37dda` alone (`Tests run: 63, Failures: 1`, `cluster start in setUp() did not settle within
  TimeSpan(4M)`, ~246 s each, measured in an isolated clone), **3/3 pass** with #1212 (`Tests run: 66,
  Failures: 0`). Which node loses the race varies: runs 1–2 of the middle arm stranded `arm-2`, run 3
  stranded `arm-1`. The mechanism is visible in the WARN rather than only in the verdict — the broken
  arm reports `Adoption needs 2 responses … self does NOT count (no durable state)` with ~378–392 stuck
  rounds per run, the fixed arm reports `Adoption needs 1 responses … self counts (marker proves it
  never participated, #1212)` with 10–12 transient warm-up rounds and no stranded node.
- **`SliceVersioningTest` passed 3/3 in ALL THREE arms and #1212 is NOT credited with fixing it.** The
  previous round observed it failing 1 of 3 at a different head; three runs here did not reproduce that,
  so its earlier failure is load-dependent and outside what this change can be said to address.
