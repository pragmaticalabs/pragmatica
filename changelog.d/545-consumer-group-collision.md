### Fixed (2026-09-04 — #545: two artifacts sharing one consumer group now refuse loudly instead of one winning silently)

- **A cross-artifact consumer-group collision is now a named, loud failure at declaration time,
  instead of `declarationFor`'s `findFirst()` picking an arbitrary winner.** `SubscriptionKey`/
  `ConsumerKey` are `(streamName, partition, consumerGroup)` — deliberately artifact-free, because
  that is the correct identity for the physical consumer that serializes reads for a group. Two
  DIFFERENT artifacts declaring the same `(streamName, consumerGroup)` collide at that identity;
  before this fix, whichever declaration `findFirst()` happened to return silently became the sole
  consumer and the other was dropped with no signal. `declarationFor` now answers the single
  registered declaration for a key or a named ambiguity refusal — never an arbitrary first match —
  and a colliding key's declarations are excluded from `reconcile()`'s desired set on both sides, so
  NEITHER artifact consumes until the collision is resolved (rename the group, or remove one of the
  conflicting declarations).
  [mechanism: `StreamConsumerManager.declarationFor`/`desiredFor`/`declineColliding`; pinned
  in-process by `StreamConsumerManagerTest$GroupCollisions#reconcile_subscribesNeitherSide_whenTwoArtifactsShareOneGroup`
  and `#declarationFor_refusesToPickAWinner_whenACollisionAppearsBetweenTheTwoDeclarationReads` — the
  latter drives the ambiguity-refusal branch with a mock registry answering two reads differently,
  since a real KV-backed registry cannot reproduce that race synchronously — component-level against
  the real reconcile/declaration path, not a live multi-node run]

- **The collision is surfaced through the existing per-artifact diagnostic channel, naming both
  artifacts, the stream, and the group — on `GET /api/v1/streams/declarative-consumers` and
  `aether streams consumers`.** No new error type or HTTP status was introduced; `diagnostic` on
  BOTH colliding entries carries the collision message. `GET /api/v1/blueprints/status/{id}` carries
  no hint of this: a slice can be fully `DEPLOYED` while its declarative consumer sits idle on a
  collision, since the collision is a stream-registration fact, not a slice-instance fact.
  [mechanism: `StreamConsumerManager.collisionDiagnosis`/`collisionMessage` feed the existing
  `Diagnosis.message()` → `ConsumerStatus.diagnostic()` path; pinned by
  `StreamConsumerManagerTest$GroupCollisions#statuses_nameBothArtifactsStreamAndGroup_whenTheyCollide`
  — component-level, not a live multi-node run]

- **Two VERSIONS of the same artifact sharing a group is not a collision — the intended blue-green
  upgrade collapse is unaffected.** Collision detection compares `ArtifactBase` (groupId + artifactId,
  version-stripped), not the full `Artifact`, so an upgrade continuing the same consumer group across
  versions is not flagged and consumption is not interrupted.
  [mechanism: `StreamConsumerManager.distinctBases`; pinned by
  `StreamConsumerManagerTest$GroupCollisions#reconcile_doesNotFlagCollision_whenTwoVersionsOfOneArtifactShareTheGroup`,
  which also asserts the partitions stay actively subscribed, not merely undiagnosed — component-level,
  not a live multi-node run]

- **The collision retraction itself was over-broad: it could detach and silently re-attach a healthy,
  non-colliding subscription on a DIFFERENT stream that merely reused the same consumer group string.**
  `unsubscribeAllFor` matched active subscriptions by `consumerGroup` alone; two unrelated streams
  sharing a group name is legal (collision detection is already keyed by `(streamName,
  consumerGroup)`). The final subscribed-partition set was never wrong — `attach()`'s
  `putIfAbsent` check silently re-subscribes anything still desired within the same `reconcile()`
  pass — so the user-visible cost was a spurious detach-then-resubscribe round trip on the unrelated
  stream: a needless graceful cursor flush and fresh resume, not a stuck-unsubscribed or lost-data
  state.
  [mechanism: `StreamConsumerManager.unsubscribeAllFor` now filters by `streamName` and
  `consumerGroup` together; pinned by
  `StreamConsumerManagerTest$GroupCollisions#reconcile_leavesAnUnrelatedStreamAlone_whenItsGroupNameCollidesOnlyOnAnotherStream`,
  which asserts on `subscribeCalls` rather than final membership, since membership alone cannot
  distinguish the fix from the bug — component-level, not a live multi-node run]
