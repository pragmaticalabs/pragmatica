### Added (2026-11-14 — #3194: a consensus-lane hold had no end-to-end test)
- **`EmberConsensusLaneHoldTest`** holds one live peer's inbound CONSENSUS stream unread (three real nodes, real QUIC and Rabia)
  until the senders' lanes to it are really unwritable, commits writes meanwhile, then releases. It pins: one backpressure
  episode per held lane spanning the hold, with its `cleared` summary; held frames older than the 5 s budget counted as
  `RETRY_GAVE_UP` and no backlog or retry left after the release; and that a write committed during the hold reaches the held
  peer within 90 s of the release. Test only, no product change.
