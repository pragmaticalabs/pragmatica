### Fixed (2026-09-21 — #1086: `POST /api/v1/cluster/scale` accepted `expectedVersion:0` as an unfenced overwrite of a populated config)
- **`ClusterConfigRoutes.applyScale` fenced only through `checkVersionAsync`, whose `expectedVersion != 0 && …`
  treats 0 as the fresh-cluster bypass; the #289 `isUnfencedOverwrite` refusal sat on the apply-config path only.**
  A scale body carrying the zero default — or omitting the field, which Jackson reads as 0 — rewrote a populated
  config's desired count with no fence at all: probed live on PR #1070's review, 5→7 with `expectedVersion:0`
  against `storedVersion=1` answered `HTTP 200 … configVersion:2`. The fence exists so two operators, or an
  operator and the reconciler, cannot clobber each other's desired count; a client that sent no version bypassed it
  silently. Reproduced through the real route handler: on the unmodified base
  `ScaleRequest("eu","core",5,0)` against `configVersion=1` answered
  `Success(ScaleClusterResponse[… previousCount=3, newCount=5, configVersion=2])`.
- The scale path now earns the guarantee apply-config earned under #289: **a scale request whose
  `expectedVersion` differs from the committed `configVersion` is refused with HTTP 409 before any write, and
  `expectedVersion=0` against a populated config is a mismatch (`UnfencedOverwrite`), not a wildcard.** Every stored
  config a scale can reach carries a version of at least 1 (`INITIAL_CONFIG_VERSION`; the bootstrap seed is stamped
  1), so 0 never means "fresh cluster" on this route. The refusal is placed where #289 placed it on apply-config —
  at the write, after the scale validator — so a request the validator refuses anyway (a quorum violation, an
  undeclared target) still answers with the validator's own cause; `03-scaling/test-01-quorum-safety.sh`, which
  probes the validator with `expectedVersion:0` bodies, keeps seeing `Quorum safety violation` / `Invalid core max`
  `[verified: ClusterConfigRoutesScaleFenceTest — stored 1 / expected 0 → 409 UnfencedOverwrite, store unchanged;
  stored 0 / expected 0 → 200; stored 1 / expected 1 → 200; stored 2 / expected 1 → 409 VersionConflict;
  stored 1 / expected 0 / count 1 → QuorumSafetyViolation, not the fence]`.
- **Audit of the other mutating cluster-config routes in `aether/node`** (`ClusterConfigRoutes.routes()`):
  `POST /api/v1/cluster/config` already carries the #289 refusal; `POST /api/v1/cluster/upgrade` takes no
  `expectedVersion` at all (`UpgradeRequest(targetVersion)`) and is guarded only by the store-level RFC-0018
  successor fence — not the #1086 shape, unchanged, noted. No other route reads an `expectedVersion`.
- **The integration harness's `scale_cluster` (`lib/cluster.sh`) relied on the bypass** — #594 recorded
  "`expectedVersion: 0` is correct and stays". It now reads `configVersion` from `GET /api/v1/cluster/config` and
  fences with it, as `aether cluster scale` and the forge scale probes already did; an unreadable version fails the
  call loudly rather than substituting 0, which the server would now refuse anyway
  `[unverified: no live cluster run of the harness in this change; `bash -n` and the extractor's controls only]`.
  The CLI is unaffected: `ClusterScaleCommand.fetchConfigVersion` already sends the committed version.
- `management-api.md` said the scale request "is rejected if it no longer matches", which was false for 0. The field
  table and the Conflicts section now state the 0 case and where it is checked.
