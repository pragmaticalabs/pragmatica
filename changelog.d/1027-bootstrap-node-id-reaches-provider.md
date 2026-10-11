### Fixed (#1027: a node provisioned through `CloudProviderSupport.provisionVia` carried two identities)

- `CloudProviderSupport.provisionVia` (the docker path of `aether cluster apply`/scale waves) named each node `<source>-<role>-<index>` for the
  caller, while the provider, handed no node id, minted a different one for the container (docker: the container name). The same node therefore
  existed under two id schemes, and the caller's label restarted at 0 on every call, so two containers could share it. The caller now mints the id
  (`aether-<cluster>-node-<ulid>`), puts it in the provision context, and records that same string; the docker provider uses it as the container name.
- Docker bootstrap: workers were left to the provider to name (cores already had a planned id from #2089). Every node, workers included, now gets a
  planned id in its provision context, so the provider mints nothing on the bootstrap path. The id the bootstrap RECORDS for a node stays the
  `<source>-<role>-<index>` attribution key that deploy, post and the cleanup ledger parse back; the container's identity travels in the context.
- [verified: `DockerProvisionViaIdentityTest` (real `DockerComputeProvider` with a recording runner), `CloudProviderSupportTest`, `DockerBootstrapProvisionPhaseTest` (including: a docker bootstrap's ledger holds one `ProvisionedVm` per node and destroy recognises the cluster), `DockerBootstrapPeersTest`]
