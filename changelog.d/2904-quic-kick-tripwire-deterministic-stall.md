### Fixed (2026-11-08 — #2904: `QuicActivityKickStrandedLossTest` tripwire positive control failed under load)
- The test thread polled quiche's loss timer for the dropped packet (armed only ~1/8 RTT, tens of ms) and then submitted
  the stall induction to the event loop; under load netty's own timer fired first, retransmitted the packet, and the
  stall state was never reached (4 of 70 repetitions on a loaded host). The induction now runs on the event loop in the
  very task that first sees the loss timer armed (`QuicheStall.induceWhenLossTimerArmed`), so netty's timer cannot win.
  Test-only change.
