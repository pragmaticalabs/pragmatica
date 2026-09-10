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
- **A node with no cluster secret still mints a RANDOM key — and that branch is a FAIL-OPEN, now
  loud.** It substitutes a plausible-looking credential for a derivation that did not happen: the CLI
  cannot derive that key, so the operator sees `aether cluster bootstrap` take a 401 from a healthy
  cluster — *precisely the defect this ticket fixes*. It now logs at **WARN**, naming what did not
  happen, that the bootstrap poll will fail authentication with 401, and which setting to fix. It was
  previously INFO and worded as reassurance.
  **Reachability, stated precisely — two routes, only one closed.** Via the BOOT PATH it is
  unreachable: a node with no cluster secret does not boot, because `Main.run` `.expect`s `resolveTls`,
  which fails with `MISSING_CLUSTER_SECRET`
  [verified: `MainClusterSecretStampTest#resolveTls_noClusterSecretAnywhere_failsSoTheNodeCannotBoot`,
  with `#resolveTls_clusterSecretConfigured_succeeds` as the positive control]. But via a **SKIPPED
  STAMP it remains reachable on a node holding a perfectly good secret** —
  `AetherNodeConfig.clusterSecret` is stamped separately, and that stamp's call site in `Main.run` is
  the one hunk no in-JVM test can defend (deleting it leaves the suite green). Mutating the equivalent
  `AetherNode` and `EmberCluster` hunks degrades into exactly this branch: the node minted a random
  key. **The WARN is therefore the compensating control for that gap** — where a test would otherwise
  stand, the runtime diagnostic stands instead. `EmberCluster` always supplies its own secret, so an
  in-process node derives exactly as a production node does.
  [verified: `BootstrapAdminKeyLegFallbackWarnTest` — 3 tests, with a sentinel positive control,
  because a `noneMatch` assertion over log capture is satisfied by an empty list and would otherwise
  examine nothing]
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
- **The wiring is pinned end-to-end by a real cluster.** `EmberBootstrapAdminKeyAuthTest`
  (`aether/ember`) boots a three-node in-JVM cluster from a known cluster secret **with management
  security ON and no configured API key — the incident's exact posture**, which is every cluster
  `aether cluster init` creates — waits for real leader election and the real consensus commit, then
  authenticates `GET /api/v1/cluster/keys` and `GET /api/v1/health` (the endpoint that returned the
  `401`) with a key derived OUTSIDE this codebase. Both must return 200 and the bootstrap key must be
  listed; then, in the same run and at that moment, the same endpoint must REFUSE a request with no
  key and one bearing a well-formed key derived from a different secret. Those two controls are what
  make the 200 mean "authenticated" rather than "no gate was reached".
  [verified: `EmberBootstrapAdminKeyAuthTest#derivedKey_isRegisteredByTheClusterAndAuthenticatesAgainstIt`]
  Mutation-probed: removing `config.clusterSecret()` from `AetherNode`'s call to the leg, and removing
  `EmberCluster`'s pass-through, each turn it RED with `Expecting "HTTP 403" to contain
  "bootstrap-admin"` — the node minted a random key and refused the derived one. `Main`'s stamp is
  pinned separately by `MainClusterSecretStampTest` (5 tests; replacing the stamped value with
  `Option.empty()` reddens 2 of them, and weakening the boot gate to accept an empty secret reddens a
  third). The fail-open WARN is mutation-probed too: dropping it to DEBUG reddens 2 tests, removing
  the consequence clause reddens 1, and making it fire on the healthy derived path reddens 1.
- **One residual gap, stated because it is real:** deleting the CALL to
  `Main.withResolvedClusterSecret` from `run()` still leaves everything green. `run()` is the process
  entry point and is not drivable in-JVM; that line is covered only by a real node boot — a cloud
  bootstrap or a container run. Everything else on the path now reddens.
- **What this does NOT establish** — [unverified] no cloud run was made, so the end-to-end claim (a
  bootstrap completing against a real cloud source) rests on the unit-level agreement of the three
  implementations plus the unpinned wiring above. Whether the KV commit lands
  inside the CLI's 5-second poll interval cannot be settled by reading or by unit tests; the poll loop
  retries for the whole `quorumFormation` timeout, which is the mechanism expected to absorb it, but
  the timing itself is unmeasured. [unverified] The HKDF-collision assumption (same derived CA implies
  the same secret) is a cryptographic assumption, not a traced fact.
