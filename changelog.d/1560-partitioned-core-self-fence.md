### Fixed (2026-09-27 — #1560: a partitioned core no longer self-fenced after #1390)
- **A black-holed core that was not the leader stayed registered and unfenced for 32 s or more.** Before #1390 it self-drained about 18 s after isolation. On heal it could reclaim stream ownership or serve a stale view. #1390 changed the counted set of the quorum-loss co-confirmation (`AetherNode.buildQuorumCoConfirmation`) to the installed voter set, which carries no health information.
  - Every peer the membership FSM had already demoted to DEPARTING or DEAD therefore came back as a "stuck" member.
  - The isolated node's SWIM still read those peers SUSPECTED. Lifeguard stretches suspicion when every local probe fails, and SUSPECTED counts as alive to the gate.
  - The fence was suppressed with an effective quorum of 5 of 5. The only later fence was the PASSIVE path, 15 s after its own edge.
- The counted set is now the FSM's counted core members narrowed to the installed voters, as it was before #1390, keeping #1390's voter scoping. [verified: `aether/node/src/test/java/org/pragmatica/aether/node/QuorumCoConfirmationSeamTest.java`, which reddens both tests when the counted set is reverted to the voter set]
- **A suppressed quorum-loss check is now re-evaluated instead of dropped.** Before this change, a count-path or presence-path check that the co-confirmation gate suppressed did not schedule another check. The fence was stranded on whatever the gate read at that single instant.
  - A suppressed check now re-arms every second.
  - It fires only while the node is still below threshold and the gate no longer suppresses.
  - Recovery cancels it.
  - The `T` debounce and the cold-boot deferral are unchanged.
  - The suppression WARN is logged once per episode; repeat checks log at DEBUG.
  [verified: `QuorumLossDetectorTest$CoConfirmationGate`, whose three `suppressed*` tests redden when the re-arm is removed]
- End to end, a single black-holed core of five self-fences in 19.0 s, both as the leader and as a non-leader. The black-hole starts after the 75 s cold-boot window. At the rc4 tip the non-leader took 34.0 s. [verified: `aether/ember/src/test/java/org/pragmatica/aether/ember/EmberPartitionedCoreSelfFenceTest.java`, bound 30 s. On bigboy it measured 19.0 s at c83ba0ed2, 34.0 s at the rc4 tip (red), and 19.0 s with the fix] [unverified: the heal-after-fence stream scenario of #1555 has not been re-run on this branch]
