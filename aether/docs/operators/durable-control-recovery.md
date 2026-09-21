# Durable control state recovery

Configure an absolute `cluster.consensus_path`, or an explicit artifacts storage path from which
the node derives a sibling `aether-control` directory. The node refuses startup without durable
control configuration; it never silently starts a new producer epoch under the working directory.
Preserve this directory across process restart, including workers' producer epoch files.

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
