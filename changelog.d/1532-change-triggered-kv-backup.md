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
  - A head that stays ahead longer than 30 s (monotonic clock) raises one `BACKUP_HEAD_AHEAD` warning per
    episode. Routine lag after a leader change resolves by itself and stays quiet.
  - An empty remote is written freely, which establishes this cluster's lineage.
  - **The remote's lineage changes only by an operator declaration.** A head of any other lineage is
    GATED, whatever the incarnations; the transition warning names both lineages and the command.
    `aether backup declare-genesis` commits a declaration (`declared-lineage.txt`) for exactly this
    cluster's `(lineage, incarnation)`, and only that lets the service replace the head.
- **`aether backup declare-genesis` / `POST /api/v1/backup/declare-genesis`** (ADMIN, leader).
  - It moves this cluster's incarnation to `max(own, head) + 1` (never backwards), commits the
    declaration beside the backup, and pushes it; the next flush then supersedes the head.
  - The incarnation step is a leader transaction witnessed on the incarnation it read. A concurrent
    write makes it refuse (`NOT_COMMITTED`) instead of being overwritten.
  - It is safe to re-run after any push failure (`DeclarationNotPublished`): a re-run publishes the
    declaration already committed locally and does not move the incarnation again.
  - It is refused, with a typed conflict, when the head is this cluster's own lineage. When that head is
    newer than the cluster, the refusal says to restore it instead.
- **Git unreachable.** Commits queue in the local repository (latest state wins). Pushes retry on backoff
  capped at 60 s, and one push carries every queued commit.
- **No credential prompts.** Git runs with `GIT_TERMINAL_PROMPT=0` (https) and ssh with
  `-o BatchMode=yes` (appended to any `GIT_SSH_COMMAND` already set); every git command is bounded by a
  timeout. All three are pinned by `GitBackupRepositoryTest` against a 401 http server, a prompting fake
  ssh and a hanging fake ssh.
- **Fixed before release:** the backup encoder no longer throws on the `LeaderKey` atom that a node's KV
  store holds beside its `AetherKey` entries. Before the fix, every flush on a real node would have
  failed.
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
  - (d) A cold restart restored from an older snapshot writes nothing while the newer head is ahead;
    the only signal is `BACKUP_HEAD_AHEAD`. Once its revision overtakes, its state REPLACES the newer
    head, and git history keeps the replaced commit. That replacement raises its own WARN,
    `BACKUP_HEAD_REPLACED`, naming the replaced revision — never the `BACKUP_RECOVERED` all-clear.
  - The two paths never share a repository: the new one lives at `<path>/kv-backup`.
