### Fixed (2026-11-17 — #3313: a backpressure episode whose held frames were all given up stayed open)
- **A (peer, lane) backpressure episode stayed open, with its "entered" WARN and no "cleared" line, when every frame
  held for it was given up or abandoned**: an episode ended only at a later write on its lane, at the peer's channel
  close or at its departure, so a lane with no later traffic on a live connection kept it open indefinitely. The
  stream's writability event, the one signal for a stream with nothing left to write, was thrown away: the lane
  handlers' `onWritable` was a no-op in every production construction.
- An episode of (peer, lane) is now open exactly while a frame of that (peer, lane) is held in any backlog of the peer,
  or the lane's latest counted write found a stream that is still active and still unwritable. One closer,
  `settle(peer)`, re-evaluates every open episode of the peer and ends each for which both are false. Six triggers run
  it: a held frame leaving a drain pass (written or given up), an abandoned frame, the lane stream's writability event
  (now wired at all six handler construction sites and at certificate rotation), the lane stream going inactive (a
  stream that dies while its connection lives now ends its episode, also when the lane was moved to a new stream), the
  peer's channel close or departure, and a refused write once it is counted. A successful direct write does not run it.
  Counting, the held count and the closer run under the peer's episode monitor (lock order: a backlog's lock, then the
  monitor), so a drain can no longer write a frame before its episode exists, and frames of the lane still held on an
  old stream keep the episode open. A write refused on a closed stream opens no episode. A throw while evaluating one
  episode leaves that episode open with one WARN; the peer's other episodes are still settled. The "cleared" line is
  logged before the episode leaves the map. The episode key is the frame's own lane (a DHT frame held on the CONSENSUS
  stream ends the DHT episode).
  [mechanism: `QuicClusterNetwork#settleUnderMonitor` runs under the peer's episode monitor, which `countHeld` and
  `countRefusedWrite` hold for every episode count; "a frame of the lane is held" is read from `LaneBacklog#heldOf`,
  a per-lane slot each backlog changes with its queue; `QuicLaneDataHandler.channelWritabilityChanged`
  and `channelInactive` call it through the settler `PeerOpenedLaneRouter#dataHandler` hands every lane handler]
- Pinned by `QuicLaneBacklogTest` (a last frame written or given up, abandoned on a writable, unwritable and closed
  stream, DHT on its own lane, a drain racing the opening, frames held on an old stream, a refused write whose stream
  turned writable before it was counted, a throwing writability check), `QuicConsensusBackpressureTest` (a refused
  write ended by the writability event alone; a stream that dies while its connection lives; peer close and
  departure; no episode on a closed stream) and `QuicWritabilityEventWiringTest` (every construction site, and the
  certificate-rotation rebuild, delivers the event). Each of the six triggers, and the "no frame of the lane held"
  condition, was removed in turn: every removal turns at least one of these tests red. At this head the four classes
  ran 10 times in a row, 73 test cases each, all green. `EmberConsensusLaneHoldTest` states what closes its episodes
  and, when its books do not balance, names the episodes still open; it ran 5 of 5 green at this head and 0 of 5 green
  with the closer's body emptied, so it now also pins the closer.
