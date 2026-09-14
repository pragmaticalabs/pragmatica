### Changed (2026-09-14 — #675: three config surfaces parsed auto-heal knobs that nothing read)
- **Decision: one live surface — the node config — every live knob settable there, and every key that
  cannot reach it refused, not discarded.** The runtime reads exactly four `AutoHealConfig` fields. The other
  five it carried — `retryInterval`, `staleObservationTtl`, `quicMissPromotionThreshold`,
  `provisionStabilityWindow`, `decommissionedRetention` — had zero production reads (`git grep '\.<accessor>()'`
  outside the three parse/record files) and are gone, together with the eight-overload factory ladder; the
  24h-vs-60s `decommissioned_retention` default disagreement disappears with the field. Per live knob:

  | Knob | Read site | Setting surface before (`2005ea7d2`) | Setting surface after |
  |---|---|---|---|
  | `startupCooldown` | `ClusterTopologyManagerRecord.activateWithFormation` → `checkFormationComplete` (the leader's first formation check) | inert — `AutoHealConfig.DEFAULT` 15s. `[timeouts.scaling] auto_heal_startup_cooldown` parsed into `TimeoutsConfig`, unread; `[operations.auto_heal] startup_cooldown` parsed into `AutoHealSpec`, which had one reader, `.enabled()` | `[timeouts.scaling] auto_heal_startup_cooldown` in the node `aether.toml` (from a cluster TOML: `[source.<name>.node_config.timeouts.scaling]`) |
  | `provisioningTimeout` | `ClusterTopologyManagerRecord` — `recordProvisioningFailure` (circuit backoff window), `scheduleGraceTerminate` (drain grace), `reapRecheckInterval`, `activationReplayGrace` | inert — `AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT` 60s. `[operations.auto_heal] provisioning_timeout` parsed into `AutoHealSpec` (one reader, `.enabled()`) | `[timeouts.scaling] auto_heal_provisioning_timeout` (same path) |
  | `swimHintsTtl` | `AetherNode` → `MembershipFsm.membershipFsm(suspectHintTtlMs, …)` (SUSPECTED-hint decay, #68) and `SwimHintsRegistry.swimHintsRegistry` | inert — `AutoHealConfig.DEFAULT_SWIM_HINTS_TTL` 15s. `[operations.auto_heal] swim_hints_ttl` parsed into `AutoHealSpec` (one reader, `.enabled()`) | `[timeouts.scaling] auto_heal_swim_hints_ttl` (same path) |
  | `maxNodes` | `AetherNode` → `NodeLifecycleManagerRecord.capGuardedProvision` | `[cluster] max_nodes` (node config, #298) | unchanged |

- **`[operations.auto_heal]` (cluster TOML) accepts `enabled` and nothing else.** Its eight tunables parsed into
  `AutoHealSpec` and reached no node — the CLI never rendered them into the composed per-node `aether.toml`
  and `Main.resolveAutoHeal` never read them. `ClusterBootstrapConfigParser` now refuses them with **PF-26**
  the way PF-25 refuses `enabled = false`: one refusal names EVERY stale key in the file, states that they
  never took effect, and for each of the three that named a live timing points at the node key that sets it
  now; `AutoHealSpec` is `enabled`-only; the `aether cluster init` scaffold no longer suggests
  `retry_interval`/`startup_cooldown`. The eight `aether/tests/integration/env/*.toml` cluster files declared
  `retry_interval`, `startup_cooldown`, `provision_stability_window = "5s"` and `provisioning_timeout` as
  "integration-test tuning" — none of it ever took effect, so removing the lines changes no run; they would
  otherwise fail PF-26 at bootstrap.
  [verified: `ClusterBootstrapConfigParserTest$AutoHealTunablesRefused#parse_autoHealTunable_isRefused_namingTheKey`
  — each of the eight keys under `[operations.auto_heal] enabled = true` fails parse with `PF-26`, the key and
  `#675` (red at the base: `retry_interval` parsed cleanly); `#parse_autoHealTunables_areAllNamed_withTheirNodeKeys`
  — `swim_hints_ttl` + `provisioning_timeout` + `retry_interval` in one file are all named, the two live ones
  with `-> [timeouts.scaling] auto_heal_*`, the dead one without (red before round 2: only `retry_interval`
  named, no node key); `#parse_autoHealEnabledOnly_stillParses`]
- **The three `[timeouts.scaling] auto_heal_*` timings reach the runtime.** `auto_heal_startup_cooldown`
  parsed into `TimeoutsConfig` and stopped there — `Main.resolveAutoHeal` built the node's `AutoHealConfig`
  from `DEFAULT` plus `[cluster] max_nodes` only. `auto_heal_provisioning_timeout` and
  `auto_heal_swim_hints_ttl` are new keys (round 2): without them the two knobs ended the PR with a Java
  default as their only surface while PF-26 refused the keys that used to name them. All three are carried
  through `AutoHealConfig.withStartupCooldown` / `withProvisioningTimeout` / `withSwimHintsTtl` alongside the
  cap, and `TimeoutsConfig.ScalingTimeouts` takes their defaults from `AutoHealConfig`'s constants so an
  absent key and the runtime default cannot disagree. [verified:
  `MainAutoHealResolutionTest#startupCooldown_reachesTheRuntimeConfig` — `auto_heal_startup_cooldown = "42s"`
  resolves to `TimeSpan(42S)` (red at the base: `but was: TimeSpan(15S)`);
  `#provisioningTimeout_reachesTheProvisioningBackoff` — a production-wired `ClusterTopologyManager` built from
  the resolved config, tripped by three failed provisions, reports `circuitBreakerState().nextAllowedMs()` 42s
  out (red before the wiring: 60s out, `1789355716810L` against an upper bound of `1789355698810L`);
  `#swimHintsTtl_reachesTheSuspectHintDecay` — `MembershipFsm` built with the resolved TTL keeps the SUSPECTED
  hint past 15s and drops it past 42s (red before the wiring: `Expecting map: {}` at 15s+1);
  `#timings_defaultWhenAbsent_andMaxNodesStillCarried`]
  [unverified: the runtime effect in a cluster run — a 42s cooldown delaying the leader's first formation
  check by 42s, a 42s timeout reaping a drained node at 42s; the pins observe the value at the consumer
  (`circuitBreakerState`, `healthHints`), built from the resolved config the way `AetherNode` wires it, not
  through a booted `AetherNode`]
- **`[timeouts.scaling] auto_heal_retry` is removed from the node config.** Nothing read it. [known: it is
  NOT refused — `aether.toml` has no unknown-key gate anywhere (`ConfigLoader` is total by design, validation
  runs at boot per section), so a leftover `auto_heal_retry` line, or a misspelt `auto_heal_*` key, is ignored
  exactly like any other unknown key; stated in `timeout-configuration.md`. A node-side unknown-key gate is a
  separate ticket.] [mechanism: `TimeoutsConfig.ScalingTimeouts` and `ConfigLoader.parseScalingTimeouts` no
  longer carry it]
- Docs corrected to the decision: `bootstrap-config.md` (field table, example, trap note),
  `timeout-configuration.md` (the three keys with defaults), `cluster-bootstrap-spec.md` §7.2 and KL-7,
  `cluster-init-wizard-spec.md`, `integration-test-overhaul-v2-spec.md`. `cluster-management-spec.md`'s
  `[cluster.auto_heal]` (with `retry_interval`) and `aether/tests/cloud/aether-cloud.toml`'s matching
  section are a different, unimplemented schema — `[cluster.auto_heal]` is not the PF-26 section, so that
  file still bootstraps and its two keys stay a silent no-op; pre-existing, untouched.
