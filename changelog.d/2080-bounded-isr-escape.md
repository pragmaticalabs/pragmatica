### Fixed (2026-10-09 — #2080: bounded promotion escape for an ISR candidate past an unreachable peer, and a divergent tail is preserved before it is cut)
- **A stream partition no longer waits forever for a member that never answers (#1563, #1579).** Both promotion gates — the owner
  activation gate (`OwnerActivation`) and the replica promotion contest (`PartitionBackfill`) — waited for every live member, and a member
  that keeps its transport handshaking while answering nothing never reaches DEAD (a wedged JVM). After the new setting
  `[streaming] promotion_escape_after` (default 120 s, refused if below the alarm bounds: two SWIM suspect windows and the contest's source
  wait, 20 s each at the defaults) of continuous unreachability, a candidate named in the partition's COMMITTED in-sync set
  (`isrVersion > 0`, read from the raw committed record, never from the routing view) goes ahead without the silent members and, once the
  activation completes (the contest: once the replica is promoted), raises one CRITICAL operator event,
  `stream-promotion-past-unreachable-peers`, naming the partition, the candidate, the members it skipped, the configured bound and the
  time elapsed. A candidate outside the set, a record with no committed set and a first owner with no record stay blocked as before; the
  alarm keeps its own, earlier bound. **The 120 s default is a guess** (the owner's): after a full-cluster cold restart the in-sync set
  is minted from placement, so a node whose disk is ahead and which merely boots slowly (JVM start plus WAL replay) must not be escaped
  past, or divergence becomes routine on staggered restarts. What would settle the value is the measured boot spread of a cold restart.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OwnerActivationTest.java, aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/PartitionBackfillEscapeTest.java, aether/node/src/test/java/org/pragmatica/aether/node/PromotionEscapeWiringTest.java, aether/aether-config/src/test/java/org/pragmatica/aether/config/ConfigLoaderTest.java]`
- **Honest guarantee.** Acknowledged writes (`confirmation_factor` >= 2) are on every member of the in-sync set, so an in-sync candidate holds
  all of them `[mechanism: the acknowledgement waits for every in-sync member]`. After a full-cluster cold restart a node whose disk is ahead and
  stays unreachable for the bound may hold acknowledged records the new lineage lacks: those records leave the live stream, but are retained in the
  recovery segment below and reported. Re-injecting them (follow-up tooling, not in this change) assigns new offsets, so consumers may see
  duplicates or reordering. With ephemeral storage there is nothing to retain and the escape changes nothing except availability.
- **Preserve before cut.** When a returning replica's divergent tail is cut back, the removed records (offset, timestamp, payload, owner-epoch key)
  are first written to a recovery segment on the node's own volume, `<wal file>.recovery-<first>-<last>-<millis>.seg` (fsynced, directory fsynced),
  and the WARNING `stream-divergent-tail-preserved` names the stream, partition, offset range and file. A segment that cannot be written refuses the
  cut (`RepairPreserveFailed`, retriable): the copy stays quarantined with its records. Recovery segments are never deleted automatically: WAL replay never reads one, the WAL's truncation and file cleanup and stream destruction never touch one, and a cut interrupted after its segment was durable is re-run without writing a second copy.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerDivergentTailTest.java]`
- Operator surfaces updated in place: `OwnerActivation` class doc (the "#1579 post-GA" paragraph is replaced), `PartitionBackfill` class doc,
  `aether/docs/reference/failure-almanac.md` and `aether/docs/reference/guarantees.md`. Supersedes #1579's plan for an automatic bound.
