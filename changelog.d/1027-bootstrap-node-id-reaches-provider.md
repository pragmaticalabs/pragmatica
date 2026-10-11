### Fixed (#1027: a node provisioned through `CloudProviderSupport.provisionVia` carried two identities)

- `CloudProviderSupport.provisionVia` (the docker path of `aether cluster apply`/scale waves) named each node `<source>-<role>-<index>` for the
  caller, while the provider, handed no node id, minted a different one for the container (docker: the container name). The same node therefore
  existed under two id schemes, and the caller's label restarted at 0 on every call, so two containers could share it. The caller now mints the id
  (`aether-<cluster>-node-<ulid>`), puts it in the provision context, and records that same string; the docker provider uses it as the container name.
- Docker bootstrap: workers were left to the provider to name (cores already had a planned id from #2089). Every node, workers included, now gets a
  planned id in its provision context, so the provider mints nothing on the bootstrap path. The id the bootstrap RECORDS for a node stays the
  `<source>-<role>-<index>` attribution key that deploy, post and the cleanup ledger parse back; the container's identity travels in the context.
- Docker removal (`aether cluster apply` scale-down, role removal, source removal) addressed `<source>-<role>-<index>` labels that no container is named,
  so it never removed the intended container. It now finds the cluster/source/role containers by label (docker containers carry a new `aether.source`
  label; containers created before this change lack it and are not matched) and removes the newest `count` by node id; fewer matching containers than
  asked is refused and removes nothing. Rolling restart of a docker node restarts the OLDEST container of the source/role (replacements are newer, so the original nodes are walked in turn), draining it at its published management port and destroying it by its own name, instead of addressing the label.
- [verified: `DockerProvisionViaIdentityTest` (real `DockerComputeProvider` with a recording runner), `CloudProviderSupportTest`, `DockerBootstrapProvisionPhaseTest` (including: a docker bootstrap's ledger holds one `ProvisionedVm` per node and destroy recognises the cluster), `DockerBootstrapPeersTest`, `WaveExecutorDockerRemovalTest`, `ProvisionContextTest`]
