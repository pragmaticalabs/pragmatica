### Fixed (2026-10-02 — #1777 track 2: a new DHT replica answered "absent" before it held the data)
- **A node that had just become a replica of a partition answered reads as if it were authoritative.** This happened
  through a join, a removal, or a boot with an empty store. Its empty answer counted as an "absent" vote, so a read
  across a ring change could resolve a false absent while the data sat on the old holders (#1770 F1). The DHT now has a
  catch-up gate.
  - Each such partition starts **catching up**. Replies carry the replica's `Readiness`: `SERVING`, `CATCHING_UP`, or
    `UNKNOWN`, the codec sentinel, which is consumed as a refusal. An absent answer from a non-serving replica refuses
    its slot instead of voting.
  - A read that cannot reach an authoritative quorum fails with the transient `DHTError.NotCaughtUp`, never with
    "absent". `exists` is gated the same way.
  - Anti-entropy fills a catching-up partition from its co-replicas **and its surviving previous holders**. At boot the
    previous holders are bounded by the next RF ring successors. Completion is proven by reading back the pulled
    entries, and a refused pull is explicit and counted.
  - When no live source is serving (genesis, a whole-cluster cold restart, every holder gone), the partition completes
    on the union of what the sources hold. That is logged at WARN, because its absent answers are then best-effort.
  - A 1 s catch-up tick retries pending partitions. Dead sources are skipped.
  - A round pulls from every source whose digest differs.
  - A timed-out round decides on the answers it has, but only when a serving ANCHOR answered: a current co-replica or
    an exactly recorded previous holder. A boot-walk node answering SERVING is not enough, because it may never have
    held the partition. A decided round keeps its pulls for one more round timeout.
  - A partition stuck behind silent sources is warned and counted by the gauge
    `aether.dht.catchup.stuck.partitions`.
  - At boot the previous holders are walked on the live ring.
  - A partition lost while pending leaves the set.
  [verified: `integrations/dht/src/test/java/org/pragmatica/dht/DHTCatchUpGateTest.java`,
  `aether/node/src/test/java/org/pragmatica/aether/node/AetherNodeDhtCatchUpBootTest.java`]
- **The #1770 `absentGrace` stop-gap is removed.** That covers `ReadOptions`, `AbsentGrace`, the grace collector, and
  ArtifactStore's metadata grace. ArtifactStore retries `NotCaughtUp` like an unreachable quorum, and the slice loader
  sees it as unavailable, never as `ArtifactNotFound`. The cache treats it as a miss. The idempotency store fails the
  request retryably instead of re-running it.
  [verified: `ArtifactStoreTest$NotCaughtUpResolveTests`, `IdempotencyInterceptorTest$StoreUnreadable`]
- Wire change, re-recorded in `wire-assignment-baseline.txt`: `Readiness` (tag 90, in the one-byte window: it rides in every DHT read reply), `GetResponse`/`ExistsResponse`/
  `DigestResponse.readiness`, and `MigrationDataResponse.refused`.
  [unverified: no multi-node, Ember or cloud run. Ember and Forge were held for the suite lock.]
  [unverified/known: the boot walk covers at most RF nodes joining together. v1820 measured 5–6 concurrent joins while
  a partition was pending: the 2·RF walk then misses every old holder in about 1–2% of partitions. With the anchor
  rule that is a counted wait (WARN and the stuck gauge), not an empty partition served as authoritative.]
  [unverified/known: a deleted key can resurrect from a copy a non-owner kept. Non-owners never drop copies, so a
  catching-up replica pulling from one, or the #428 fallback read probe (which already does this today), can bring
  back a key removed on its owners. This is durable-delete scope: #1777 track 3, rc5.]
