### Fixed (2026-09-27 — #1551: a self-bootstrapped cluster could not provision a single node)
- **Core scale-up and auto-heal on a cluster formed without `aether cluster bootstrap` made zero provider
  calls.** Such a cluster's committed cluster config is the BootstrapModule self-seed, whose TOML is empty.
  The capacity-controlled provisioning path added in #1390 asked the source registry for the source's
  account binding, and `binding`, `resolve(source, binding)` and the fleet-inventory pass parsed that empty
  TOML and failed the `config_version` gate ("Persisted config has no config_version … re-bootstrap the
  cluster"), before any provider was reached. Three such failures tripped the provisioning circuit. The
  unbound siblings (`resolve(source)`, `sources`, `isAvailable`) already read an absent or seed config as
  "no operator config, use the local provider"; the bound path did not.
- All of them now take that decision from one helper, `SourceComputeRegistry.operatorConfig` — the registry's
  resolution paths, fleet inventory and reservation, the replacement-provisioning reads in the topology
  manager, community placement, and the config apply route's seed check. With no operator config the bound
  path binds the local provider under the fixed binding `local`. Capacity accounting applies to the
  provisions the ledger itself makes; cores that formed the cluster before the ledger existed (a
  self-bootstrapped fleet) are not counted, so for them the provider's own node-count cap is the only bound.
  Without a local provider the bound path still refuses, naming that condition rather than an absent
  configuration.
- **A seed-era node listed by a later `[source.default]` is adopted, not rejected.** A provision made under
  the seed is bound `local`; when an operator config then declares the same source (the spec's canonical
  `[source.default]`) and its listing includes that node, the reservation moves to the operator binding
  (already counted, so the fleet allocation is unchanged). Previously the node was treated as a duplicate
  identity across sources and every later provision and terminate failed.
- **A reservation cannot land between an operator config replacing the seed and its inventory.** The
  reservation's transaction now witnesses the committed cluster config its inventory check read, so a
  config committed between the check and the apply refuses it; the next attempt inventories the new sources
  first.
- **Fleet inventory is not recorded complete under the seed.** With no operator sources there is nothing to
  inventory, so reservations proceed; but the ledger stays `inventoryComplete=false`, so when an operator
  config later replaces the seed, its sources are inventoried and their existing instances counted before
  any further reservation (a reservation racing that inventory refuses, as at boot). Recording completeness
  under the seed would have skipped that inventory permanently and let the ledger under-count the fleet.
- `SourceComputeRegistryTest` and `CapacityControlledLifecycleTest` pin the seed-config behaviour and the
  seed-to-operator-config inventory; `SeedToOperatorCapacityTest` (adopted from the verifier's probes) pins the
  adoption, the check-to-apply race, the reservation-time inventory check, leader change mid-inventory and
  the ledger's scope. A source ADDED by a later operator config is still not re-inventoried; that
  pre-existing gap is tracked separately and pinned by a tripwire.
