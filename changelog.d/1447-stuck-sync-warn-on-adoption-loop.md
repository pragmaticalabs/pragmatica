### Fixed (2026-09-29 — #1447: the stuck-sync WARN could never fire on the adoption loop)
- **A node looping in adoption never emitted the periodic "still SYNCING" WARN.** `adoptCollectedState`
  reset the round counter on every entry, and `doSynchronize` returned on the adoption branch before
  counting, so a node whose adoption kept failing (a snapshot it cannot restore, an activation that is
  refused) reported nothing periodic; at cluster size 1 the WARN was unreachable outright, and the retry
  tick was not even rescheduled after the first failed adoption.
- Rounds are now counted by OUTCOME: a round that ends without the node active counts, whether or not
  adoption ran, and the counter resets only when the node activates or a new sync episode starts. An
  adoption that did not activate retries on the normal sync cadence and, every sixth round, logs a WARN
  that names the refusal cause. A healthy adoption logs no stall WARN.
  [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/rabia/RabiaOwnRestoreFailureTest.java`
  — in-JVM engine with a failing `restoreSnapshot`, cluster sizes 1 and 3, not a multi-node run]
- Operator action is unchanged by this fix: the WARN only makes the existing fail-closed state (#1468)
  visible; what clears it is still the open part of #1468.
