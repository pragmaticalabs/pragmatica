### Fixed (2026-10-02 — #1830: phantom seed peers lingered 1-4 min in a booting core's DHT ring and membership)
- **A seed that departed before this core booted stayed in its DHT ring and membership count for
  minutes.** SWIM's cold-boot rule turned the seed's FAULTY into UNKNOWN and then dropped the verdict.
  When the residency sweep ran after the 75 s window had closed, it tombstoned the seed. The real
  FAULTY → Departed came only after the 100 s tombstone and a fresh probe cycle: 3 min 40 s in cloud
  run 7. If the node never left COLD_BOOT, the seed stayed for good.
  [mechanism: `SwimProtocol.emitFaultyOrUnknown` booting branch; `tombstoneIfWasHealthy` at sweep]
- The suppressed FAULTY is now deferred instead of dropped. When the cold-boot gate closes, a peer that
  is still FAULTY and has never been HEALTHY gets its FAULTY → Departed. That takes it into DEPARTING,
  and the ring is pruned. A seed that came up meanwhile is not departed, and one with a live QUIC link
  is held by the transport veto, as before.
  [verified: `integrations/swim/src/test/java/org/pragmatica/swim/SwimProtocolColdBootReplayTest.java`]
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/PhantomSeedMembershipTest.java`]
- A QUIC `Hello identity mismatch` on a dial now refutes the dialed identity: its address answered with
  another NodeId, so the seed is not there. A refuted never-HEALTHY peer is not shielded by the cold-boot
  rule, so it departs at its first FAULTY edge even while the cluster is still booting. Only the dialed
  identity is refuted, never the node that answered, and a live link under the identity still vetoes.
  [verified: `integrations/swim/src/test/java/org/pragmatica/swim/SwimProtocolIdentityRefutationTest.java`]
  [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicMisdirectedDialTest.java`]
- [unverified: no multi-node or cloud run. The bound is shown in-JVM with time-scaled SWIM timings, not
  measured on a booting replacement.]
