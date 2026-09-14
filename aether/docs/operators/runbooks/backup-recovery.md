# Backup & Recovery Runbook

## Overview
Declared cluster state (the consensus KV-Store snapshot) is persisted by `GitBackedPersistence`
(`integrations/consensus`) when `[backup]` is enabled: a single file, `state.toml`, in a local git
repository at `[backup] path`, one commit per save, optionally pushed to a git remote. This is the
only backup mechanism. **There is no backup API or CLI** — `POST /api/v1/backups` and
`aether backup`/`aether backups` were removed in #676 because their only implementation was a
`disabled()` stub that returned `backup-disabled` in every configuration.

What the file holds, precisely: a `# Phase: N` header followed by the **base64 of the raw binary KV
snapshot** (`AetherNode::snapshotToBase64`). It is not structured TOML and `git diff` between two
commits shows two opaque blobs, not per-key changes. Git gives history, integrity and offsite copies;
it does not give readable diffs.

**When a save happens — lifecycle transitions only, never on commit** (`RabiaEngine`): quorum-loss
pause, membership reconfigure (this save writes an empty state at phase 0), graceful stop, and a
re-persist right after a restore-from-disk. A crash or power loss therefore never produces a
snapshot; the last one on disk is from the last lifecycle event. `[backup] interval` is parsed and
read by nothing — there is no periodic save.

**How the file is written (#676):** to `state.toml.partial`, fsynced, then renamed over
`state.toml`, so an interrupted save leaves the previous snapshot intact and loadable
(`GitBackedPersistenceTest#save_interruptedMidWrite_keepsThePreviousSnapshotLoadable`). Before
#676 the write truncated `state.toml` in place and a half-written file loaded as an EMPTY state.

## Enabling Backups

### Configuration (aether.toml)

```toml
[backup]
enabled = true
path = "/data/backups"
remote = ""
```

| Field | Default | Description |
|-------|---------|-------------|
| `enabled` | `false` | Enable git-backed persistence. Also requires a non-blank `path`; `enabled = true` with a blank `path` silently stays in-memory. |
| `path` | env-dependent | Git repository directory for `state.toml` |
| `remote` | `""` | Git remote URL; when set, every save is followed by `git push` |
| `interval` | `"5m"` | Accepted and ignored — no periodic save exists |

**Default paths by environment:**
- LOCAL: `./aether-backups`
- DOCKER: `/data/backups`
- KUBERNETES: `/var/aether/backups`

### Setting Up Remote Backup
1. Create a private git repository
2. Set the `remote` field to the repository URL
3. Ensure the Aether process has SSH/HTTPS credentials

## Intentionally resetting a cluster — clear per-node persistence first

**With `[backup] enabled = true`, wiping the cluster is not enough on its own.** Enabling backup
gives each node durable consensus persistence, and a node that keeps its old backup directory across
an intentional reset carries consensus history the reset cluster never had.

Since #660, sync adoption refuses to install a state older than what the node already holds. That is
the correct safety behaviour — committed state must not be discardable by a sync — but it means the
old-disk node **no longer converges by silently discarding its history**. It activates on its own
old state and diverges from the freshly-reset cluster. (Before #660 it regressed onto the cluster's
state and the reset appeared to "just work", which was the same divergence hazard hidden behind a
detect-only WARN — see D9 in `aether/docs/specs/cluster-topology-overhaul-spec.md`.)

**The node names this condition itself.** Look for:

```
Node <id> BOOT FUTURE-HISTORY detected (§6.4, detect-only): persisted Rabia phase <N> exceeds
cluster-reported sync phase <M> — this node carries history the joined cluster never saw
```

**Recovery action.** On every node, before restarting into the reset cluster, remove the backup
directory configured as `[backup] path` (`./aether-backups`, `/data/backups`, or
`/var/aether/backups` by default — see the table above). Then start the cluster. If the WARN above
appears after a reset, that node's persistence was not cleared: stop it, clear its `[backup] path`,
and restart it.

This applies only to a DELIBERATE reset. Do not clear persistence to "fix" the warning during a
genuine recovery — there the node's history is the thing you are trying to keep, and
`Recovery from Total Cluster Loss` below is the correct procedure.

## Taking a Backup

There is no manual trigger. A save is produced by the lifecycle transitions listed above; a
graceful stop (`aether nodes shutdown`, or SIGTERM to the process) is the operator's way to get a
fresh snapshot before maintenance. Each save is one git commit (`Backup phase N at <instant>`).

## Listing Backups

```bash
cd /data/backups
git log --oneline
```

## Recovery from Total Cluster Loss

### Step-by-step:
1. Stop all nodes
2. Ensure `state.toml` is present in the backup directory
3. Start one node first, with backup enabled. Its peer list (or discovery target) must still name
   all three configured nodes — the #782 minimum-cluster-size gate checks the CONFIGURED topology,
   not how many nodes happen to be up, so starting the first node of a properly-configured
   three-node cluster does not abort. That node will not reach quorum or elect a leader until a
   second node joins; that is expected while it loads state.
4. The node loads state from `state.toml` during sync
5. Start the remaining nodes — they sync from the restored node and the cluster reaches quorum once
   the second node joins

### Restoring a Specific Backup
With all nodes stopped, check out the wanted commit's `state.toml` in the backup directory of the
node you will start first, then follow the steps above:
```bash
cd /data/backups
git log --oneline                    # pick the commit
git checkout <commit-id> -- state.toml
```

## Inspecting Backup History

Since backups are stored in git:
```bash
cd /data/backups
git log --oneline          # List all backups
git show HEAD:state.toml   # Current snapshot: "# Phase: N" + base64 of the binary KV snapshot
```
`git diff` between commits compares two base64 blobs — it tells you the state changed, not what changed.

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Backup fails | No write permission on backup dir | Check directory permissions |
| Push fails | Invalid remote or credentials | Verify remote URL and SSH keys |
| Restored state ignored | Nodes were still running when `state.toml` was checked out | Stop all nodes before restoring; the file is read at sync time only |
| Empty backup | KV-Store has no entries | Normal for fresh cluster |
| `BOOT FUTURE-HISTORY` WARN after an intentional reset | Node kept its old `[backup] path` across the reset | Stop the node, clear its backup directory, restart — see "Intentionally resetting a cluster" above |
