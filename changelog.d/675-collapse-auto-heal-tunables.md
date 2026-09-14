### Changed (2026-09-14 — #675: three config surfaces parsed auto-heal knobs that nothing read)
- **Decision: one live surface — the node config — and every key that cannot reach it is refused, not
  discarded.** The runtime reads exactly four `AutoHealConfig` fields: `startupCooldown` (the formation-check
  delay in `ClusterTopologyManagerRecord.activateWithFormation`), `provisioningTimeout` (the reap backstop),
  `swimHintsTtl` (`MembershipFsm`) and `maxNodes` (#298). The other five it carried — `retryInterval`,
  `staleObservationTtl`, `quicMissPromotionThreshold`, `provisionStabilityWindow`, `decommissionedRetention` —
  had zero production reads (`git grep '\.<accessor>()'` outside the three parse/record files) and are gone,
  together with the eight-overload factory ladder; the 24h-vs-60s `decommissioned_retention` default
  disagreement disappears with the field. Wiring a knob nobody reads is not an option, so "wire" applied to
  exactly one key (below) and "delete + refuse" to the rest.
- **`[operations.auto_heal]` (cluster TOML) accepts `enabled` and nothing else.** Its eight tunables parsed into
  `AutoHealSpec` and reached no node — the CLI never rendered them into the composed per-node `aether.toml`
  and `Main.resolveAutoHeal` never read them. `ClusterBootstrapConfigParser` now refuses any of them with
  **PF-26**, naming the key and the ticket, the way PF-25 refuses `enabled = false`; `AutoHealSpec` is
  `enabled`-only; the `aether cluster init` scaffold no longer suggests `retry_interval`/`startup_cooldown`.
  The eight `aether/tests/integration/env/*.toml` cluster files declared `retry_interval`, `startup_cooldown`,
  `provision_stability_window = "5s"` and `provisioning_timeout` as "integration-test tuning" — none of it
  ever took effect, so removing the lines changes no run; they would otherwise fail PF-26 at bootstrap.
  [verified: `ClusterBootstrapConfigParserTest$AutoHealTunablesRefused#parse_autoHealTunable_isRefused_namingTheKey`
  — each of the eight keys under `[operations.auto_heal] enabled = true` fails parse with `PF-26`, the key and
  `#675` (red at the base: `retry_interval` parsed cleanly); `#parse_autoHealEnabledOnly_stillParses`]
- **`[timeouts.scaling] auto_heal_startup_cooldown` now reaches the runtime.** It parsed into `TimeoutsConfig`
  and stopped there; `Main.resolveAutoHeal` built the node's `AutoHealConfig` from `DEFAULT` plus
  `[cluster] max_nodes` only. It is the one auto-heal timing the runtime honours, so it is carried through
  `AutoHealConfig.withStartupCooldown` alongside the cap. [verified:
  `MainAutoHealResolutionTest#startupCooldown_reachesTheRuntimeConfig` — `auto_heal_startup_cooldown = "42s"`
  resolves to `TimeSpan(42S)` (red at the base: `but was: TimeSpan(15S)`); `#startupCooldown_defaultsWhenAbsent_andMaxNodesStillCarried`]
- **`[timeouts.scaling] auto_heal_retry` is removed from the node config.** Nothing read it. It is not refused:
  `aether.toml` has no unknown-key gate anywhere (`ConfigLoader` is total by design, validation runs at boot
  per section), so a leftover line is ignored exactly like any other unknown key — stated in
  `timeout-configuration.md` rather than special-cased. [mechanism: `TimeoutsConfig.ScalingTimeouts` and
  `ConfigLoader.parseScalingTimeouts` no longer carry it]
- Docs corrected to the decision: `bootstrap-config.md` (field table, trap note), `timeout-configuration.md`,
  `cluster-bootstrap-spec.md` §7.2, `cluster-init-wizard-spec.md`, `integration-test-overhaul-v2-spec.md`.
  `cluster-management-spec.md`'s `[cluster.auto_heal]` (with `retry_interval`) is a different, unimplemented
  schema (the `aether/tests/cloud` shell driver's TOML) and is untouched.
- [unverified: the wired cooldown's runtime effect — that a 42s value delays the leader's first formation
  check by 42s — is pinned at the resolver, not in a cluster run; `provisioningTimeout` and `swimHintsTtl`
  remain fixed defaults (60s, 15s) with no config key, which is the pre-existing state, now documented.]
