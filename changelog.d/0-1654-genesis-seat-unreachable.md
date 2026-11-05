### Fixed (2026-11-05 — #1654: genesis dead-seat signal, engine half; part of #2460 L-10)
- Consensus: genesis that forms with an agreed seat which is no longer a connected peer now logs a WARN naming the seat and emits `GenesisSeatUnreachable`; formation is not blocked `[verified: integrations/consensus/src/test/java/org/pragmatica/consensus/rabia/GenesisSeatUnreachableTest.java]`.
