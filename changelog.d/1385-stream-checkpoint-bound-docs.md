### Fixed (2026-10-11 — #1385: docs overclaimed the replay bound of a declarative stream consumer)
- **The replay bound after an ungraceful move holds only while the partition keeps receiving events.** The checkpoint trigger
  (1000 events or the 1 s / 500 ms interval since the last commit) is evaluated only on a delivery, so no timer starts a commit on a quiet
  partition: one whose events all land inside its first interval after attach never commits a checkpoint, and an ungraceful move replays it
  from offset 0. At-least-once is unaffected; the bound is not. The trailing commit that would earn it is still not implemented.
- The statement is now made, without the unqualified bound, in `guarantees.md` (§4 row 19 and the `stream.consume` entry), the design-versus-shipped
  note under the `streaming-spec.md` §10.2 checkpoint diagram, and the comments on `StreamConsumerManager`, `ConsumerRuntimeState.shouldCheckpoint`
  and `DurableGroupIdentity.consumerConfig`. The other places that state the bound were already corrected by #2111. Comments and documentation
  only; no behaviour change. [mechanism: `shouldCheckpoint` is reached only from `ConsumerRuntimeState.advanceCursor` -> `checkpointIfNeeded`]
