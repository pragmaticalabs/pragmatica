### Fixed (2026-11-08 — #2904: `QuicActivityKickStrandedLossTest` positive control failed under load)
- The scenario reaches the stall only if quiche's loss timer for the dropped packet (armed ~1/8 RTT, tens of ms, after the
  ACK of the next packet) is still pending when the induction runs. On a loaded host the ACK is processed late, or other
  traffic is in flight, and the stall is never reached (4 of 140 tripwire repetitions failed at base). The induction now
  runs on the event loop in the task that first sees the timer armed (`QuicheStall.induceWhenLossTimerArmed`), and
  reaching the stall is a retried precondition: the scenario is rebuilt on a fresh connection (up to 6 attempts) and
  the tripwire is evaluated only against a run that read the stall state. Test-only change.
