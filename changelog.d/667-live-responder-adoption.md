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
  retry tick), never on a timer of its own, now thresholds on RESPONSES and uses liveness only to
  choose the source:
  - **any LIVE responder:** adopt once a majority of `{responders} ∪ {self}` has answered — but self
    is counted ONLY when it holds durable state. So `clusterSize/2` responses for a node that can
    vouch for its own history, and `clusterSize/2+1` for an amnesiac one, whose floor is `Phase.ZERO`
    and which therefore sits inside that majority contributing nothing. That is #667's hole exactly.
  - **the source within that quorum:** the most advanced LIVE state when LIVE responders are
    themselves a majority, otherwise the most advanced of ALL the responses. Filtering to LIVE inside
    a mere response quorum is unsafe — the responder that intersects the commit quorum may be the COLD
    one — so the filter is licensed only by a live majority. Self's floor stays as belt.
  - **no LIVE responder:** #660's cold rule, unchanged — `clusterSize/2` responses, self as the floor.
  - `UNKNOWN` counts as COLD in every arm: it counts toward the response quorum exactly as a COLD
    response does, and never toward the live majority.
  [verified: `RabiaSyncAdoptionResponseQuorumTest` (13 tests) — the mixed state that deadlocked is red
  at `db8bffedd` (n=3 one LIVE + one COLD, n=5 one LIVE + three COLD, every peer answering, 5s
  timeouts) and green here in 0.34s; a response minority still waits with nothing installed; the cold,
  all-UNKNOWN and mixed arms; n=1, n=2, n=3, n=5; a responder flipping LIVE→COLD.
  `RabiaSyncAdoptionLiveResponderTest` keeps the #667 residual (two responses of five must not adopt)
  and asserts UNKNOWN≡COLD as an equivalence rather than as one of its consequences]
- **The round-1 rule was a deadlock, and this supersedes it.** It thresholded on LIVE responders
  (`clusterSize/2+1` of them) and made a live minority wait. That count is one the blocked nodes
  cannot raise: the moment one node activates it answers LIVE, every remaining joiner sees a live
  responder, switches to the stricter bound, and they answer each other COLD — so a half-started
  cluster could never finish, and nothing times out of `Syncing` (`syncRounds` only WARNs). Cold start
  was never the defective arm. [mechanism: `RabiaEngine.adoptionThresholdMet` — a method that no
  longer exists at this head — returned `live >= clusterSize/2+1` whenever `live > 0`, making the cold
  rule unreachable; it and `candidateResponses` are replaced by a single `adoptionCandidates`, which
  also closes the round-1 review's finding that the decision was computed twice from independently
  re-read state]
- **The LIVE filter is narrow, and where it bites is now pinned rather than assumed.** Adoption fires
  on the arrival that first meets the requirement, so the collected set is normally exactly the
  requirement and "a live majority among them" reduces to "all of them are LIVE", where filtering
  removes nothing — measured at n=5 across all four arrival orders of {3 LIVE, 1 COLD}, the decision
  fired at 3 responses every time. The filter SELECTS only when the collected set outgrows the
  requirement, which happens when `clusterSize()` falls mid-round.
  [verified: `RabiaSyncAdoptionResponseQuorumTest.AShrinkingClusterExercisesTheLiveFilter` — the same
  three responses adopt the LIVE maximum when the cluster shrinks 5→3 and the COLD one at phase 500
  when it does not; same inputs, opposite outcomes]
- **Adoption now fires at the quorum and does not wait for a later, more advanced response.** That is
  safe rather than merely different: the adopted state is at or past every committed value (the
  maximum over a response quorum), and a node that activates behind the cluster's frontier catches up
  through normal replication — `commitDecision` → `advancePhase` advances `currentPhase` on every
  Decision, and a gap beyond `MAX_PHASE_AHEAD` buffers the Decision and calls `triggerResync`. No
  consumer of the adopted state requires it to be the maximum available.
  [mechanism: `RabiaEngine.advancePhase` / `isFarFuturePhase` / `triggerResync`]
- **An accepted, owner-ruled cost — read this before tightening the rule again.** At n=3 with one node
  down at most ONE responder exists, and one is never a majority of three. Closing #667's hole requires
  the responders to be a majority **alone**, because an amnesiac self contributes nothing. So in a
  degraded 3-node cluster **#667's safety property and joiner liveness are mathematically
  incompatible**, and the owner chose liveness: a self holding durable state counts toward the
  majority.
  What that spends is a property the system **does not in fact hold**. #660's cold rule — shipping
  today, untouched by every version of #667 — already activates a self whose durable snapshot is STALE
  relative to a commit it witnessed, because `persistence.save` has four call sites
  (`doPauseForQuorumLoss`, `doReconfigure`, `shutdownAndReset`, `applyRestoredState`) and **none is a
  commit path**, so a crashed node's snapshot lags what it voted for by an unbounded amount and the
  engine cannot tell a clean stop from a crash.
  [verified: probed against the rc4 tip `4af02125c` — n=5, self durable at phase 10 having witnessed a
  commit at 500, two minority responders at 10: **activates and installs the stale state**; positive
  control at 1 of 5 responses stays inactive, so the fixture does observe the threshold]
  The residual risk is narrower than "self is stale": a commit can be discarded only when self was in a
  commit quorum whose every OTHER member is currently unreachable AND self lost its own record of it. A
  genuinely new node was never in a prior quorum, so for it that branch is unreachable.
  [unverified: the cold arm's own exposure is not fixed here — it is pre-existing and out of #667's scope]
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
