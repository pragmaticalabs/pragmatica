### Fixed (2026-11-17 — #3313: a backpressure episode whose held frames were all given up stayed open)
- **A (peer, lane) backpressure episode stayed open, with its "entered" WARN and no "cleared" line, when every frame
  held for it was given up or abandoned**: only a write ended an episode, so a lane with no later traffic kept it open
  indefinitely. Nothing else reports writability for a stream with nothing left to write (the lane handlers'
  `onWritable` is a no-op in every production construction).
- The event that removes a lane's last held frame now ends the episode if the stream is writable at that moment. A
  backlog whose frames were given up while the stream was unwritable keeps its drain passing (frames sent meanwhile
  are held behind it) and ends those lanes' episodes at the first pass that finds the stream writable; an abandoned
  backlog ends them if the stream is writable at the abandon, otherwise the lane's next write or the peer's departure
  does. The episode key is the frame's own lane (a DHT frame held on the CONSENSUS stream ends the DHT episode).
  [mechanism: `LaneBacklog` keeps the lanes of discarded frames and does not retire while it owes one on an
  unwritable stream; `drainPass` and `abandonBacklog` pass them to `endBackpressureEpisode`]
- Pinned by `QuicLaneBacklogTest` (given up, abandoned, DHT on its own lane) and `LaneBacklogTest` (owed keys).
  `EmberConsensusLaneHoldTest` no longer relies on later traffic to balance entered and cleared episodes; its comment
  now says so.
