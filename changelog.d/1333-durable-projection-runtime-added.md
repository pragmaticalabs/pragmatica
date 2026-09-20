### Added (2026-09-20 — #1333: durable projections wired to the runtime)
- **A projection can now be attached to the node that hosts its slice, and rebuilt by an operator.**
  `ProjectionRuntime` is a slice-scoped resource declared against the projection's topic section
  (`@ResourceQualifier(type = ProjectionRuntime.class, config = "<topic section>")`); `attach(projection)`
  registers it with the node and returns the copy wired with the node's replay cursor. After every
  committed cursor for the group, the node reports it to the projection stamped with the epoch the
  committing consumer runs under, which is what lets a REBUILDING partition skip a dead-lettered replay
  offset and go LIVE `[verified: aether/forge/forge-tests DurableProjectionRebuildForgeTest on a 3-node
  Ember cluster; aether/node DurableProjectionRebuildTest carries the mutation table]`.
- **The rewind is fenced in KV, and its epoch is minted from committed state.** `StreamCursorCheckpointValue`
  carries the group's rewind epoch and is `EpochBearing`, so the applier refuses a checkpoint stamped with a
  strictly older epoch — a consumer still committing its pre-rewind position cannot move a rewound cursor
  forward. The rewind token is minted by the node from the group's COMMITTED checkpoints (strictly newer
  than every epoch found), never from a store-local counter, and the rewind record mints its epoch
  (`EpochBearing.mintsEpoch`), so the applier refuses it unless strictly newer: a fresh store after a node
  restart or an assignee move rebuilds correctly, and two rebuilds racing on the same committed state
  cannot both succeed — the loser's `rebuild()` fails `[verified: aether/node DurableProjectionRebuildTest
  freshStoreAfterACommittedRewind_mintsAStrictlyNewerEpoch_andRestartsTheConsumer and
  equalEpochRewindPut_isRefusedByTheApplier_andRebuildReportsIt]`. A consumer resumed under a rewound
  epoch checkpoints its catch-up at the head, so a clean rebuild's committed cursor does not sit at the
  rewind offset until the next event `[verified: cleanRebuild_commitsTheRebuiltHead_withoutAFurtherEvent]` `[verified: aether/node
  DurableProjectionRebuildTest zombieCheckpoint_atTheOldEpoch_isRefusedByTheApplier on the real KVStore applier]`. Resume order is `(rewind epoch,
  offset)`, so a node that crashed before applying a rewind cannot resurrect its stale-high local cursor.
  The group identity a projection reports for is the runtime's `artifactBase#method`, inferred when the
  slice has exactly one durable subscriber on the topic and refused loudly otherwise.
- **`GET /api/v1/topics/{namespace}/{topic}/{version}/groups`** and
  **`POST /api/v1/topics/{namespace}/{topic}/{version}/rebuild/{group}`** (both LOCAL), with
  `aether topics groups` / `aether topics rebuild`; `ProjectionStore.replayStatus()` so the groups
  route shows REBUILDING/LIVE per partition; `InMemoryProjectionStore` / `InMemoryProjectionClaims` as
  in-process backings whose scope is stated on the type.
