### Fixed (2026-09-30 — #1755: Rabia resynced from a snapshot on any Decision one slot ahead)
- **A Decision one slot ahead of the applied prefix deposed the node** (`ConsensusPassive`, `QuorumLost`,
  every leader-bound component cycled), because #1390 answered any `comparison > 0` with `triggerResync()`.
  The Decision is now buffered and applied in order when the missing slot arrives; a resync happens only
  for a gap beyond `MAX_PHASE_AHEAD` (100) or when the slot is still missing after the new
  `ProtocolConfig.decisionGapTimeout` (default 2 s). Both paths log a WARN.
