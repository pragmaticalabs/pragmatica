### Fixed (2026-10-02 — integration harness: S05's "2-vs-3 partition" was not one when the clock started)
- **12-network S05 applied the two minority partition firewalls one after the other (~20 s apart in cloud run 7) and started
  timing at once**; established QUIC flows outlive a provider firewall, so for a window the split was a half-cut node still holding
  links to a quorum, which is how a minority node won an election and deposed the healthy leader (product #1748, not masked here).
  Both partitions are now applied concurrently and confirmed applied; the clock starts only after ISOLATION is confirmed (every
  majority node's own membership shows both minority nodes not-Member and the leader has no CONNECTED link to either, bounded by
  the SWIM window); a split that never completes is an honest FAIL naming who still sees whom, and the heal still runs.
- **The majority-unreadable failure now prints the last read's HTTP status and body** instead of "the leader may be down": a
  forwarding error means the answering node could not reach the CURRENT leader.
- **Leader continuity.** The pre-partition leader is read immediately before the partition, and every majority read during the isolation
  wait AND the window must report it: a different leader, none, or not-quorate is the FAIL (the isolation wait can no longer be where a
  deposition heals unseen), an unreadable read stays unknown, and the PASS line prints the leaders actually observed.
  [verified: `aether/tests/integration/test/test-s05-isolation.sh` C1, I1-I7, L1-L4, O1-O2, M1-M2 and the updated test-partition-heal-on-failure.sh. No cloud run.]
