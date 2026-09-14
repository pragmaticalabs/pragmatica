### Fixed (2026-09-14 — #904: a secret-resolution failure for a configured resource provider booted the node degraded instead of refusing to start)
- **`AetherNode.createResourceProviderFacade` logged `Failed to resolve secrets in configuration` and
  then returned `noOpResourceProviderFacade()` with an empty `nodeComposite`.** The node booted with
  resource provisioning silently disabled: every `provide(...)` failed with `Resource provisioning
  not configured` and every `ConfigurationSection` slice deployed to it failed at load with
  `AbsentCompositeConfigFacade`'s "no configuration composite" cause, which can only guess between
  "no provider configured" and "secret resolution failed at boot". The operator had configured the
  provider; the node ran without it. Same shape as #888 and the #830 rule: a configured capability
  that cannot be built is a boot failure naming its cause, never a degraded boot
  [mechanism: `ConfigurationProvider.withSecretResolution` fails with
  `ConfigError.SecretResolutionFailed`; the failure arm of the `fold` built the no-op setup].
- `createResourceProviderFacade` now returns `Result<ResourceProviderSetup>`; the failure arm returns a
  cause reading `configured resource provider cannot be built: Failed to resolve secret '${secrets:<path>}'
  in config key '<key>': <underlying> — refusing to boot …`, with the `SecretResolutionFailed` carried as
  `source()`. `assembleNode` propagates it exactly like the `[storage.*]` refusals (`baseStorageSetupsResult`),
  so `AetherNode.aetherNode(...)` fails and nothing is bound or started on that path
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/AetherNodeSecretResolutionRefusalBootTest.java`
  `aetherNode_refusesBoot_whenConfiguredProviderSecretsCannotBeResolved` — boots the real path with a
  `SecretsProvider` that fails; asserts the failure names the config key and the secret path, does not
  say `not configured`, and carries `SecretResolutionFailed`; red at the base with "booted with provisioning
  silently disabled", red again when the production hunk alone is reverted].
- **The sibling arm: a `${secrets:<path>}` placeholder in the configured provider with NO `SecretsProvider`
  at all now refuses boot too.** Before, `createResourceProviderFacade` returned the provider as-is and the
  placeholder text was served as the value (`ConfigService.getString("database.password")` answered
  `${secrets:vault/db/password}`); the failure surfaced later as an auth error naming nothing about secrets.
  Reachable in production through `Main.resolveEnvironment`'s log-and-`Option.none()` on a failed cloud
  factory, and through any node.toml without a `[cloud]` block. The same rule
  `NoSecretsProviderForStorageEncryption` already applies to `[storage.encryption]`, now applied to the
  config map [mechanism: `ConfigurationProvider.withoutSecretResolution` — the no-resolver counterpart of
  `withSecretResolution`, same `SECRET_PATTERN` — returns the provider itself when no value carries a
  placeholder and `ConfigError.SecretResolutionFailed(key, path, "no secrets provider configured to resolve
  it")` otherwise; the failure rides the same `secretResolutionRefused` channel as above, so `source()` is
  `SecretResolutionFailed` in both arms]
  [verified: `AetherNodeSecretResolutionRefusalBootTest#aetherNode_refusesBoot_whenPlaceholderPresentAndNoSecretsProviderConfigured`
  — real boot with `environment` absent (Main's shape) and one placeholder; asserts refusal naming the key,
  the path and "no secrets provider", carrying `SecretResolutionFailed`; red before the fix with "booted
  with the placeholder passed through as the literal value"; red again with the arm-2 wiring alone reverted
  and again with the scan alone neutered]
  [verified: `SecretResolvingConfigurationProviderTest.NoResolver` — placeholder-free provider is returned
  as the SAME instance; a placeholder fails naming key and path].
- Unchanged, and the control for the arm above: no placeholder and no `SecretsProvider` still boots with the
  literal values served as-is — the shape every Ember/Forge boot takes (`EnvironmentIntegration.withCompute`
  has `secrets = empty()`) [verified: `AetherNodeSecretResolutionRefusalBootTest#aetherNode_boots_whenNoSecretsProviderAndNoPlaceholder`].
  Tracked `aether/` and `examples/` fixtures were swept for `${secrets:` (`git grep -F`, 48 files, all
  Java/Markdown plus one slice-level `resources.toml` comment line in `examples/notification-hub`); the only
  boot-test placeholders sit in the typed `StorageEncryptionConfig`, which is not part of the config map.
- `EnvSecretsProvider.resolveSecret`'s failure now names the variable the operator has to set:
  `Secret resolution failed for 'vault/db/password': environment variable AETHER_SECRET_VAULT_DB_PASSWORD is
  not set` [verified: `EnvSecretsProviderTest.ResolutionTests#resolveSecret_missingEnvVar_namesTheEnvVarToSet`,
  red with the name dropped from the message].
- Unchanged: no `configProvider` at all still boots on the no-op facade — that is the unconfigured case,
  and after this change it is the only way a booted node reaches `Resource provisioning not configured`.
  `AetherNodeResourceFacadeSeamTest` unwraps the new `Result`; its assertion is untouched.
- Not changed / known: `SecretResolvingConfigurationProvider.fetchSecret` `.await()`s the resolver with no
  timeout, so a secrets backend that never answers hangs boot rather than refusing — the third outcome
  beside refuse and boot; the shipped Hetzner chain (`EnvSecretsProvider`, an env lookup) cannot hang
  `[unverified: not induced; to be filed separately]`.
- Not changed / known: a refused boot inside an in-JVM host (Ember/Forge) leaves what `createNode` built
  before `assembleNode` (KV store, persistence, `RabiaNode`, the storage tiers) unreleased — identical to the
  two pre-existing `[storage.*]` refusals; production `Main` exits on the failed `Result`
  `[unverified: not measured]`.
- Not changed / known: the slice-intrinsic `resources.toml` layer with no resolver still passes
  `${secrets:…}` through literally (`SliceStore.resolveIntrinsicSecrets`'s no-resolver arm) — the same shape
  one layer down, outside this ticket's node.toml scope `[unverified: not run]`.
