### Fixed (2026-11-06 — #2724: a LINK_LOST hint floored a live peer to a death verdict before its next probe)
- **A transport death hint could kill a live peer without a single probe of it.** A `LINK_LOST` or `PEER_UNRESPONSIVE`
  hint floors the SWIM suspicion window to 3 s and corroborates the #336 kill-gate, but a peer's next round-robin probe
  is up to (N-1) x period away (4 s with five nodes) and the tick expires suspicions before it probes. In the Ember run,
  a voter the leader had removed terminalised the live leader 1 ms before the probe that would have refuted it, and the
  two nodes held each other DEAD permanently.
- On a hint that leaves the peer SUSPECT under a current death hint, SWIM now probes the peer at once (skipped while a
  probe of it is already pending). A live peer's verified Ack ends the suspicion, with no FAULTY and no
  `DepartedObserved`. Probe-ack stays the only ALIVE authority; the hint only schedules the probe.
  [verified: integrations/swim/src/test/java/org/pragmatica/swim/SwimLinkLostLivePeerTest.java, in-JVM protocol harness, not multi-node]
- Not guaranteed: if every datagram of that probe cycle (the Ping, its Ack and the indirect relays) is lost before the
  floor, the verdict is the same as before this fix. [mechanism: the probe adds evidence, the floored expiry is unchanged]
- A genuinely dead peer under a current hint still gets its verdict at the first tick at or after hint + 3 s, as before.
  A failed hint-time probe cycle adds one Lifeguard LHM increment, which the next verified Ack removes.
  [verified: integrations/swim/src/test/java/org/pragmatica/swim/SwimLinkLostLivePeerTest.java]
