### Fixed (2026-11-08 — #2930: SwimLinkLostLivePeerTest raced its own recorders under CPU load)
- **Two test-harness races in `SwimLinkLostLivePeerTest`, no product change.** The observation recorder ran before the
  ping counter, so the awaiting thread could see the death verdict while the counter still held its `-1` sentinel; and
  the fake transport counted a Ping before delivering its Ack, so a test thread that saw the Ping could record a hint
  and then have the Ack refute the suspicion before its assertion. The counter listener now runs first and a Ping is
  counted after its Ack and hint.
