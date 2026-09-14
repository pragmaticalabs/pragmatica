### Fixed (2026-09-14 — #381: runtime config changes never reached a running slice's `notifyConfigUpdate`)
- **Decision: wire, not delete.** The push machinery existed end to end — the slice processor generates
  `notifyConfigUpdate(instance, section, facade)` with per-section parse-and-dispatch, `NodeDeploymentState`
  registers every slice that declares a config-update method and fires the ACTIVATE-time notification, and
  `feature-catalog` row 176 advertises live notification — except for its trigger: `ConfigNotificationManager.notifyChange`
  had zero callers, so a `config set` reached the KV overlay (which every slice facade already reads through)
  and nobody was told. Completing the trigger is ~40 production lines; deleting would have removed a generated,
  documented slice contract. This is not #774 (atomic resource swap): nothing is re-resolved, the slice's own
  callback is invoked with its own facade.
- **The chain.** `DynamicConfigManager` reports each committed `ConfigKey` put/remove it honours (cluster-wide,
  or scoped to this node) to its applied-listeners AFTER updating the overlay provider —
  `AetherNode.collectRouteEntries` registers `NodeDeploymentManager::onConfigChanged`, which dispatches a
  `ConfigChanged(key)` FSM event; `Active` pushes it through `notifyChange(key, this::buildConfigFacade)`; the
  manager invokes the generated method once per (registered slice, declared section) where the section
  prefixes the dotted key, with THAT slice's facade. `notifyChange`'s old shape — one section, one facade for
  every slice — could not have been correct (each slice reads through its own composite), so it now takes the
  key and a per-artifact facade function; registrations carry their sections. Every other FSM state ignores the
  event (slices re-read at the next activation). The dead `lastParsedConfig` map is gone.
  [verified: `ConfigChangePushLivenessTest` (dead-surface gate, bytecode reachability over every module's
  `target/classes`) — red at the base with `notifyChange` unreachable; red again with the one `AetherNode` line
  removed (`onConfigChanged`/`onApplied` unreachable); `ConfigNotificationManagerChangeTest` — two slices with
  sections `database`,`cache` / `database.pool`, key `database.pool.size` reaches both with their own facades,
  `databases.other` and `cache.ttl` reach nobody (red with the prefix test forced true; red with a shared
  facade); `NodeDeploymentFsmTest#active_ConfigChanged_isPushedToTheRegisteredSlice` (red with the `case`
  removed) and `#dormant_ConfigChanged_isIgnored`; `DynamicConfigManagerAppliedListenerTest` — the listener
  sees the overlay already updated for put and remove, and a key scoped to another node is neither applied nor
  reported (red with the put-side notify removed)]
- **Guarantee, precisely** (`guarantees.md` §C4 / row 25, catalog row 176): process-local per node,
  asynchronous on the notification thread, at-most-once per change per (slice, section) — a callback that
  throws is logged, never retried — and only while the node is ACTIVE. The `DeadSurfaceCommissioningTest`
  positive control that asserted `notifyChange` dead is retired (the permanent gate is coupled to its synthetic
  fixture, not to #381, per its own doc).
- [unverified: no boot test deploys a slice with a `@ConfigUpdate` method and issues `config set` — the
  end-to-end path is pinned structurally (reachability) plus three behavioural pins on its three segments,
  not observed on a running node; the generated `notifyConfigUpdate`'s own parse-and-dispatch is unchanged
  and untested here.]
