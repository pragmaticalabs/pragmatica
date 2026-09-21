### Fixed (2026-09-21 — #1020: a full cluster restart loses every cluster-minted API key)
- **A node restarted from disk never installed its OWN persisted snapshot.** A minted API key is a
  `KVCommand.Put` committed through consensus into the replicated KV store, and that store is durable only
  through `[backup]` (`RabiaPersistence.gitBacked`; in-memory otherwise). `RabiaEngine` read `persistence.load()`
  for three things — the sync-response payload served to peers, the adoption floor and the boot future-history
  detector — and never to populate its own state machine. The branch that activates "on this node's own state"
  (every response behind self's persisted phase, or a single-node cluster with no peer) therefore activated an
  EMPTY store at phase 0: after a full-cluster stop with `[backup]` enabled, a key came back only if the first
  responder's persisted phase happened to EQUAL self's, and a staggered graceful stop breaks that tie through the
  last node's quorum-loss pause save — so the node holding the MOST advanced snapshot came up empty and answered
  403 for a key it had acknowledged. `activateWithoutAdoption` now installs the persisted snapshot through
  `restoreState` when its phase is ahead of the live one (phase advance-only, pending batches carried, re-persist,
  activate, replay, notify); a live phase at or past the persisted one — a resync from ACTIVE — still installs
  nothing, because the disk lags a live process. [verified:
  `RabiaSyncAdoptionOwnSnapshotTest#everyResponderBehind_activatesWithOwnPersistedSnapshotInstalled`,
  `#singleNode_activatesWithOwnPersistedSnapshotInstalled` — red on the unmodified engine with `lastRestored: null`
  and `Phase[value=0]`; `#resyncFromActive_ownStaleDiskSnapshotIsNotInstalledOverTheLivePhase` reddens when the
  ahead-of-live guard is dropped] [verified on the live path:
  `ApiKeyFullRestartForgeTest#fullGracefulRestart_everyNodeAcceptsTheKeysMintedBeforeTheStop` — three Ember nodes
  with per-node `[backup]` dirs, `API_KEY` management security; mint, graceful stop of node 1, mint again through the
  survivors (phase skew), stop, restart with node 3 held back so node 2's only responder is behind it. With the engine
  hunk reverted on the same base: `second-minted-key presented on akr-2: expected 200 but was 403` for the full 30 s
  poll; with it: node 2 logs `activating on its own persisted state … persisted phase 14`, both keys answer 200 on
  every node, and `BootstrapAdminKeyRegistrar` finds the restored admin key instead of minting one]
- Depends on #1341, which landed the same day: before it `AetherNode::base64ToSnapshot` was handed the `# Phase: N`
  header `GitBackedPersistence` writes and every production `load()` failed to `none()`, so a restarted node was
  amnesiac whatever was on its disk — the first run of the forge pin against `d444d22c6` wedged the restart at
  "restored state … phase 0" on one node and Syncing forever on the other. On top of #1341 the tie-only restore
  above is the remaining defect, and this fix closes it.
- **Two existing pins asserted the defect** (`RabiaSyncAdoptionQuorumTest#ownMoreAdvancedState_isNotRegressed_byStalerResponses`,
  `#futureHistory_stillDetected_butNoLongerRegressesOntoTheOlderClusterState`): both asserted that NOTHING was
  installed, on the argument that the persisted snapshot lags the live state. That argument holds for a resync from
  ACTIVE and not for a fresh process whose live store is empty. They now assert that the RESPONDERS' snapshot is not
  installed and self's is — which still reddens if the adoption floor is deleted, the mutation the originals were
  written against.
- What this does NOT earn, stated so a sweep returns it beside the claim: `GitBackedPersistence` does not create
  `[backup] path`, so a path that does not exist (the shipped image creates `/data`, not `/data/backups`) fails every
  save at ERROR while the node runs on — provision the directory. [unverified: not pinned; the harness pre-creates
  per-node dirs, as #1341's `withConsensusBaseDir` requires] The shipped
  `aether/docker/docker-compose.yml` and container `aether.toml` carry no `[backup]` section and mount no volume, so
  a deployment made from them still runs `RabiaPersistence.inMemory()` and loses every KV record — every minted key
  and every revocation — on a full restart; the derived `bootstrap-admin` key is re-registered by the fresh leader
  (`BootstrapAdminKeyRegistrar` latches per process) so a REVOKED bootstrap key comes back ACTIVE. [design intent —
  unverified: read at `BootstrapAdminKeyRegistrar.activate`/`BootstrapAdminKeyLeg.attempt`, not measured on a live
  restart] And with `[backup]` enabled, a key minted after the last lifecycle save (quorum-loss pause, reconfigure,
  graceful stop) is lost when the process is killed rather than stopped — `persistence.save` never runs on commit
  (`aether/docs/operators/runbooks/backup-recovery.md`).
