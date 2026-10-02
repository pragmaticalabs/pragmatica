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
    previous holders are not known exactly, so they are walked on the live ring (see the boot-walk note below). Completion is proven by reading back the pulled
    entries, and a refused pull is explicit and counted.
  - When no live source is serving (genesis, a whole-cluster cold restart, every holder gone), the partition completes
    on the union of what the sources hold. That is logged at WARN, because its absent answers are then best-effort.
  - A 1 s catch-up tick retries pending partitions. Dead sources are skipped.
  - A round pulls from every source whose digest differs.
  - A timed-out round decides on the answers it has, but only when a serving ANCHOR answered: a current co-replica or
    an exactly recorded previous holder. A boot-walk node answering SERVING is not enough, because it may never have
    held the partition. A decided round keeps its pulls for one more round timeout.
  - A round completes only the pending spell it started in. A partition lost and regained while a round's pull was
    in flight starts a new spell, and the old round's late pull no longer marks it serving.
  - A partition stuck behind silent sources is warned and counted by the gauge
    `aether.dht.catchup.stuck.partitions`.
  - A partition lost while pending leaves the set.
  [verified: `integrations/dht/src/test/java/org/pragmatica/dht/DHTCatchUpGateTest.java`, `DHTCatchUpRegainTest`,
  `aether/node/src/test/java/org/pragmatica/aether/node/AetherNodeDhtCatchUpBootTest.java`]
- **The #1770 `absentGrace` stop-gap is removed.** That covers `ReadOptions`, `AbsentGrace`, the grace collector, and
  ArtifactStore's metadata grace. ArtifactStore retries `NotCaughtUp` like an unreachable quorum, and the slice loader
  sees it as unavailable, never as `ArtifactNotFound`. The cache treats it as a miss. The idempotency store fails the
  request retryably instead of re-running it.
  [verified: `ArtifactStoreTest$NotCaughtUpResolveTests`, `IdempotencyInterceptorTest$StoreUnreadable`]
- Wire change, re-recorded in `wire-assignment-baseline.txt`: `Readiness` (tag 90, in the one-byte window: it rides in every DHT read reply), `GetResponse`/`ExistsResponse`/
  `DigestResponse.readiness`, and `MigrationDataResponse.refused`.
  [unverified: no multi-node, Ember or cloud run. Ember and Forge were held for the suite lock.]
- **Boot walk.** A partition pending since boot draws sources from the first RF + J + U nodes of its walk on the
  current ring. J counts the members that joined since boot. U counts the ring members this node has not heard
  from over the DHT since boot (a digest request or answer). Whenever at least one of the partition's old holders
  survives, the sources include one.
  [mechanism: each join, and each phantom, displaces an old holder by at most one walk position, and a removal only
  moves holders earlier, so a surviving old holder lies within RF + J + U.]
  [verified: `DHTCatchUpBootWalkTest`, 1–8 joins with 0, 2 and 4 removals, 0 misses. A fixed 2·RF walk missed every
  old holder in 0.3–3.2% of partitions at 5–8 joins, and those partitions were served empty.]
  The boot ring is the static configured-core list, so a scale-up core that joined before this node booted also
  counts toward J. That lengthens the walk, which is safe for reach.
  **Phantom static cores.** The same list can name cores that left before this node booted. Its ring keeps such a
  phantom until the membership prunes it, and J does not count it. Each phantom therefore took a walk slot from a
  real holder: v1820's sim found that 2 phantoms miss every old holder in 848 of 15,521 partitions with no joins.
  That is beyond anything run 7 showed: it had one phantom, in 1 of 4 boots, which is the sim's 0-miss row.
  A phantom can never be heard from, so U counts it, and the holders it displaced are asked.
  [verified: `DHTCatchUpGateTest.bootWalk_asksTheHoldersDisplacedByPhantomCores_andNeverServesEmpty`, red without U]
  U comes from DHT contact rather than SWIM: the DHT's liveness view reports a seeded phantom as a live member.
  Until a member has answered, it counts in U, so a booting node's first round asks every ring member.
  When the ring prunes a node, every round that was asking it is dropped and restarted from the current ring, so
  no round keeps a source set frozen before the prune.
  [verified: `DHTCatchUpGateTest.roundWaitingOnAPhantom_isRestartedWhenTheRingPrunesIt`, red without the drop]
  **Interaction with #1830** (the phantom bound): with U, a phantom does not make the gate wrong at any point in
  the window, however long the phantom lasts. What the window still costs is time. A phantom among a partition's
  co-replicas never answers, so a round there cannot decide until the phantom is pruned. That is a counted wait (the
  stuck gauge), never an empty partition. #1830 shortens that wait. Interim finding (i-phantom): SWIM prunes a
  phantom at least 75 s after boot, and after 3 min 40 s in run 7.
  [unverified: code-only — a phantom is never pruned if the node stays in COLD_BOOT; its partitions then wait.]
  **A partition whose old holders were ALL removed is served from what remains**: the union of what its live
  sources hold, logged at WARN. Its absent answers are then best-effort.
  [unverified/known: a deleted key can resurrect from a copy a non-owner kept. Non-owners never drop copies, so a
  catching-up replica pulling from one, or the #428 fallback read probe (which already does this today), can bring
  back a key removed on its owners. This is durable-delete scope: #1777 track 3, rc5.]
