# Rolling Cluster Upgrade

This guide covers upgrading an Aether cluster with zero downtime using the rolling upgrade script.

## Prerequisites

- `curl` and `jq` installed
- Network access to the cluster's Management API
- New version binaries available (or a method to restart nodes with the new version)

## Quick Start

```bash
./aether/script/rolling-aether-upgrade.sh \
  --cluster localhost:8081 \
  --version 0.20.0
```

## How It Works

The script upgrades one node at a time through the Management API:

1. **Drain** — evacuates slices from the node (`POST /api/nodes/drain/{id}`)
2. **Wait** — until the node reaches DECOMMISSIONED state
3. **Shutdown** — initiates graceful shutdown (`POST /api/nodes/shutdown/{id}`)
4. **Restart** — prompts the operator to restart the node with the new binary
5. **Ready** — waits for the node to rejoin the cluster
6. **Activate** — puts the node back on duty (`POST /api/nodes/activate/{id}`)
7. **Canary** — observes the node for a configurable period to verify health

If any node fails its canary check, the upgrade halts. The cluster remains in a valid mixed-version state (envelope versioning handles version compatibility).

> **Note — restart here means operator-orchestrated, not runtime auto-restart.** The "Restart"
> step above is a deliberate, drain-gated action you perform with the new binary. It is NOT the
> same as a container/process runtime auto-restarting a *crashed* node. Aether uses a
> terminal-removal membership model (a dead NodeId never returns under the same identity;
> crash recovery is a new-ULID replacement minted by auto-heal), so runtime auto-restart
> (`restart: unless-stopped`/`always`, systemd `Restart=on-failure`/`always`) **must stay
> disabled** on aether-node. See [`../operator/deployment-recovery.md`](../operator/deployment-recovery.md).

## Options

| Option | Description | Default |
|--------|-------------|---------|
| `--cluster <host:port>` | Management API endpoint | *required* |
| `--version <version>` | Target version | *required* |
| `--canary-wait <seconds>` | Health observation window per node | 30 |
| `--api-key <key>` | RBAC authentication key | none |
| `--dry-run` | Show plan without executing | false |
| `--skip-download` | Assume binaries already staged | false |

## Dry Run

Preview the upgrade plan without making changes:

```bash
./rolling-aether-upgrade.sh --cluster localhost:8081 --version 0.20.0 --dry-run
```

## Container-Based Upgrades

For container deployments, use `--skip-download` and restart containers with the new image:

```bash
./rolling-aether-upgrade.sh \
  --cluster localhost:8081 \
  --version 0.20.0 \
  --skip-download
```

When prompted to restart a node, use your container runtime:

```bash
# Docker/Docker
docker stop aether-node-1 && docker run -d --name aether-node-1 ghcr.io/pragmaticalabs/aether-node:0.20.0 ...

# Kubernetes
kubectl set image deployment/aether-node aether-node=ghcr.io/pragmaticalabs/aether-node:0.20.0
```

## Failure Recovery

If the upgrade halts:

1. Check the failed node's logs
2. Fix the issue and restart the node
3. Activate it manually: `curl -X POST http://<cluster>/api/nodes/activate/<id>`
4. Re-run the script — already-upgraded nodes will be processed again (safe, idempotent drain/activate)

## Mixed-Version Clusters

Aether supports mixed-version clusters through envelope versioning. A partially-upgraded cluster is fully functional — there is no urgency to complete a halted upgrade, though it should be resolved to maintain operational simplicity.
