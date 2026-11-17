### Fixed (2026-11-17 — #3313: a backpressure episode whose held frames were all given up stayed open)
- **A (peer, lane) backpressure episode stayed open, with its "entered" WARN and no "cleared" line, when every frame
  held for it was given up or abandoned**: only a write ended an episode, so a lane with no later traffic kept it open
  indefinitely. The stream's writability event, the one signal for a stream with nothing left to write, was thrown away:
  the lane handlers' `onWritable` was a no-op in every production construction.
- An episode is now a level, ended by one closer (`settleEpisodes`): it ends every open episode of a stream whose lane
  has no frame held and whose stream takes writes or is gone (closed, peer departed). The closer runs on every frame
  exit (written, given up, abandoned, backlog retired), every direct write, the peer's channel close or departure, and
  the stream's writability event, now wired at all six handler construction sites. A held frame's episode is opened
  under the backlog lock the closer runs under, so a drain can no longer write the frame before its episode exists. A
  write refused on a closed stream opens no episode. A throw from the writability check leaves the episode open with
  one WARN, for the closer's next run. The "cleared" line is logged before the episode leaves the map. The episode key
  is the frame's own lane (a DHT frame held on the CONSENSUS stream ends the DHT episode).
  [mechanism: `QuicClusterNetwork#settleEpisodes` runs under the stream's `LaneBacklog` lock, the lock `countHeld`
  opens a held frame's episode under; `QuicLaneDataHandler.channelWritabilityChanged` calls it through the settler
  `PeerOpenedLaneRouter#dataHandler` hands every lane handler]
- Pinned by `QuicLaneBacklogTest` (given up, abandoned on a writable, unwritable and closed stream, DHT on its own lane,
  a drain racing the opening, a throwing writability check), `QuicConsensusBackpressureTest` (a refused write ended by
  the writability event alone; no episode on a closed stream) and `QuicWritabilityEventWiringTest` (every construction
  site delivers the event). `EmberConsensusLaneHoldTest` states what closes its episodes and, when its books do not
  balance, names the episodes still open; it does not pin this fix.
