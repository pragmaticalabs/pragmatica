# Backup & Recovery Runbook

## Overview

With `[backup] enabled = true` and a `path`, the **leader** keeps a git backup of the cluster's
**declared state** — every `ClusterStateKey` in the KV store: blueprints, slice targets, stream
configs, API keys, cluster config, schema versions, communities, operator settings, and the cluster's
lineage and incarnation (#1532). It lives in the repository `<path>/kv-backup` on branch `kv-backup`, as
one readable document (`kv-backup.txt`, one entry per line), and is pushed to `[backup] remote` as a
fast-forward — never forced.

- **When it is written:** on change. A change to a backed-up key marks the backup dirty; it is flushed
  after 500 ms of quiet and at most 5 s after the first change, and an incarnation change is flushed at
  once. Runtime state (placements, partition owners, leases, node registrations) is never backed up
  `[mechanism: sealed AetherKey = ClusterStateKey | RuntimeKey; BackupKeyClassificationTest]`.
- **RPO:** changes made after the last successful push. With the remote unreachable, commits queue in the
  leader's local repository, so they also depend on that node's disk surviving; `BACKUP_PUSH_FAILING`
  fires after 60 s of lag.
- **Consensus itself is in memory** on every node. There is no per-node consensus snapshot any more (the
  old `state.toml` path was removed by #1533); the KV backup is the only thing a whole-cluster restart
  restores from.

## Restarting a whole cluster — a regular start, then the restore

A whole-cluster restart is a **regular cluster start with a fresh set of core nodes** (new NodeIds, the
same cluster configuration and the same `[backup]` remote), followed by the restore. Restarting the same
NodeIds with empty state is not a supported restart mode (#1543).

1. Stop every node.
2. Start the fresh cores with `[backup] restore = "auto"` (the default). Genesis forms exactly as on a
   first start.
3. The leader decides once, and commits the decision (`Backup restore decision: …` in the log):
   - **RESTORED** — the backup head is applied, then the incarnation moves past every incarnation the
     backup history records for the lineage `[verified: ApiKeyFullRestartForgeTest — fresh cores
     restore, the minted key is accepted on every node, the incarnation rises]`;
   - **FRESH** — the backup is empty, or `restore = "fresh"`;
   - **SKIPPED_EXISTING_STATE** — the KV already holds cluster state (a running cluster is never restored
     over);
   - **DISABLED** — the deciding leader has no `[backup]`.
4. Until the decision commits, every backup-enabled node **refuses writes to cluster state**
   (`RestorePending`, retryable), so nothing seeds over the restore `[verified:
   EmberKvBackupRestoreTest]`. Runtime work (DHT partition ownership, membership) proceeds.
5. Placement is **rebuilt, not restored**: slices from the restored slice targets are deployed onto the
   fresh nodes, streams from the restored stream configs get owners among them, and the worker
   communities re-form `[verified: ApiKeyFullRestartForgeTest — the restored slice is ACTIVE on the fresh
   nodes only; every stream owner is a fresh node]`. An in-flight rolling update is resumed as after a
   leader failover (the split resumes once both versions are ACTIVE on the new nodes); promote, rollback
   and complete work on it `[verified: DeploymentRestoreReloadTest, unit level]`.

**What the restore changes on the way in** (the only normalisation, in `BackupRestoreCoordinator.normalised`):

| Entry | Restored as | Why |
|---|---|---|
| Community ACTIVE / DEGRADED | FORMING | those states observed the old cluster's members |
| Deployment outcome IN_PROGRESS | not restored | it names an apply that died with the old cluster |
| Schema version MIGRATING | PENDING | the database survives, the migration lock does not; PENDING re-runs the migration's check |
| Entity checkpoint pointer | **not restored** (interim) | its offsets belong to the old cluster's log; see below |

**Data-plane limits — what a whole-cluster restart does NOT bring back:**
- **Entity state restarts empty.** The backup keeps entity checkpoint pointers, but a restore withholds
  them and raises `BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED` naming each partition: the pointer carries
  no incarnation, and seeding a fresh log from it could skip new records `[verified:
  BackupRestoreCoordinatorTest#aBackedUpEntityCheckpoint_isNotRestored_andTheWithholdingIsWarned]`.
- **Stream records are lost.** A stream's configuration is restored; records that lived only in the old
  nodes' WAL and ring are gone. `[unverified: whether a fresh cluster re-reads sealed segments that
  survive in a remote storage tier — their index lived in the old nodes' metadata snapshots; not tested]`
- DHT artifact caches are node-local and re-fetch from the artifact repository.

**When the restore cannot read the backup** (remote unreachable, head undecodable): the cluster stays up
and GATED — no fresh boot, no crash loop. One `BACKUP_RESTORE_BLOCKED` warning names the remote and the
two exits; the restore retries with backoff capped at 60 s. Exits: fix the remote, or restart with
`[backup] restore = "fresh"` to abandon the backup `[verified: BackupRestoreCoordinatorTest.Blocked,
EmberKvBackupRestoreTest]`.

**Hazard — restoring the same backup into two live clusters.** Each restore mints a new cluster instance
id (#1533), so two clusters restored from the same head at the same time reach the same lineage and
incarnation as different instances. The backup refuses to let either one replace the other's head:
whichever cluster finds the other's head raises `BACKUP_FORKED`, naming both instance ids, and backs up
nothing from then on; a push race is settled by git's fast-forward check, and the loser ends FORKED.
`[verified: KvBackupServiceTest.Fork]` **Detection is on the second writer only:** `BACKUP_FORKED` on a
cluster means another live cluster with the same lineage and incarnation owns the backup head; that other
cluster shows NOTHING and keeps backing up. The FORKED cluster's state is not being backed up. Resolve it
by stopping the FORKED cluster, or by running `aether backup declare-genesis` on it: that moves it to a new
incarnation and instance of the SAME lineage, which then supersedes the other cluster's head — so run it
only on the cluster whose state should continue, and stop the other one.

**`restore = "fresh"` against an existing backup** starts a new lineage, and its backup is then GATED
(`BACKUP_GATED`) until `aether backup declare-genesis` makes it the head; the old lineage stays in git
history.

**No `[backup] remote`:** the restore reads only the local repository of whichever node leads the cold
start, which may be older than another node's. A startup `BACKUP_RESTORE_SOURCE_LOCAL` warning says so.
`[unverified: picking the newest head across nodes is not implemented — configure a remote]`

**Every core should carry the same `[backup]` section.** Mixed configurations are unsupported: a leader
without `[backup]` commits DISABLED, which opens the restore gate on the others.

## Storage metadata snapshots — a separate mechanism, per node, per storage instance

Each storage instance (`artifacts`, `content`, `streams`, …) also keeps its OWN metadata snapshots
under `[storage.<instance>] snapshot_path`: `snapshot-<epoch>.dat` files plus a one-line pointer
file `LATEST` naming the current one (`DefaultSnapshotManager`, `integrations/storage`). These are
not consensus state and are not covered by `[backup]`; the WAL truncation of the `streams` instance
is bounded by the refs in the latest snapshot on disk (#1345).

**How they are written (#1353):** the snapshot goes to `snapshot.partial`, is fsynced
(`FileChannel.force(true)`) and renamed over `snapshot-<epoch>.dat` in one atomic rename
(`FileOps.moveAtomic`); `LATEST` is written the same way via `LATEST.partial`. So a write that
stops part-way — disk full, process killed — leaves the previous snapshot and the previous `LATEST`
intact and removes its partial
(`SnapshotDurableWriteTest#forceSnapshot_latestWriteInterrupted_previousSnapshotStillRestores`,
`#forceSnapshot_snapshotWriteInterrupted_leavesNoTornFileUnderAnyName`). Snapshot writes are
serialised (`forceSnapshot()` holds a lock; the tick and the HTTP `POST …/snapshot` route can call
it at once), so one writer owns the partial names at a time
(`#forceSnapshot_concurrentCallers_leaveOnlyCompleteCorrectlyNamedFiles`). Before #1353 both files
were truncated in place, and a torn `LATEST` restored NOTHING although a complete snapshot sat
beside it. `[unverified: power loss — the rename's directory entry is not fsynced; the pinned property is
torn-file behaviour.]`

**How they are read at boot:** the file `LATEST` names is tried first. If it is missing, torn or
fails its content-hash check, the retained snapshots are tried newest-first and the first complete
one is restored, at WARN:

    Snapshot <dir>/snapshot-000042.dat named by LATEST is unreadable; restored another retained
    snapshot snapshot-000041.dat (epoch=41) instead. Metadata recorded only in the unreadable
    file is lost unless a WAL replays it. See docs/operators/runbooks/backup-recovery.md

A torn snapshot is never restored. If something is on disk — a `LATEST` file, or any
`snapshot-*.dat` — and none of it restores, the node REFUSES to boot (#1013): `createAll` fails with

    Failed to create storage '<instance>': Storage '<instance>' has a metadata snapshot that does not
    restore, readiness not signalled: Snapshot <dir>/snapshot-000042.dat named by LATEST is
    unreadable and none of the 0 other retained snapshot(s) restores; refusing to start with EMPTY
    metadata. See docs/operators/runbooks/backup-recovery.md

and the instance's readiness gate never leaves `LOADING_SNAPSHOT`. Only a directory holding neither
`LATEST` nor a snapshot file (or no directory at all) boots with empty metadata, as a first boot
does; a directory that exists but cannot be listed refuses too, since absence cannot be established
from a failed read. Before #1013 every one of these booted read-ready on empty metadata with at most
a WARN, and replayed the WAL as if the metadata had never existed.

**Operator action on the refusal:** the boot names the file. Restore the snapshot directory from a
backup of the volume, or — if the metadata is genuinely expendable (a `content` or `artifacts`
instance whose blocks can be re-fetched) — stop the node, move the unreadable `snapshot-*.dat` files
and `LATEST` out of the directory, and start it: an empty directory is a first boot. For the
`streams` instance the refs in the snapshot are how sealed segments are indexed at boot
(`SegmentIndex.rebuildFromRefs`); started empty, the segments on disk are not indexed, so treat the
refusal as data loss until the file is recovered.

**Operator action on the fallback WARN (an older snapshot restored):**
1. Nothing is required for the node to run: the next snapshot write repoints `LATEST` at a fresh
   complete file. The read path does not rewrite `LATEST` and does not delete the unreadable file.
2. Keep the unreadable file until you have decided whether the mutations between the two epochs
   matter; it is the only evidence of what was lost. For the `streams` instance, segment refs that
   existed only in the torn snapshot are unreachable until it is repaired; the WAL replays whatever
   it still holds, and a WAL that starts above the restored watermark is accepted as reclaimed
   history with its own WARN (#1258; with #1345 truncation reads the snapshot through this same
   fallback, so truncation and recovery agree on which file is current).
3. To remove it by hand: stop the node, delete the named `snapshot-<epoch>.dat`, start the node.
   Never edit `LATEST` while the node runs — it is rewritten on every snapshot.
4. Recurring WARNs after a clean shutdown mean the disk is tearing completed writes; check the
   volume before trusting any snapshot on it.

## Enabling Backups

### Configuration (aether.toml)

```toml
[backup]
enabled = true
path = "/data/backups"
remote = "git@backups.example.com:ops/cluster-a-kv.git"
restore = "auto"
```

| Field | Default | Description |
|-------|---------|-------------|
| `enabled` | `false` | Enable the KV backup and the restore. Also requires a non-blank `path`. |
| `path` | env-dependent | Directory holding the backup repository `<path>/kv-backup` |
| `remote` | `""` | Git remote the leader pushes to (fast-forward only). Strongly recommended: without it a restore reads only the deciding leader's local repository |
| `restore` | `"auto"` | What a cold start does with the backup: `auto` restores the head (or starts fresh when it is empty); `fresh` ignores it |

**Default paths by environment:** LOCAL `./aether-backups`, DOCKER `/data/backups`, KUBERNETES
`/var/aether/backups`.

Git never prompts: https credential prompts are disabled (`GIT_TERMINAL_PROMPT=0`) and ssh runs in batch
mode; a remote that needs credentials the process does not hold fails, and the backup reports
`BACKUP_PUSH_FAILING` (push) or `BACKUP_RESTORE_BLOCKED` (restore).

## Warnings

| Code | Meaning | Operator action |
|------|---------|-----------------|
| `BACKUP_GATED` | The head belongs to another lineage | Restore it, or `aether backup declare-genesis` to make this cluster the head |
| `BACKUP_FORKED` | Another cluster instance holds the head at this cluster's own lineage and incarnation (two clusters restored from the same backup) | Retire one cluster; run `aether backup declare-genesis` on the one whose state continues |
| `BACKUP_HEAD_AHEAD` | The head of this lineage stayed ahead of this cluster for > 30 s | Nothing is backed up meanwhile; find the other writer of this lineage |
| `BACKUP_HEAD_REPLACED` | This cluster's state replaced a newer head | The replaced commit is in git history; inspect it |
| `BACKUP_REMOTE_UNREADABLE` | The head cannot be decoded | Repair or move the head |
| `BACKUP_PUSH_FAILING` | Commits have not reached the remote for 60 s | Check remote, credentials, network |
| `BACKUP_COMMIT_FAILED` | The local repository cannot take a commit | Check disk, permissions, git |
| `BACKUP_RESTORE_BLOCKED` | A cold start cannot read the backup; cluster-state writes are refused | Fix the remote, or restart with `restore = "fresh"` |
| `BACKUP_RESTORE_SOURCE_LOCAL` | No remote: the restore reads one node's local repository | Configure `remote` |
| `BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED` | A restore withheld entity checkpoints; entity state restarts empty | None — this is the documented limit |
| `BACKUP_RECOVERED` | A failing backup is current again | — |

## Inspecting the backup

```bash
cd /data/backups/kv-backup
git log --oneline                  # "kv backup lineage=… incarnation=… revision=…" per commit
git show HEAD:kv-backup.txt        # header, then one "<base64 value> <key>" line per entry, sorted by key
```

Keys are readable, so `git diff` between two commits shows which entries changed.
