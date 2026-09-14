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
- Unchanged: no `configProvider` at all still boots on the no-op facade — that is the unconfigured case,
  and after this change it is the only way a booted node reaches `Resource provisioning not configured`.
  `AetherNodeResourceFacadeSeamTest` unwraps the new `Result`; its assertion is untouched.
