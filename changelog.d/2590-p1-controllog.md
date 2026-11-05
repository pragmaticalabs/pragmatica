### Added (2026-11-02 — #2590 P1: the control log, consumer progress off core consensus, mechanism only)
- **A control log on the stream substrate** (`aether-stream`, package `stream.control`): records with a latest-per-key
  index, admitted by the partition owner against its admitted view (the committed index plus its in-flight tail) on one
  serial queue per partition, appended as one run with one group-commit fsync, and acknowledged only after that fsync
  and the confirmation barrier. Every node keeps the committed index of the partitions it holds, fed only from committed
  offsets (the owner's visible position, a replica's `CommitAdvance`), rebuilt from the log on a new ring, a suffix
  truncation or a promotion to owner, and refused over a hole in the retained log. Includes the retention floor (the
  oldest live key's latest record, `-1` while the index is invalid), tombstones and the owner's carry-forward.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/control/`,
  `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/segment/ControlRetentionFloorTest.java`]
- **Nothing uses it yet.** Consumer cursors move onto it in P2 and entity fold markers in P3; no production wiring
  builds a control log in this change.
- **Wire changes.** A control record is a stream record whose payload starts with a control tag (the WAL frame has no
  field for one), and a forwarded batch-append (`ControlAppendForward`, answered with one outcome per record) joins the
  stream forward protocol, pinned at system tags 2202–2206. Pre-GA, nodes of different versions are not supported in one
  cluster across this change.
- **Instrument:** the cursor command writer's consensus batches are counted by key class (checkpoint, read barrier,
  rewind, entity pointer) and exposed as the gauges `aether.consensus.cursor.batches` and
  `aether.consensus.cursor.commands{class=…}`. [verified:
  `aether/node/src/test/java/org/pragmatica/aether/node/stream/ConsensusBatchCountersTest.java`]
