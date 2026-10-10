### Fixed (2026-10-10 - #1543 part F2: a docker replacement boots the image of the upgrade's version)
- **A docker source's replacement now boots the image its runtime profile pins at the committed `[cluster] version`.**
  The CTM resolves `image = "repo:{version}"` against the committed TOML and passes it in `ProvisionSpec`; the Docker
  provider runs `request.image()` when set and its single configured image otherwise (bootstrap seed, no committed
  TOML). Before this, every docker replacement booted the provider's configured image whatever the upgrade's version,
  so a rolling upgrade replaced each container with the same image.
- **`aether cluster bootstrap` and the scale wave start a docker source's containers on the image their role's runtime
  profile pins** (as the provider's `image_name`, resolved per role at the cluster version). Before this they always
  booted the provider's default `aether-node:local`, so a cluster could start on an image its own pin did not name and
  the replacement that an upgrade starts later would differ from it. A role pinning no image keeps the default.
- **An upgrade of a docker source whose roles do not all pin an image carrying `{version}` is refused at its start
  (HTTP 409 `UpgradeDockerImageUnversioned`), before any write and before any node is touched.** It names the source and
  the fix. It is never rolled back node by node. Cloud and ssh sources are unchanged: a cloud spec image is the VM boot
  image, so the container pin is not used for it.
- **Not covered:** a docker cluster with no committed TOML (a bootstrap seed) has no profile to resolve and keeps the
  provider's configured image; its upgrade is not refused because there is no source to inspect.
- **`aether cluster upgrade` and `POST /api/v1/cluster/upgrade` accept SemVer pre-release versions (`1.0.0-rc5`).** The CLI
  accepted only `X.Y.Z` (since 0.25.0), so it refused every release version of this line; the route validated nothing.
  Both now use one rule (`ClusterUpgradeToml.isUpgradeVersion`): SemVer 2.0 with an optional pre-release, no build
  metadata (the version becomes an image tag); anything else is a typed 400 `InvalidUpgradeVersion` before any read or write.
- **`cluster upgrade --wait` survives the replacement of the node it polls** by following the live membership.
- **The docker provider acts only on its own cluster's containers** (`list` filters on the cluster label; `terminate`
  refuses with a typed `OutOfClusterScope` unless the target carries that cluster's label).
- **`aether cluster scaffold` emits `AETHER_SOURCE` on every node (`--source`, default `docker`).** A node learns its source only
  from that variable; without it the label is `default`, which no applied config names, so the first replacement of an upgrade
  or an auto-heal was refused with "No configured source for replacement default". The refusal's reason is now carried into the
  replacement record (and so into the operator event and the paused upgrade's message), bounded to 300 characters.
- **A replacement of a node that has no source label derives its source from the committed config, or is refused.** A cluster
  bootstrapped from static PEERS (compose, cloud-init) is known to its peers without source labels, so every node's replacement
  record carried a blank source, passed on as the source "default" and refused: the first replacement of a freshly bootstrapped
  cluster never started. A blank source now takes the source only when exactly ONE source, of any type, declares the role (no
  preference between types: a docker node is never replaced by a cloud VM); zero or several declaring it are a typed refusal
  naming the role and the candidate sources, which reaches the paused upgrade's message. Without any committed config (bootstrap
  seed, forge) there is nothing to derive from and the replacement proceeds as before.
- **The retirement and the provider listing of such a node use the same rule.** They asked the provider for the source "default"
  too ("Unknown compute source: default"), so the old node of a freshly bootstrapped cluster could not be reaped and the upgrade
  paused after the swap with both nodes kept. They now use the config's sole declaring source; ambiguity is the typed refusal
  and the node is not read as gone.
