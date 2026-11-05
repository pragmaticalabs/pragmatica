### Fixed (2026-10-28 — #2460: consensus engine-lifecycle overhaul, L-2)
- Consensus: a far-future Propose no longer starts a sync round on an engine whose activation is gated and not yet authorized (A-04); a Paused engine still resyncs, as ruled `[verified: integrations/consensus/src/test/java/org/pragmatica/consensus/rabia/LifecycleTripwireTest.java]`.
