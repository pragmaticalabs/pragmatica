### Fixed (2026-09-28 — #1650: core-provisioned cloud workers all came up in source `default`)
- **Provisioned nodes booted with the provisioning host's `AETHER_SOURCE`/`AETHER_ZONE`, not their own.** Cloud-init
  (and the CLI's SSH re-launch, and the Docker provider) copied both from the rendering process's env. Workers are
  rendered on the leader, where `AETHER_SOURCE` is usually unset or names the leader's own source, and a node learns
  its source only from that variable (`Main` → the SWIM `source` label → its community). So every core-provisioned
  worker joined as `default` (or as the leader's source), and a multi-source cloud cluster collapsed into one
  community.
- Every path now stamps the node's own values: `NodeUserDataRenderer` (container and JVM env file) from the source it
  renders, the CLI re-launch builders from the source being deployed, and `DockerComputeProvider` from the provision
  context and resolved zone. `ClusterIdentityEnv.NODE_OWN_VARS` names the per-node variables a provider must never copy
  from its host.
- `AETHER_ZONE` is stamped only when the landing zone is known at render time, i.e. a source with a single zone. A
  source listing several `zones` is rotated through after rendering, so the variable is left absent rather than guessed
  or inherited. Community placement matches on the provider-observed zone, not this label, so it is unaffected.
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerWorkerReconcileTest.java`
  — two cloud worker sources, each worker's user-data carries its own source and zone]
