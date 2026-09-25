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
- **The re-persist that follows a restore was the one `save` whose failure nobody heard.**
  `RabiaEngine.applyRestoredState` discarded its `Result<Unit>` outright, while the other three save sites
  (pause `:543`, reconfigure `:624`, stop `:654`) all attach `.onFailure(log::error)` and `GitBackedPersistence`
  carries no logger of its own — so the path was silent end to end while the next line announced `restored state
  from persistence` at INFO regardless. That save is what makes the restored state durable for the NEXT restart:
  when it failed the node came up ACTIVE and correct in memory, stale on disk, and reproduced this very ticket one
  restart later with no diagnostic anywhere. It now logs at ERROR naming the consequence rather than only the
  cause. [verified: `RabiaRestoredStateSaveFailureLogTest#restoredStateSaveFails_logsAtErrorNamingTheConsequence`
  — 1 red of 810 when the `.onFailure` is dropped again; its `#restoredStateSaveSucceeds_logsNoFailure` control is
  green on both arms, which is what separates "nothing to report" from "the appender never worked"]
- **`GitBackedPersistence` now creates `[backup] path`.** A configured directory that did not exist failed EVERY
  save while the node ran on as though persistence were switched off — so a cluster configured for durability kept
  nothing at all and came back empty, this ticket's symptom by another route. `writeTomlFile` creates it before the
  first write, ahead of `ensureGitInitialized`. This retires limitation (c) as originally written in this PR.
  [verified: `GitBackedPersistenceTest#save_backupDirectoryDoesNotExist_createsItAndWritesTheState` — 1 red of 810
  when the directory creation is removed]
- **The own-snapshot guard's EQUALITY boundary is now pinned.** The test is `persisted > live`, never `>=`:
  `reconfigure` persists the live, NON-EMPTY state-machine snapshot under `Phase.ZERO` and only then calls
  `stateMachine.reset()`, so a node's disk can hold a non-empty snapshot whose phase equals the live phase, and at
  equality installing the disk copy would resurrect the previous membership's state over a slate cleared on
  purpose. Changing `> 0` to `>= 0` previously left all 806 tests green. [verified:
  `RabiaSyncAdoptionOwnSnapshotTest#resyncFromActive_ownDiskSnapshotAtTheLivePhase_isNotInstalled` — 1 red of 810
  under exactly that mutation, `expected: null but was: [111, 119, 110]`]
- **A failed own-snapshot restore does not activate the node, and that is now SAID and PINNED rather
  than left as a side effect.** `activateWithoutAdoption` always activated before this ticket; routing
  its own-restore arm through `restoreState` — whose `activate()` hangs off `onSuccessRun` — means a
  `restoreSnapshot` that FAILS now leaves the engine `Syncing`, re-entering the same branch on every
  retry tick. It is fail-closed (such a node never serves the empty store this ticket is about), but
  the docstring claimed activation unconditionally and nothing covered the failure. The docstring now
  states it, and the ERROR names the CONSEQUENCE — node NOT active, serves no requests, every retry
  re-enters — instead of logging a bare cause object, because that line is the entire operator surface
  for the state: the periodic stuck-in-`Syncing` WARN is structurally suppressed on this path (#1447).
  **Whether the wedge is right, whether it should be bounded or terminal, and what readiness reports
  meanwhile are #1013's decisions and are deliberately NOT taken here.** The pin is an ENABLED
  tripwire, not a `@Disabled` placeholder: it asserts the CURRENT behaviour and its failure message
  names #1013 as the ticket that may legitimately flip it, so the change cannot be made silently.
  [verified: `RabiaOwnRestoreFailureTest#ownRestoreFails_logsAtErrorNamingTheConsequence` — 1 red of
  813 when the bare-cause log is restored; `#ownRestoreFails_staysInactive_untilTicket1013Decides` —
  1 red of 813 when a failed restore is made to activate anyway, which is the change #1013 might make;
  `#ownRestoreSucceeds_activates` is the control that makes the tripwire's negative assertion mean
  "genuinely did not activate" rather than "fixture never got there"]
- What this does NOT earn, stated so a sweep returns it beside the claim: the shipped
  `aether/docker/docker-compose.yml` and container `aether.toml` carry no `[backup]` section and mount no volume, so
  a deployment made from them still runs `RabiaPersistence.inMemory()` and loses every KV record — every minted key
  and every revocation — on a full restart; the derived `bootstrap-admin` key is re-registered by the fresh leader
  (`BootstrapAdminKeyRegistrar` latches per process) so a REVOKED bootstrap key comes back ACTIVE. [design intent —
  unverified: read at `BootstrapAdminKeyRegistrar.activate`/`BootstrapAdminKeyLeg.attempt`, not measured on a live
  restart] And with `[backup]` enabled, a key minted after the last lifecycle save (quorum-loss pause, reconfigure,
  graceful stop) is lost when the process is killed rather than stopped — `persistence.save` never runs on commit
  (`aether/docs/operators/runbooks/backup-recovery.md`).
