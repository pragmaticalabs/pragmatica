### Fixed (2026-10-07 — #2007: the aether-node image had no git, so `[backup]` could not back up or restore)
- **`[backup]` shells out to `git`, and the container image installed only `iptables` and `wget`.** A container node with
  `[backup]` enabled logged "git could not be run", the restore decision stayed BLOCKED, and cluster-state writes were
  refused. The image now installs `git` (`--no-install-recommends`), and JVM-mode hosts get it from the rendered user data
  and the VM snapshot builder.
- **A node with `[backup]` enabled now refuses to boot when `git --version` cannot run**, with a message that names `[backup]`
  and git, instead of failing later as a blocked restore. A node without `[backup]` is not probed.
- **The image also creates `/data/backups` owned by `aether`**, so a named volume mounted there inherits that ownership. A
  root-owned mount point made the backup repository unreadable (`AccessDenied`) with the same BLOCKED symptom.
