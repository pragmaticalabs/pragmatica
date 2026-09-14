### Fixed (2026-09-14 — #761: the config binder returned `none()` or a mistyped value for an `Option<X>` it could not bind; now a named refusal, with the enumeration that licensed it kept as a gate)
- **Enumeration first, as the ticket ruled.** Every record `ProviderBasedConfigService` is handed in production was
  walked reflectively — the 21 `ResourceFactory.configType()`s the SPI provider binds (discovered through the same
  `ServiceLoader` `SpiResourceProvider` uses), the three records `NodeDeploymentState` binds (`TopicConfig`,
  `ScheduleConfig`, `StreamConfig`) and `DatabaseConnectorConfig` from `DatasourceConnectionProvider`; slice
  config records never reach this binder (`FactoryClassGenerator` emits per-component `ConfigFacade.get*`
  calls). Result: **25 records, 26 `Option<X>` components, case (a) nested-generic = 0, case (b) unsupported
  class = 0** — every X is a primitive, enum or record. The blast radius of a refusal is therefore empty today
  [mechanism: `aether/dead-surface-gate/src/test/java/org/pragmatica/aether/deadsurface/OptionBindingSupportGateTest.java`
  is that walk, kept enabled: it asserts the unsupported set is empty, guards its own corpus (≥12 factory roots,
  ≥20 Option components) and was validated by adding a probe record with both cases, which it named].
- **Both silent arms of `extractOptionValue` now refuse.** (a) a raw `Option` or a nested generic
  (`Option<List<String>>`) used to receive `provider.getString(fullKey)` — an `Option<String>` in a slot of another
  type, a live type error at first use (the red pin at the base bound `Option<List<String>>` successfully to a
  string); (b) an inner class that is neither primitive, enum nor record used to bind `none()`, indistinguishable
  from an absent key. Both now yield the new `ConfigError.UnsupportedType(key, declaredType)`, present key or not.
- **The refusal survives the binder's fallbacks.** `collectComponentAt` swallows any extraction failure into a
  derived name, then the record's `DEFAULT` instance, then `SectionNotFound` — so a refusal alone would have
  become a silent default on any record carrying `DEFAULT`. `UnsupportedType` is now propagated past those
  fallbacks; a declaration error is not satisfiable by a default
  [verified: `integrations/config/config-service/src/test/java/org/pragmatica/config/ProviderBasedConfigServiceUnsupportedOptionTest.java`
  — nested generic, unsupported class absent and present, and a `DEFAULT`-carrying record all fail with
  `UnsupportedType` naming the key and type; `Option<String>` absent→`none()`, present→`some` as controls.
  Removing the fallback bypass makes the `DEFAULT` case bind successfully (the silent default) and reddens
  three tests; restoring the old `none()` arm reddens the three case-(b) tests only].
- Not changed: value-level readers (`get*`, #1098), the bare (non-`Option`) unsupported-component path, which
  still falls through the same fallbacks (drafted as a follow-up in the fix report), and any record that binds
  today — the gate is what says so.
