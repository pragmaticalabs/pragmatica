### Security (2026-09-11 — #1016: the published node image carried a built-in administrative credential)

- **`aether/docker/aether-node/aether.toml` declared an `[app-http.api-keys.*]` table with
  `authorization_role = "ADMIN"`.** `docker/aether-node/Dockerfile` copies that file to
  `/app/aether.toml` through its `ARG CONFIG_PATH` default, and the release workflow builds that
  Dockerfile with no override, so the value shipped inside the published image as its only
  out-of-the-box ADMIN credential — the same one on every install. Docker-compose, Forge and
  direct-image deployments were affected; cloud bootstrap was not, because it composes its own
  runtime TOML via `BootstrapOverlayGenerator`.
- **The shipped configuration now declares no key, and the node is fail-closed by default.**
  `ConfigLoader` leaves `security_mode` at its `API_KEY` default (#290), `AetherNode` installs the
  config validator over an empty key map, and `KvStoreApiKeyValidator` falls through to
  cluster-held keys — so every non-public management route is refused until a credential exists.
  Public routes still answer, which is what the container health check probes.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/api/PublishedImageConfigFailsClosedTest.java`]
- **The test loads the file the image actually ships**, through the real `ConfigLoader`, and drives
  the validator wiring `AetherNode` installs, so re-adding a key to that file reddens it. It carries
  its own controls: that the parse reached the real file, that the Dockerfile copies that file, that
  the route resolves to an ADMIN permission, and that a cluster bootstrap admin key still
  authenticates through the same pipeline.
- **Operators get a credential from one of two places, neither baked into an image.** The cluster
  bootstrap admin key is derived from the cluster secret at formation (#980), printed once, and — 
  unlike a file-declared key — is enumerable, revocable and audited. `AETHER_API_KEYS` pre-provisions
  one where that is preferred; `ConfigLoader.resolveApiKeys` reads it ahead of any TOML, which is how
  the integration suite supplies its own without a second config file to drift.
  [mechanism: `ConfigLoader.resolveApiKeys`, `BootstrapAdminKeyLeg`]
- **A node with security enabled and no declared key now says so at startup.** The existing warning
  covered only `security_mode = "none"`; security ON with an empty key set had no voice at all, and
  that is the shape the published image now boots with. A default applied when nothing was configured
  has to be loud, or it becomes the fail-open nobody notices.
- **`AETHER_API_KEYS` is now propagated by `ClusterIdentityEnv.IDENTITY_VARS`.** Only the singular
  `AETHER_API_KEY` — the client credential the CLI sends — was on the allow-list; the plural form is
  the server-side key set the node accepts. The omission was invisible **only because the image baked
  a matching key**, so a CTM-minted or auto-healed node inherited none and did not need one. Removing
  the baked credential makes it load-bearing: this fix exposes a pre-existing gap rather than
  introducing one. [mechanism: `DockerComputeProvider` iterates `IDENTITY_VARS`]
