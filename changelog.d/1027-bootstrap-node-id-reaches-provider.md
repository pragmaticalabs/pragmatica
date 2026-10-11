### Fixed (#1027: a node provisioned through `CloudProviderSupport.provisionVia` carried two identities)

- `CloudProviderSupport.provisionVia` (the docker path of `aether cluster apply`/scale waves) named each node `<source>-<role>-<index>` for the
  caller, while the provider, handed no node id, minted a different one for the container (docker: the container name). The same node therefore
  existed under two id schemes, and the caller's label restarted at 0 on every call, so two containers could share it. The caller now mints the id
  (`aether-<cluster>-node-<ulid>`), puts it in the provision context, and records that same string; the docker provider uses it as the container name.
- The docker bootstrap path had the same split for what it records: `provisionOne` returned the `<source>-<role>-<index>` label while the container
  carried the planned id. `provisionOne` now returns the id the provider was given in the spec's context, and bootstrap hands WORKERS a planned id too
  (cores already had one from #2089), so the provider mints nothing on the bootstrap path.
- Visible effect: the node ids stored in the bootstrap state for a docker cluster are now the container names the cluster knows (`aether-<cluster>-node-...`)
  instead of `<source>-core-N` labels.
- [verified: `DockerProvisionViaIdentityTest` (real `DockerComputeProvider` with a recording runner), `CloudProviderSupportTest`, `DockerBootstrapProvisionPhaseTest`, `DockerBootstrapPeersTest`]
