### Fixed (2026-09-29 — #1503: any firewall labelled aether-cluster=<name> disabled auto-heal provisioning for that cluster)
- **The Hetzner provider counted every firewall carrying the cluster label as evidence of a firewall-selector miss.**
  When a replacement's source selector found no firewall, `HetznerComputeProvider` looked cluster-wide and failed
  closed (`FirewallSelectorMissed`, #444) if anything turned up. So one non-ingress firewall carrying
  `aether-cluster=<name>` made every replacement fail and disabled auto-heal for the whole cluster. Examples: the
  test harness's partition firewalls (#1500), or one an operator labelled by hand.
  Only ingress firewalls now count: those Aether creates for a source, which also carry `aether-source`. A
  cluster with only non-ingress firewalls is treated as having unmanaged ingress, and the provision proceeds with the
  existing loud WARN. A genuine selector miss still fails closed, and its message counts only ingress firewalls.
  [mechanism: `unmanagedOrMissed` filters on the `aether-source` label; pinned by
  `HetznerComputeProviderTest.createFrom_whenClusterHasOnlyNonIngressFirewalls_createsUnfirewalled` and the control
  `createFrom_whenIngressFirewallOfAnotherSourceExistsBesideNonIngress_refusesToCreate`, both red on the pre-fix
  code. The existing refusal test's fixture now carries the labels a real ingress firewall has.]
