### Fixed (2026-10-03 — #1730: a partitioned, deposed stream owner could keep acknowledging writes the majority would not hold)
- **Kafka-style in-sync replica sets for stream partitions (phase 1).**
  - **State:** each partition's committed ownership record now carries its ISR and an ISR version. Owner and ISR
    commit atomically. Every write to the record is a guarded `LeaderTransaction` that expects the exact
    committed record.
  - **Acknowledgement (CF ≥ 2):** an ack waits for EVERY ISR member, not any `CF − 1` registered replicas.
    Acks from replicas outside the ISR are not counted. The ack set is judged when the ack resolves, against
    the ISR then, and counts a member the owner has proposed to add until that write lands or fails (Kafka's
    maximal ISR), so an expansion racing an in-flight ack cannot leave a committed ISR member without the record. A publish is refused with `NOT_ENOUGH_REPLICAS` before
    the append while the ISR holds fewer than `confirmation_factor` members. The visibility watermark is the
    high-water mark, the lowest offset every ISR member confirmed.
  - **ISR changes:** only by consensus commit.
    - The owner proposes out a member lagging longer than the new `[streaming] isr_lag_max` (default 30 s,
      Kafka's `replica.lag.time.max.ms`). This is a liveness knob only.
    - The owner proposes in a replica that reached the high-water mark.
    - The leader drops members that left the live set.
  - **Minority owner:** a partitioned minority owner cannot commit a shrink, so it never acknowledges a write
    the majority will not hold.
  - **Failover:** elects only from the live ISR. With no ISR member live, the partition stays unavailable,
    reported as the `NoInSyncReplica` block. Unclean failover is off, and there is no opt-in yet.
  - **Placement:** ISR members stay placed as replicas, so they keep receiving the partition.
  - **Activation:** owner activation is bound to the ownership (owner, epoch, term), not the ISR, so an ISR
    change does not re-run the promotion gate.
- **CF 1 is a documented window** (Kafka `acks=1`): the ack means the owner's fsync only.
- **Behaviour change:** CF ≥ 2 ack latency is now the slowest ISR member's. A dead or slow member stalls acks
  until it leaves the ISR.
- **Limit until phase 2:** a divergent unacknowledged tail is not truncated automatically. The promotion gate's
  refuse-and-flag-for-operator stays.
