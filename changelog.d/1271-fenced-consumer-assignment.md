### Fixed (2026-09-19 — #1271: consumer-group partition assignment was unfenced)
- **Two nodes could deliver the same consumer partition and both write its cursor.** Each node decided
  which `(group, partition)` it consumed from its own view of ownership, membership and placement, on
  its own 5 s tick; while two views disagreed both attached, both delivered, and the last cursor write
  won — duplicate delivery, broken per-partition ordering for that window, and cursor regression.
- **A committed assignment per `(stream, group, partition)`** (`ConsumerAssignmentKey` /
  `ConsumerAssignmentValue`, replacing the never-used `StreamPartitionAssignment*` records on the same
  wire tags 1131/1650/1651) is written by a leader-only `ConsumerAssignmentWriter` with epoch
  `Epoch(rabiaTerm, assignmentTerm)`. A node attaches only where the committed record names it; an
  absent record admits nobody (a new group's first delivery waits for one leader reconcile tick).
  [mechanism: `StreamConsumerManager.admittedPartitions` reads the committed record]
- **Cursor checkpoints are fenced at apply time.** A new `KVStore` applier arm (`AssignmentGuarded`)
  admits a `StreamCursorCheckpointKey` write only when its token — assignee and epoch — equals the
  committed assignment's, so a deposed node cannot move or regress the committed cursor, including
  before its successor's first checkpoint. [mechanism: cross-key guard in `KVStore.staleWrite`; pinned
  by `KVStoreAssignmentGuardTest` and `ClusterCursorStoreTest$AssignmentFence` against the real applier]
- **The loser learns and stops.** `ClusterCursorStore` re-reads the committed checkpoint after each
  publish (after a `Noop` barrier on a forwarding node) and reports a refused one as the new terminal
  `CommitOutcome.Fenced`; the runtime stops delivery, never retries it, and detaches without a final
  flush. Delivery is also re-checked against the committed record on every pass, and the node's
  quorum-loss self-fence now abandons every consumer at DETECTION.
- **The guarantee is a BOUNDED overlap, not a single deliverer:** two nodes deliver one partition only
  while their committed-state mirrors disagree (apply skew plus one in-flight batch), or, on the
  minority side of a partition, until its quorum-loss detector fires. The disk cursor records the epoch
  it was written under, so a node regaining a partition never resumes from its earlier tenure's cursor.
  [unverified: no multi-node run measured the overlap window or the partitioned-minority bound]
