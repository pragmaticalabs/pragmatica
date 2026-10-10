### Fixed (2026-10-08 — #2059: aws, gcp and azure cloud sources composed a node config their integration could never be created from)
- **`BootstrapOverlayGenerator` rendered only `[cloud.credentials] api_token`**, so the aws, gcp and azure `*EnvironmentIntegrationFactory.validateCredentials` found
  their keys missing; since #2058 made integration creation fail closed, such a node refused to boot. A hetzner source without `credentials` failed the same way.
  The overlay now renders the source's full credential map (`CloudCredentialSchema.credentials`): `[source.<name>.node_config.cloud.credentials]` overlaid with the
  scalar `credentials` (hetzner `api_token` only) and the source location (`region` for aws, `zone` for gcp, never inferred from `zones`, and `location` from `region` for azure).
- **The CLI's credential field names now match the factories'.** The old scalar mapping wrote `api_token`, `access_key` and `credentials_file` for every provider; none of
  `access_key` or `credentials_file` is read by any factory. `SourceCloudBindings`, the overlay and `ProviderResolver` share one derivation.
- **A missing key is refused before any node is provisioned, naming the key.** `CloudCredentialSchema` lists the keys per provider (hetzner `api_token`; aws `access_key_id`,
  `secret_access_key`, `region`; gcp `project_id`, `service_account_email`, `private_key_pem`, `zone`; azure `tenant_id`, `client_id`, `client_secret`, `subscription_id`,
  `resource_group`, `location` — azure's `resource_group` is read by the factory though the issue omitted it). It is enforced by validate (`PF-28`), `NodeConfigBuilder.compose`
  and `ReplacementNodeConfigComposer.compose`.
  [verified: `aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/CloudCredentialComposeTest.java` composes through the real parser and composers, reads the result with the
  real `ConfigLoader`, and creates the integration through the real factories; `CloudCredentialSchemaTest` pins the key lists against those factories in both directions]
- **Behaviour change:** an aws, gcp or azure source with only a scalar `credentials` no longer carries it anywhere (one string cannot be several keys); supply the keys under `node_config.cloud.credentials`.
  [unverified: no live cloud account was used; creation was exercised offline]
- **`cluster init` carries the operator's answers through.** The wizard asks one env-var question per credential key (aws, gcp and azure need several), `--credential-env <key>=<ENV_VAR>` does the same in batch, and both reach the generated `${env:...}` references; the answer used to be asked for and dropped. gcp asks for its zone (`--zone`) and ships no default: a `<region>-a` default named a zone that does not exist for some regions and passed validation.
