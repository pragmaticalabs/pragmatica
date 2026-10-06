### Fixed (2026-10-06 — #1543 part C: an upgrade's version becomes the version replacements provision)
- **`POST /api/v1/cluster/upgrade` stored the target in `ClusterConfigValue.version` and left the committed TOML's
  `[cluster] version` unchanged.** Replacement user data renders its image tag and jar URL from that TOML, so after
  an "upgrade" every auto-heal replacement still booted the OLD version, and a later `cluster apply` stamped the
  old version back over it. The route now rewrites the TOML's `[cluster] version` line (re-parsed and required to
  read back the target, so an unrewritable config is refused, never half-written) and stores the same version
  beside it, under the unchanged #1424 `expectedVersion` fence. A bootstrap seed with no committed TOML still moves
  only the stored version.
- **A literal pin the upgrade would silently ignore is refused (HTTP 409 `UpgradeVersionPinned`); a pin may carry
  `{version}` to follow it.** When a runtime profile used by a source role pins `image` (container runtime) or
  `jar_url` (JVM runtime), the renderer prefers the pin over the version. `{version}` in either field is now
  substituted from `[cluster] version` at render time (replacement user data and the CLI bootstrap launch), and a
  pin carrying it is not refused. The placeholder must be in the committed config: apply does not currently change
  runtime-profile content. A CRLF config is refused with a message naming CRLF. The harness TOMLs
  (`env/docker*`, `remote*`, `cloud-hetzner*`, `tests/cloud`) pin deliberate candidate/local artifacts that differ
  from `cluster.version`, so they stay literal.
- **`POST /api/v1/cluster/config` with a changed `[cluster] version` is refused with a typed 409
  `VersionChangeViaApply`** pointing at `aether cluster upgrade`, replacing the generic 501 "escalate rather than
  retry". Smallest correct change: the upgrade route is the single writer of the version; apply is not a second one.
- **Nodes advertise the version they run** in a `version` NodeInfo label (no wire change: labels already ride the
  ANNOUNCE and the QUIC Hello; the steady-state gossip subset is unchanged), and `GET /api/v1/nodes/lifecycle`
  entries gain `version`. Empty means this observer holds no label for the peer, not an old version.
- **Not changed:** the upgrade still restarts and replaces nothing — it changes what future provisioning boots.
  Replacement-based rolling is the remaining #1543 work.
