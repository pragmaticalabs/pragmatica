### Fixed (2026-09-29 — #1695: the `cluster apply` wave rollout provisioned cloud nodes with no rendered identity, so they could not join)
- **Scope:** the client-side wave rollout (`WaveExecutor`), which is entered only through
  `aether cluster apply --resume`/`--rollback`. **That rollout is dormant in rc4 (#686):** plain `apply` performs
  scale-only writes and never reaches it, and `--resume`/`--rollback` abort without an apply-state file. These fixes
  harden that dormant path.
- **Every cloud node the wave rollout minted booted unable to join.** This covered scale-up, add-role, rolling
  reprovision and replace-before-retire. `WaveExecutor` handed the provider a
  spec with NO user-data: no `AETHER_CLUSTER_NAME`, secret, role, source, zone or peers, and no runtime install. So
  the VM booted, billed, and never joined.
  CLOUD nodes are now provisioned through the composition the leader's auto-heal uses
  (`ReplacementNodeConfigComposer`, `SourceCloudBindings.resolveOverlayFromConfig`, `NodeUserDataRenderer`). The one
  exception is `ssh_key_ids`, which are empty on apply, so a node minted here that later becomes leader falls back to
  the provider's by-name key lookup (#1724). The inputs are:
  - the cluster secret from this machine's bootstrap state (else `AETHER_CLUSTER_SECRET`);
  - the live core peers from `GET /api/v1/nodes/live`.
  Either missing refuses the provision with a message naming it, instead of creating a node that cannot join.
- **A wave rollout that fails part-way destroys, best-effort, the cloud VMs of the failing step, and names every VM
  it created.** The desired configuration is persisted only on success, so the failing step's VMs belong to no desired
  state. A VM whose destroy failed is named as STILL RUNNING AND BILLED, with provider, server id and removal steps.
  VMs of earlier completed steps are kept (they belong to the desired configuration) and are listed too, because the
  rollout records none of them: a retry mints new ids and `--rollback` does not see them. Only VMs a cloud provider created are recorded, destroyed or
  listed. SSH hosts (the operator's own), Forge placeholders and local Docker containers never are, and no terminate
  call is ever made for them.
- **A zoneless source no longer asks for a location literally named `default`.** The zone is passed as an optional
  placement and is omitted when the source names none.
- [mechanism: `WaveExecutor.provisionCloudNodes` builds each spec through `WaveNodeProvisioning.cloudProvisionSpec`.
  Pinned by `WaveExecutorCloudProvisioningTest`, which drives that entry point against a provider that captures
  every spec: rendered user-data (cluster, node id, role, source, peers, secret), zone placement present and absent,
  and live-core peer selection]
- `[unverified: no live cloud apply was run; the join itself is not exercised]`
