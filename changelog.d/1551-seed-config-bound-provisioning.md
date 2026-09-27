### Fixed (2026-09-27 — #1551: a self-bootstrapped cluster could not provision a single node)
- **Core scale-up and auto-heal on a cluster formed without `aether cluster bootstrap` made zero provider
  calls.** Such a cluster's committed cluster config is the BootstrapModule self-seed, whose TOML is empty.
  The capacity-controlled provisioning path added in #1390 asked the source registry for the source's
  account binding, and `binding`, `resolve(source, binding)` and the fleet-inventory pass parsed that empty
  TOML and failed the `config_version` gate ("Persisted config has no config_version … re-bootstrap the
  cluster"), before any provider was reached. Three such failures tripped the provisioning circuit. The
  unbound siblings (`resolve(source)`, `sources`, `isAvailable`) already read an absent or seed config as
  "no operator config, use the local provider"; the bound path did not.
- All of them now take that decision from one helper, `SourceComputeRegistry.operatorConfig`. With no
  operator config the bound path binds the local provider under the fixed binding `local`, and capacity
  accounting still applies to local-provider provisions. Without a local provider the bound path still
  refuses.
- **Fleet inventory is not recorded complete under the seed.** With no operator sources there is nothing to
  inventory, so reservations proceed; but the ledger stays `inventoryComplete=false`, so when an operator
  config later replaces the seed, its sources are inventoried and their existing instances counted before
  any further reservation (a reservation racing that inventory refuses, as at boot). Recording completeness
  under the seed would have skipped that inventory permanently and let the ledger under-count the fleet.
- `SourceComputeRegistryTest` and `CapacityControlledLifecycleTest` pin the seed-config behaviour and the
  seed-to-operator-config inventory.
