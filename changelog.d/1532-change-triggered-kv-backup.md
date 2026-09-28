### Added (2026-09-28 — #1532: change-triggered, leader-only KV backup)
- **`[backup]` now backs the cluster state up when it changes, not only on lifecycle transitions.**
  - With `[backup] enabled = true` and a path, the leader writes the backed-up KV state (#1530) as a
    `BackupEntryCodec` document (#1531) to a git repository at `<path>/kv-backup`, branch `kv-backup`.
  - It pushes to `[backup] remote` as a fast-forward and never forces.
  - A change to a backed-up key sets a dirty flag. One worker flushes after 500 ms of quiet, at most 5 s
    after the first change, so a burst of changes is one commit.
  - A flush whose backed-up entries did not change commits nothing, so runtime-key traffic makes no
    commits.
  - Only the leader writes. A new leader flushes once on election.
- **Ordering and the lineage gate.**
  - Each document carries `(lineage, incarnation, revision)` from the cluster incarnation key (#1529
    part 1) and the KV committed revision.
  - A head of the same lineage is written over only when it is not ahead of this cluster, compared
    lexicographically by incarnation, then revision. A deposed leader's late write is therefore dropped.
  - An empty remote is written freely, which establishes this cluster's lineage.
  - A head of another lineage GATES a freshly started cluster until a restore (#1533) or
    `aether backup declare-genesis`.
- **`aether backup declare-genesis` / `POST /api/v1/backup/declare-genesis`** (ADMIN, leader).
  - It moves this cluster's incarnation past a foreign head, so the next flush supersedes it.
  - It is refused, with a typed conflict, when the head is this cluster's own lineage. When that head is
    newer than the cluster, the refusal says to restore it instead.
- **Git unreachable.** Commits queue in the local repository (latest state wins). Pushes retry on backoff
  capped at 60 s, and one push carries every queued commit.
- **Warnings are emitted on transitions only:** `BACKUP_GATED` (it names the command),
  `BACKUP_REMOTE_UNREADABLE`, `BACKUP_PUSH_FAILING` (after 60 s of lag), `BACKUP_COMMIT_FAILED` and
  `BACKUP_RECOVERED`. They are logged; the `OperatorWarning` cluster event is #1574.
- **The backup header format changed.** It now carries `lineage=`, `incarnation=` and `revision=`
  (format version still 1: no document was written before this).
  - Lineage and incarnation are DERIVED from the document's own `ClusterIncarnationKey` entry. A
    document whose header disagrees with that entry is refused on decode (`HeaderEntryMismatch`).
  - A pre-genesis state carries incarnation 0 and no lineage, and is not backed up.
- **An incarnation change is flushed immediately**, with no debounce, so the new incarnation's first
  commit records it. This narrows, but does not close, the window in which an incarnation runs without
  any backup recording it — the residual a restore's floor cannot see `[unverified]`.
- **Verification.** Unit tests run against real git with bare remotes in temp directories:
  - a burst of changes is one commit, and unchanged, runtime-only and follower flushes make none;
  - handoff, stale leader, foreign lineage, a remote that moved, and unreachable-then-recovered;
  - the declare-genesis refusals;
  - the local repository carries `(lineage, incarnation, revision)`.
  - No multi-node verification yet `[design intent — unverified]`.
- **Restore is #1533.** This change takes backups; nothing reads them back yet.
- **The old consensus-snapshot path stays until #1533 deletes it** (rc4 does not cut with both live).
  - It is what a full-cluster restart restores from today: a whole snapshot installed at boot through
    sync adoption, the node's own included `[verified: ApiKeyFullRestartForgeTest]`. guarantees.md's
    "detect-only" wording is corrected.
  - Its interim hazards: it restores the whole KV, runtime keys included; it never advances
    `ClusterIncarnationKey` on a cold restart; it ignores `[backup] enabled`; and `[backup] interval`
    has no reader.
  - The two paths never share a repository: the new one lives at `<path>/kv-backup`.
