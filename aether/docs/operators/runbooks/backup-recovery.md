# Backup & Recovery Runbook

## Overview
Aether's KV-Store durable backup serializes cluster metadata to a single TOML file (`state.toml`) managed in a local git repository. Git provides versioning, history, diffs, integrity checking, and optional remote push for offsite backup.

## What Gets Backed Up
- Slice deployment targets and scaling state
- Node lifecycle states
- Cluster configuration
- Leader election state
- Worker pool assignments
- Gossip key rotation state

**Not backed up** (ephemeral, reconstructed on restart):
- Application blueprints (re-deployed from repositories)
- Runtime metrics and invocation traces

## Enabling Backups

### Configuration (aether.toml)

```toml
[backup]
enabled = true
interval = "5m"
path = "/data/backups"
remote = ""
```

| Field | Default | Description |
|-------|---------|-------------|
| `enabled` | `false` | Enable/disable backup |
| `interval` | `"5m"` | Backup interval |
| `path` | env-dependent | Backup directory |
| `remote` | `""` | Git remote URL for offsite backup |

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

## Manual Backup

### Via CLI
```bash
aether backups trigger
```

### Via API
```bash
curl -X POST http://localhost:8080/api/backups
```

## Listing Backups

### Via CLI
```bash
aether backups list
```

### Via API
```bash
curl http://localhost:8080/api/backups
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
```bash
aether backups restore <commit-id>
```

## Inspecting Backup History

Since backups are stored in git:
```bash
cd /data/backups
git log --oneline          # List all backups
git diff HEAD~1            # See what changed in last backup
git show HEAD:state.toml   # View current backup content
cat state.toml             # Human-readable TOML
```

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Backup fails | No write permission on backup dir | Check directory permissions |
| Push fails | Invalid remote or credentials | Verify remote URL and SSH keys |
| Restore fails | Cluster still active | Stop all nodes before restoring |
| Empty backup | KV-Store has no entries | Normal for fresh cluster |
| `BOOT FUTURE-HISTORY` WARN after an intentional reset | Node kept its old `[backup] path` across the reset | Stop the node, clear its backup directory, restart — see "Intentionally resetting a cluster" above |
