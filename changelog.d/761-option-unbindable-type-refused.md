### Fixed (2026-09-14 — #761: the config binder returned `none()` or a mistyped value for an `Option<X>` it could not bind; now a named refusal, with the enumeration that licensed it kept as a gate)
- **Enumeration first, as the ticket ruled.** Every record `ProviderBasedConfigService` is handed in production was
  walked reflectively — the `ResourceFactory.configType()`s the SPI provider binds (23 factories in source; the 20
  on the gate's classpath, discovered through the same `ServiceLoader` `SpiResourceProvider` uses, plus the three
  jooq factories it cannot see, which all bind `DatabaseConnectorConfig`, walked as an explicit root), the three
  records `NodeDeploymentState` binds (`TopicConfig`, `ScheduleConfig`, `StreamConfig`) and `DatabaseConnectorConfig`
  from `DatasourceConnectionProvider`; slice config records never reach this binder (`FactoryClassGenerator` emits
  per-component `ConfigFacade.get*` calls). Result: **25 records, 26 `Option<X>` components, case (a) nested-generic
  = 0, case (b) unsupported class = 0** — every X is a primitive, enum or record. The blast radius of a refusal is
  therefore empty today
  [mechanism: `aether/dead-surface-gate/src/test/java/org/pragmatica/aether/deadsurface/OptionBindingSupportGateTest.java`
  is that walk, kept enabled: it asserts the unsupported set is empty and that the walked records and their
  `Option<X>` components equal an EXACT named set (the 25 records and 26 components), so a record vanishing from
  the classpath — a lost `ServiceLoader` registration, which a count floor let pass with half the corpus gone — or
  an `Option` component appearing reddens it naming the record or component. Validated by stripping
  `NotificationSenderFactory`'s registration from the installed jar (red naming the five notification records),
  adding `Option<String>` to `ScheduleConfig` (red naming it as unexpected) and adding `Option<URI>` (red naming it
  as unsupported)].
- **All three silent arms of `extractOptionValue` now refuse.** (a) a raw `Option` or a nested generic
  (`Option<List<String>>`) used to receive `provider.getString(fullKey)` — an `Option<String>` in a slot of another
  type, a live type error at first use (the red pin at the base bound `Option<List<String>>` successfully to a
  string); (b) an inner class that is neither primitive, enum nor record used to bind `none()`, indistinguishable
  from an absent key. All now yield the new `ConfigError.UnsupportedType(key, declaredType)`, present key or not.
- **The refusal survives the binder's fallbacks.** `collectComponentAt` swallows any extraction failure into the
  record's `DEFAULT` instance, then `SectionNotFound` (its derived-name fallback is `String`-only and never reached
  by an `Option` component) — so a refusal alone would have become a silent default on any record carrying
  `DEFAULT`. `UnsupportedType` is now propagated past those fallbacks; a declaration error is not satisfiable by a
  default
  [verified: `integrations/config/config-service/src/test/java/org/pragmatica/config/ProviderBasedConfigServiceUnsupportedOptionTest.java`
  — raw `Option`, nested generic, unsupported class absent and present, and a `DEFAULT`-carrying record all fail
  with `UnsupportedType` naming the key (and type where one is declared); `Option<String>` absent→`none()`,
  present→`some` as controls. Restoring the raw arm's `getString` reddens the raw test alone; restoring the
  nested-generic arm reddens that test alone; restoring the old `none()` arm reddens the three case-(b) tests;
  removing the fallback bypass makes the `DEFAULT` case bind successfully (the silent default) and reddens five].
- **Where the refusal surfaces.** The binder runs at slice ACTIVATION, not node boot: `UnsupportedType` reaches
  `SpiResourceProvider.loadConfig` as `SliceLoadingFailure.Fatal.ConfigurationFailed` → the slice transitions to
  PERMANENT `FAILED` and a `DeploymentFailed` event at WARNING names the key and declared type. The node stays up;
  `Main.abortBoot` never sees it, by design — one slice's declaration error must not kill the node.
- Not changed: value-level readers (`get*`, #1098), the bare (non-`Option`) unsupported-component path, which
  still falls through the same fallbacks (drafted as a follow-up in the fix report), and any record that binds
  today — the gate is what says so.
