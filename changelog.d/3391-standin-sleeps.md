### Fixed (2026-11-18 — #3391: three tests waited on a fixed sleep for the state they then asserted)
- **`KvBackupGracefulStopTest`, `OwnerActivationTest.admit_ownershipLeaves_redriveStops` and `QuicActivityKickTest` asserted after a fixed park or sleep.**
  The backup test parked 12 s as "the backup settles", so a debounced flush still pending could carry the stream written
  next and hide a missing stop flush; the owner-activation test parked 1.5 s and asserted exactly two rounds, which a
  re-drive landing late under load turned red; the kick test slept to half and double the kick window and asserted the
  kicks sent. Each now waits for the event itself with a deadline and a message (the remote backup document naming
  the leader's backed-up keys; the second activation round; a sent kick, the window closing by itself), and keeps its
  "nothing more happens" half as a bounded quiet period.
- Two package-private test accessors report whether the activity kick's window is open (`QuicActivityKick#isRunning`,
  `QuicPeerConnection#activityKickRunning`); production behaviour is unchanged.
- The kick's window is measured against wall time inside `QuicActivityKick`, so the cost test
  (`steadyWriteLoad_costsAboutTenKicksPerSecond`) still depends on host load; making it deterministic needs an injectable clock.
