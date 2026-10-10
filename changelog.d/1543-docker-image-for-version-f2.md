### Fixed (2026-10-10 - #1543 part F2: a docker replacement boots the image of the upgrade's version)
- **A docker source's replacement now boots the image its runtime profile pins at the committed `[cluster] version`.**
  The CTM resolves `image = "repo:{version}"` against the committed TOML and passes it in `ProvisionSpec`; the Docker
  provider runs `request.image()` when set and its single configured image otherwise (bootstrap seed, no committed
  TOML). Before this, every docker replacement booted the provider's configured image whatever the upgrade's version,
  so a rolling upgrade replaced each container with the same image.
- **An upgrade of a docker source whose roles do not all pin an image carrying `{version}` is refused at its start
  (HTTP 409 `UpgradeDockerImageUnversioned`), before any write and before any node is touched.** It names the source and
  the fix. It is never rolled back node by node. Cloud and ssh sources are unchanged: a cloud spec image is the VM boot
  image, so the container pin is not used for it.
- **Not covered:** a docker cluster with no committed TOML (a bootstrap seed) has no profile to resolve and keeps the
  provider's configured image; its upgrade is not refused because there is no source to inspect.
