### Fixed (2026-11-08 — #2930: swim tests raced their own recorders under CPU load)
- **Test-harness races in `SwimLinkLostLivePeerTest` and `SwimDeathPathCoConfirmationTest`, no product change.** The
  observation recorder ran before the ping counter, so the awaiting thread could see the death verdict while the counter
  still held its `-1` sentinel; the fake transport counted a Ping before delivering its Ack, so a test thread that saw
  the Ping could record a hint and then have the Ack refute the suspicion before its assertion; and three death-path
  tests stopped as soon as `onMemberFaulty` was called, which runs before the verdict observation is emitted. The counter
  listener now runs first, a Ping is counted after its Ack and hint, and those tests wait for the observation too.
