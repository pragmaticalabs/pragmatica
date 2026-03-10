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

## Manual Backup

### Via CLI
```bash
aether backup trigger
```

### Via API
```bash
curl -X POST http://localhost:8080/api/backup
```

## Listing Backups

### Via CLI
```bash
aether backup list
```

### Via API
```bash
curl http://localhost:8080/api/backups
```

## Recovery from Total Cluster Loss

### Step-by-step:
1. Stop all nodes
2. Ensure `state.toml` is present in the backup directory
3. Start a single node with backup enabled
4. The node will load state from `state.toml` during sync
5. Start remaining nodes — they will sync from the restored node

### Restoring a Specific Backup
```bash
aether backup restore <commit-id>
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
