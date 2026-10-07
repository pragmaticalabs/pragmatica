### Fixed (2026-10-07 — #2004: a stream owner block alarm's recovery could be delivered before its raise)
- **A clear that landed between a block being recorded and its raise published the resolution first.** The aggregator drops a
  recovery with no open warning, so the CRITICAL (`stream-owner-lineage-refused`, the unreachable-members wait, the divergent
  peer block) stayed open until the next episode.
- Recording a block and raising it, and removing a block and resolving it, now run under one monitor in the owner-activation
  gate, so the delivered order is `[raise, resolve]` or nothing, never `[resolve, raise]`. The monitor is a leaf lock: while it
  is held the gate runs only map operations and the alarm's log line plus bounded hand-off.
