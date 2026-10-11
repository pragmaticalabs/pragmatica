### Changed (2026-10-04 — BREAKING, #1424: `POST /api/v1/cluster/upgrade` now carries the `expectedVersion` fence)
- **BREAKING (wire contract, pre-GA, no migration path): the request body gains a required `expectedVersion`.**
  `UpgradeRequest` was `(targetVersion)` only, the one mutating cluster-config route without a client-side
  version fence. The store-level RFC-0018 successor CAS closed the lost UPDATE, but two operators issuing
  different `targetVersion`s, or an upgrade landing beside a scale or apply, resolved as last intent wins
  (the later request re-read the fresh version and committed on top). The route now fences exactly as
  apply-config (#289) and scale (#1086) do: a stale `expectedVersion` is a 409 `VersionConflict`, an explicit
  `0` against a populated config is a 409 `UnfencedOverwrite` (never a wildcard), and an omitted or `null`
  field is refused at decode time with a 400 (the field is a primitive `long`). A request for the version the
  cluster is already at keeps its "already at version" answer, ahead of the fence.
- **`aether cluster upgrade` sends it**, reading `configVersion` from the same `GET /api/v1/cluster/config`
  that supplies the current version. Any other client of the route must read `configVersion` and send it.
- Docs: `management-api.md` request, field table and conflicts updated.
- Pinned by `ClusterConfigRoutesUpgradeFenceTest` (through the real route handler), `UpgradeRequestContractTest`
  and `ClusterUpgradeCommandTest` (the two spellings of the wire contract).
