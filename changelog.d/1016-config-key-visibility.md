### Changed (2026-09-11 — #1016: keys declared in node configuration were invisible to operator tooling)

- **`GET /api/v1/cluster/keys` listed cluster-held keys only.** A key declared in a node's
  `[app-http.api-keys.<key>]` table or in `AETHER_API_KEYS` authenticated against every route —
  `KvStoreApiKeyValidator` consults the config validator first — while being absent from the listing
  an operator audits credentials with. A credential that cannot be seen cannot be reasoned about
  during an incident.
- **The listing now reports every credential the node accepts**, each record carrying a `source`
  field of `cluster` or `config`. A config record uses a synthetic `config:<declared name>` id and
  never the key value; its timestamps are `-1` because a file declaration has no creation, expiry or
  revocation event.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/api/routes/ConfiguredKeyVisibilityTest.java`]
- **`aether cluster rotate-key` filters candidates on `source`.** Without it the new records would
  have broken rotation outright: the command refuses to act on an ambiguous ACTIVE set, so any
  deployment pre-provisioning a single config key would have presented two ACTIVE records and
  rotation would have stopped working. A record with no `source` reads as `cluster`, so behaviour
  against a node predating the field is unchanged.
- **Revoking a config-declared key is refused, with a message naming the file as its authority.**
  Revocation commits a REVOKED record through consensus, which is meaningful only where the cluster
  key store is the key's authority. For a file-declared key the file is, the node cannot rewrite an
  operator's file, and the config validator is consulted before the store — so a tombstone would sit
  unread while the key kept authenticating. Reporting success would tell an operator a live
  credential was dead. The documented route is to remove the declaration and restart the node.
- **Making such keys genuinely revocable needs a design ruling, deliberately not taken here.**
  Consulting a KV tombstone before accepting a config key leaves a bypass window on every restart,
  because an empty KV during replay is indistinguishable from "nothing revoked". Gating config keys
  on `ManageableNode#isReady()` closes that window but stops them authenticating before the node is
  ACTIVE, which is when cluster formation uses them.
