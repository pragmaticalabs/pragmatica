### Fixed (2026-11-02 — #2594: five more leader-only writes trusted a committed leader record that named another node)
- **A deposed leader could still commit five kinds of leader-only write under the new leader's record**: a cluster
  config change (apply, scale, upgrade), a DHT replication change, a backup genesis declaration, a restore decision, and
  a stream or entity ownership write. Each was gated only on the node's local leader flag and carried the committed
  `LeaderValue` read at build time; the applier checks only that the transaction carries the committed value. Each now
  issues the write only when the committed record names this node and it leads (#2592 fixed the deployment rollback).
