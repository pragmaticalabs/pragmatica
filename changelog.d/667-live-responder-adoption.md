### Fixed (2026-09-14 — #667: sync adoption could not tell a live responder from a cold one, so a single node rejoining a live cluster could adopt a live minority's state)
- **#660 relaxed adoption to `clusterSize/2` peer responses with self as a floor.** Sound for a
  full-cluster cold start (nothing durable anywhere), it was weaker than the old bound for one node
  restarting with in-memory persistence into a still-live cluster: its floor is phase 0, the first two
  responders may be live peers that never witnessed the latest commit, and the floor cannot refuse
  them — a commit held only by the two peers that had not answered yet was silently discarded.
  `doHandleSyncRequest` already served live engines and stopped/syncing engines differently, but the
  receiver could not see which it got. [mechanism: `RabiaEngine.adoptionThresholdMet` counted
  responses, `adoptCollectedState` took the maximum over all of them, gated only by `ownStateFloor`]
- `SyncResponse` now carries `ResponderState` — `LIVE` (Active/Observing/Paused engine: the current
  state machine and phase) or `COLD` (Stopped/Syncing: the persisted snapshot or empty), with the #964
  `UNKNOWN` sentinel last. Adoption, re-evaluated on every arriving response (and on the existing
  retry tick), never on a timer of its own, is now three cases with a stated guarantee each:
  - **LIVE responders ≥ ⌊n/2⌋+1:** adopt the most advanced LIVE state. A live majority intersects every
    majority that could have committed anything, so its maximum is at or past every commit; a COLD
    snapshot cannot outrank it. Self's floor stays as belt.
  - **no LIVE responder:** #660's cold rule, unchanged — `clusterSize/2` responses, self as the floor.
  - **some LIVE responders, fewer than a majority:** keep collecting. A node rejoining a cluster that
    has no live majority waits by design — that cluster has no quorum either — and the stuck-Syncing
    WARN now says "L live of M needed for a live majority". `UNKNOWN` counts as COLD: an unreadable
    flag never loosens the bound.
  [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/rabia/RabiaSyncAdoptionLiveResponderTest.java`
  — (i) n=5, two LIVE responders behind: must not adopt (red before, adopted); two LIVE + one COLD and
  two LIVE + one UNKNOWN: still waits; (ii) the third LIVE completes the majority and the LIVE maximum
  is adopted, a COLD response "ahead" is not; (iii) all-COLD bare majority still activates (#660);
  (iv) `ownStateFloor`'s LIVE arm, isolated with a never-persisting store; producer marking pinned in
  `RabiaPausedSyncResponseTest` (Active → LIVE, Stopped → COLD)]
- **Wire format.** `SyncResponse` gained a record component and `ResponderState` a tag (112, in the
  one-byte window the hot-prefix gate demands for `org.pragmatica.consensus.*`). The wire-assignment
  gates pin tags and ordinals, not record shape (#1147). Both pins live in
  `SyncResponseResponderStateCodecTest`, and only ONE of them pins the SHAPE:
  `ordinalBeyondThisNode_decodesToUnknown_withTheRestOfTheResponseIntact` hand-frames the bytes and so
  reddens on any component change, while `responderState_roundTrips_throughTheGeneratedCodec` writes
  and reads with the same regenerated codec and stays green through an added fourth component
  (measured). The class doc now says which is which, because the earlier claim credited the round-trip.
  [mechanism: hand-framed bytes vs a codec agreeing with itself for any shape] The
  baseline gained exactly the two new lines. rc4 promises no cross-rc wire compatibility (#434/#666);
  a pre-#667 peer's `SyncResponse` does not decode, the same posture as #766/#805.
- Even cluster sizes: `⌊n/2⌋+1` LIVE peers is 3 of 3 at n=4 and unreachable at n=2 (one peer), where
  the cold rule alone applies whenever that peer answers COLD; n≥3 odd is the supported topology.
  [design intent — unverified]
- Not verified in a multi-node run: a COLD responder is an engine in Stopped/Syncing with its
  transport up, and Ember offers no seam to park a peer there (a stopped Ember node has no transport
  and answers nothing). [unverified: Ember n=5 restart-into-live-cluster]
