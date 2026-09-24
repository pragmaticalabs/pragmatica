### Fixed (2026-09-24 — #1487: aether cluster bootstrap --cluster <name> was not persisted, so replacements carried the TOML's [cluster] name)
- **`--cluster <name>` renamed only the bootstrap VMs.** The override reached the parsed config, but
  formation POSTed the operator's raw TOML, still holding the file's `[cluster] name`, to
  `/api/v1/cluster/config`. CTM reads the cluster name from that persisted TOML, so every replacement
  was labelled `aether-cluster=<TOML name>`, CTM inventory filters used that name too, and reaping or
  teardown scoped to the override missed the replacements.
- The override is now also written into the raw TOML's `[cluster] name` line before bootstrap starts, so
  the TOML that formation POSTs carries the same name as the seeds. Only that one line is replaced
  (including any trailing comment on it); the rest of the operator TOML, including unresolved
  `${env:...}` references, is posted verbatim. With no `--cluster` (or a blank one) the TOML is posted
  unchanged. The override is validated against the cluster-name pattern, and the rewritten TOML is
  re-parsed: unless its `[cluster] name` then equals the override, `--cluster` fails before anything
  is provisioned or applied instead of persisting a name the operator did not choose. This refuses
  TOMLs that parse but that the line edit cannot handle: a root dotted `cluster.name = ...`, a quoted
  `"name" = ...`, a multi-line string inside `[cluster]` holding a line that starts `name =`. The
  recovery is to write the name as a plain `name = "..."` line under `[cluster]`, or to drop `--cluster`
  and set the name in the file.
  [verified: `aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/ClusterNameOverridePersistenceTest.java`
  (unit level: the TOML and POST body the CLI builds, not a live cluster)]
- **`aether cluster apply --cluster <name>` now applies the same rewrite.** `apply` already took
  `--cluster` to choose the target cluster. It now also rewrites the file's `[cluster] name` to that
  value before the config is sent (plain `apply`) or parsed and compared (`--resume`, `--rollback`).
  Without the rewrite, a cluster bootstrapped with `--cluster X` from a file named `Y` refused every
  later `apply` of that file with "cluster.name is immutable", because the stored name is now `X`.
  Without `--cluster`, `apply` sends the file unchanged, so a file whose name differs from the stored
  one is still refused. The recovery is to pass `--cluster <stored name>`.
  [verified: `aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/ClusterNameOverridePersistenceTest.java`
  (unit level: the CLI-side immutable-field check that `--resume`/`--rollback` run, not a live cluster)]
  [mechanism: plain `apply` POSTs the rewritten TOML, and `ClusterConfigRoutes.executeDiff` runs the same
  `ClusterBootstrapConfigDiff.diff` whose immutable-field result the test checks]
- That replacements are now labelled with the override follows from CTM resolving the name out of the
  persisted config. [mechanism: `ClusterConfigRoutes` stores the POSTed TOML; CTM passes the resolved
  name through `ProvisionContext.forReplacement` to `HetznerComputeProvider.buildLabels`]
  [design intent — unverified: no cloud run of a replacement under `--cluster` has been made]
- **The integration harness now matches replacements by the persisted name.** Its cloud membership and drain checks matched CTM replacements as `aether-cloud-<cluster>-node-*`. That pattern only worked because the TOML name happened to be `cloud-` plus the harness name, and it never matched on the jvm runtime (`aether-cloud-cloud-test-b-jvm-node-*`). It is now `aether-<cluster>-node-*`, the node id CTM derives from the persisted cluster name. [verified: `aether/tests/integration/test/test-chaos-harness.sh` R2-R3 — restoring the old pattern reddens both]
