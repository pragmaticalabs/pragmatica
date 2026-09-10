### Fixed (2026-09-10 — #980: `aether cluster bootstrap` could not authenticate against the cluster it had just formed)

- **Phase 7 tore down healthy clusters.** `CLUSTER_FORMATION` cleared its all-nodes `/health/live`
  gate, then polled `/api/v1/health` for the quorum check and received
  `401 X-API-Key header required` from a cluster that was genuinely healthy — quorum established,
  leader elected, membership 3/3, `quorumSafe=true reason=NO_DEFICIT`. The tooling then rolled the
  cluster back. **The cause was structural, not a bug:** `BootstrapPhaseFormation` read its admin key
  from *cluster config*, `aether cluster init` never writes one, and the key bootstrap does mint is
  created at the top of phase 7 — after `PROVISION` and `DEPLOY_RUNTIME` — so it cannot appear in the
  cloud-init user-data the nodes booted from. Neither side held a key the other would accept, by
  construction.
- **The bootstrap admin key is now DERIVED from the cluster secret** both sides already hold, via
  HKDF-SHA256 under a new `info` label `aether-bootstrap-admin-key-v1`. The secret is minted six
  phases earlier (`VALIDATE`), it is in the user-data every node boots from, and a node without one
  does not boot at all — so a node that answered the liveness gate necessarily holds it. Nothing new
  crosses the wire: each side computes the key locally.
- **Registration is unchanged, and that is the load-bearing half.** The node derives at *first
  leadership* and commits the key's SHA-256 hash plus an `ApiKeyAuditValue` through consensus exactly
  as before (`BootstrapAdminKeyLeg`), so the key remains enumerable via `GET /api/v1/cluster/keys`,
  revocable, rotatable and audited. Deriving into the boot-time validator set instead would have been
  available sooner and enumerable by nothing — the shape was considered and rejected. `aether cluster
  rotate-key` / `revoke-key` keep working untouched, because the rotatable object is the KV
  registration, not the derivation.
- **Blast radius, stated rather than buried:** a cluster-secret holder previously got transport
  compromise but *not* the management API; they now get ADMIN until the key is rotated. This shortens
  an existing path rather than opening a new one — transport-level compromise was already total — but
  it is a real change and was accepted knowingly.
- **One HKDF implementation, not two.** The derivation shared by the CA key, the daily gossip keys
  and now the admin key moved out of `SelfSignedCertificateProvider`'s private methods into
  `ClusterSecretDerivation` (`integrations/net/tcp`); the provider calls it rather than carrying its
  own copy. A drifting second copy of a key-derivation function produces a wrong key with no compile
  error and no runtime signal beyond a 401.
- **No new wire type.** The key reuses `ApiKeyValue`/`ApiKeyAuditValue`; no `AetherKey`/`AetherValue`
  variant was added, so `SystemCodecPinningTest`'s tag pin is untouched.
- **Nodes with no cluster secret keep a random key** — the in-JVM Ember/Forge harness case. The
  fallback is strictly stronger, never weaker: the key is unguessable, merely not re-derivable. Which
  path ran is logged. Ember itself now passes its own secret through, so an in-process node derives
  exactly as a production node does.
- **`~/.aether/clusters/<name>/bootstrap-state.json` is now written owner-only (`0600`).** That file
  contains the cluster secret, and under this change it is an admin-equivalent credential file. It
  was written with a bare `Files.writeString` at default permissions while everything *derived* from
  it — the persisted `api-key`, the runtime TOML — already went through `SecureFiles.writeSecure`.
  Re-saving repairs an existing file's permissions in place, so operators are covered on their next
  bootstrap rather than only on a fresh one.
  [verified: `BootstrapStatePersistencePermissionsTest#save_writesTheClusterSecretFileOwnerOnly`,
  `#save_tightensAnExistingWorldReadableStateFile`]
- **Evidence.** [verified: `ClusterSecretDerivationTest` (`integrations/net/tcp`) — determinism,
  separation, label independence from the CA seed, and a compatibility vector computed OUTSIDE this
  codebase (Python `hmac`/`hashlib`), which is what makes it a gate rather than a tautology;
  `BootstrapAdminKeyLegTest.DerivationFromClusterSecret` (`aether/node`) — the leg's key equals what
  the CLI derives, two fresh clusters on one secret agree, two secrets do not, the key and its audit
  entry are still enumerable through the same `forEach(ApiKeyKey.class, ApiKeyValue.class, …)` scan
  `ApiKeyRoutes.handleListKeys` uses, and a re-elected leader does not mint a second key;
  `BootstrapAdminKeyValidatorAcceptanceTest` (`aether/node`) — a key derived through the *CLI's* entry
  point, registered through the real leg, accepted as ADMIN by the real `KvStoreApiKeyValidator`, and
  a key from another cluster's secret refused;
  `BootstrapPhaseFormationDerivedKeyTest` (`aether/cli`) — the CLI derives when no key is configured,
  an operator-configured ADMIN key still wins, and an empty or blank secret is never derived from.]
  Each behavioural hunk was mutation-probed — mutation applied, the named test confirmed RED, file
  restored, suite reconfirmed green: the HKDF `info` label (2 red), HKDF-Extract removed (1 red — and
  ONLY the externally-computed vector caught it; determinism and separation stayed green under a
  self-consistent wrong algorithm), constant HKDF output (10 red, 5 of them in
  `SelfSignedCertificateProviderTest`, which is the evidence the provider genuinely routes through the
  shared implementation and kept no private copy), the leg reverting to a random key (3 red across 2
  classes), the audit entry dropped (1 red), the blank-secret guard removed (1 red), the CLI reverting
  to config-only key lookup (2 red), and the state file reverting to a bare write (2 red).
- **THREE WIRING HUNKS ARE NOT PINNED BY ANY TEST, and their mutations stay green.** Stated as a
  measured result, not an omission: replacing `config.clusterSecret()` with `Option.none()` in
  `AetherNode`, replacing the `Main` stamp with `Option.empty()`, and replacing Ember's pass-through
  with `Option.empty()` each leave the suite entirely green — 1299 `aether/node` tests for the first
  two, 6 `aether/ember` tests for the third. These are boot-assembly lines whose only observable is a
  live cluster registering a key, so nothing short of a cloud bootstrap or a new Ember config-accessor
  test can redden them. A defect confined to those three lines would ship undetected by CI.
- **What this does NOT establish** — [unverified] no cloud run was made, so the end-to-end claim (a
  bootstrap completing against a real cloud source) rests on the unit-level agreement of the three
  implementations plus the unpinned wiring above. Whether the KV commit lands
  inside the CLI's 5-second poll interval cannot be settled by reading or by unit tests; the poll loop
  retries for the whole `quorumFormation` timeout, which is the mechanism expected to absorb it, but
  the timing itself is unmeasured. [unverified] The HKDF-collision assumption (same derived CA implies
  the same secret) is a cryptographic assumption, not a traced fact.
