### Fixed (2026-10-08 — #2058: a `[cloud]` section whose integration cannot be created no longer boots the node without it)
- **A node whose config has a `[cloud]` section now REFUSES to start when that section's integration cannot be created.** `Main#resolveEnvironment` used to log
  `Failed to create cloud environment` and continue with no integration, so the leader could not provision, replace or scale until the first incident. Now: exit code **69**
  (`EX_UNAVAILABLE`; 65 is the refused config file, 78 the refused identity) and a `FATAL: refusing to start: the [cloud] section names provider '<p>' but its integration could
  not be created: <cause>. The provider's [cloud.credentials] must provide: <keys>` line on stderr and in the log. No `[cloud]` section is unchanged; a `docker` section cannot fail.
- **WARNING for operators of aws, gcp and azure sources, and of a hetzner source without `credentials`:** their nodes already had no working integration (the bootstrap overlay
  renders only `api_token`, while the aws, gcp and azure factories require `access_key_id`/`secret_access_key`/`region`, `project_id`/`service_account_email`/`private_key_pem`/`zone`
  and `tenant_id`/`client_id`/`client_secret`/`subscription_id`), and booted regardless. They now stop at boot. Until the overlay renders those keys (tracked separately by the
  maintainers), put them under `[source.<name>.node_config.cloud.credentials]`. Supervisors must not restart on 69 unchanged (`node-operations.md#exit-codes`).
  `[verified: MainCloudIntegrationBootTest (child JVM; the two refusal tests go red when the refusal is replaced by the old log-and-continue)]`
