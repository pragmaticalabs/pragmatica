### Changed (2026-09-19 — #1263: the three stream write paths are one write operation)
- **`DefaultStreamPublisher` (slice `StreamPublisher`), `PartitionedStreamAccess` (`StreamAccess.publish`) and
  `StreamWriteRouter` (management publish) each carried their own copy** of owner resolution, forwarding,
  the retry wrapper, the local append and the replication barrier. #1230 had to apply the same fix in all
  three. `StreamWriteRouter` is now the only implementation; the two typed publishers encode the event,
  pick its partition and delegate.
  [mechanism: all three entry points delegate to `StreamWriteRouter.publish`; pinned at unit level by `StreamWritePathContractTest`, one contract run against each entry point]
- **The min-sync barrier is read live** from the stream's committed `min-sync-replicas` on every publish.
  Before, the typed publishers froze the value at construction. `DefaultStreamPublisher.streamPublisher`
  and `PartitionedStreamAccess.streamAccess` no longer take a `minSyncReplicas` argument (pre-GA API change).
- **An unknown self never forwards on the routing arm** (the rule `DefaultStreamPublisher` always applied):
  with no identity to compare against, the HRW owner cannot be established as another node, so the write
  lands locally and the committed-owner admission decides. The committed-owner redirect (#1230) still
  forwards, because the refusal itself names the owner as another node. `PartitionedStreamAccess`'s no-self
  sentinel previously compared unequal to every owner and forwarded.
  [mechanism: `StreamWriteRouter.isRemote` over `Option<NodeId>`; pinned at unit level by `StreamWritePathContractTest$UnknownSelf`]
- **Behaviour changes beyond the minSync read.**
  - A publisher built EVENTUAL over a stream whose committed config is STRONG or UNKNOWN is now refused
    (the shared guard reads the committed config, not the mode the publisher was built with).
  - The committed config is also the source for the reverse case: a publisher whose own blueprint mode is
    UNKNOWN publishes normally to a stream committed EVENTUAL, where before the slice publisher refused on
    its own unreadable mode. The stream's committed config is what the write must honour, and an unreadable
    blueprint mode over a stream that is committed EVENTUAL promises nothing the write cannot deliver.
  - An EVENTUAL `publishBatch` whose group fails now fails the batch; it was acknowledged as success.
  - A STRONG stream on the slice publisher keeps its explicit consensus alternative and does not go through
    the shared router; with no consensus path wired it refuses with `CONSENSUS_PATH_UNAVAILABLE`, as the
    router does.
