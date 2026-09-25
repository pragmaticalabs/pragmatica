# Durable control state recovery

Configure an absolute `cluster.consensus_path`, or an explicit artifacts storage path from which
the node derives a sibling `aether-control` directory. The node refuses startup without durable
control configuration; it never silently starts a new producer epoch under the working directory.
Preserve this directory across process restart, including workers' producer epoch files.

## The path is required, and it must be on persistent storage

A node whose config sets neither `cluster.consensus_path` nor an explicit artifacts storage path
aborts at boot with `FATAL: Durable control storage requires cluster.consensus_path or an explicit
artifacts storage path`. There is no fallback default: a journal silently placed on ephemeral storage
would lose the control state this page protects.

Every shipped configuration sets it (#1519):

| Configuration | `cluster.consensus_path` | Where it persists |
|---|---|---|
| Container image (`/app/aether.toml`) | `/data/aether-control` | The container's own filesystem: it survives `docker restart`, not removal. Mount a volume per node at `/data` for more. |
| `aether/docker/docker-compose.yml`, `docker/scaling-test`, `aether cluster scaffold --template docker-compose` | `/data/aether-control` (from the image) | One named volume per node at `/data`. |
| `aether cluster bootstrap` and auto-heal, cloud and SSH sources | `/var/lib/aether/aether-control` | The VM or host disk. The container bind-mounts `/var/lib/aether` at the same path, so the path also works for JVM-mode nodes. |
| Forge | set per node by the Ember cluster (`EmberCluster.nodeConfiguration`) | The Ember data directory when one is configured, otherwise a directory under `java.io.tmpdir` — development only. |

If you write your own config, set an absolute `cluster.consensus_path` on storage that outlives the
process: in a container, that is a volume or bind mount, never the container's writable layer alone.
Give every node its own directory — never share one between node identities. On a cloud or SSH source,
a `node_config.cluster.consensus_path` override must stay under `/var/lib/aether` for a container
runtime, because that is the only host directory the container mounts.

A malformed counter, a lock marker without a published counter (including an interrupted first
allocation), a checksum failure, or a torn voting journal tail fails startup. This is an intentional
fail-closed availability tradeoff even when the final torn frame was never acknowledged.

Stop the process and preserve the full directory for diagnosis. Do not delete just the lock, reset
the counter, truncate a WAL manually, or combine checkpoint and WAL from different backups.
Restore a verified consistent control-state backup for that identity; if none exists, keep the
identity retired and use the supported replacement path from a surviving quorum with a fresh
node identity. Loss of a core majority has no automatic recovery override. A failed first boot
with no provable published identity may be reprovisioned under a new identity.

The node status `voterReconfiguration` field distinguishes stalled handoff stages and state-transfer
failures. After a committed barrier, restoring connectivity to a successor quorum is required;
there is no timeout rollback to the previous electorate.
