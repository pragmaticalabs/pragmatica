### Fixed (2026-09-12 — #1022: auto-heal provisions VMs the bootstrap ledger never records)

- **The operator's cleanup ledger is structurally incapable of naming a CTM auto-heal replacement, and
  no missing call site explains it.** The ledger is `~/.aether/clusters/<name>/bootstrap-state.json`
  on the operator's machine, written by `aether/cli`; a replacement is created by the cluster LEADER,
  in `aether-deployment`, on a VM in the cloud — a different process on a different host, in a module
  `cli` depends ON and which cannot depend on `cli` back. Observed 2026-09-11: five replacements the
  ledger never held, and the `aether-cluster` label sweep reaped four more VMs than teardown knew
  about. `HetznerComputeProvider` already says this out loud — "it is not recorded in
  `bootstrap-state.json`, so `aether cluster destroy` finds it by sweeping `aether-cluster=<name>`" —
  which made the sweep the only channel and the ledger permanently incomplete.
  `[mechanism: module dependency direction, `aether/cli/pom.xml` declares no `aether-deployment`]`
- **The label sweep now WRITES what it finds into the ledger, before deleting any of it.** Every swept
  VM the ledger does not already name by provider id is appended, one write per VM, so a teardown that
  then fails part-way leaves the ledger naming the servers that are still billing — #994's property,
  extended to auto-healed VMs. Ordering is pinned, not incidental: a record made after its delete buys
  nothing. `[verified: aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapCleanupTest.java — vmSweep_recordsUnrecordedVmsInTheLedger_beforeDeletingThem]`
- **Bootstrap-minted VMs are not double-counted.** The selector matches every cluster-labelled VM,
  including the ones bootstrap already recorded; an unfiltered append would enter each of them a second
  time under the synthesized `label-swept` role, turning the ledger an operator reads into a double
  count of what is billing. `[verified: … — vmSweep_skipsVmsTheLedgerAlreadyNames_recordingOnlyTheRest]`
- **A ledger that cannot be written no longer stops the reaping**, and the id still reaches stderr —
  the server is billing either way, and refusing to delete it because a note about it could not be
  saved would turn a full disk into an un-reapable cluster.
  `[verified: … — vmSweep_ledgerWriteFailure_stillDeletes_andNamesTheIdOnStderr]`
- **One ledger-append path, not two.** `BootstrapStatePersistence.appendResource` is now the single
  read-modify-write onto the ledger, shared by the bootstrap recorder
  (`BootstrapPhaseProvision.recordProvisionedVm`, #994) and the sweep. It never CREATES a ledger: an
  absent state file means the cluster was not bootstrapped from this machine, and fabricating one would
  mint a cluster record with no secret, no source handle and no credential mapping — a file that reads
  as authoritative and can reap nothing. Absent, unreadable and write-failed stay three distinct
  refusals because they call for three different operator actions.
  `[verified: aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapStatePersistenceAppendResourceTest.java]`
- **The leader no longer drops the id of the server it just paid for.** `asDispatched` took an
  `InstanceInfo` and ignored it, so a replacement's provider instance id existed nowhere. The CTM now
  emits it at WARN with the node id, role and source. This is a weaker record than the ledger — it is
  lost with the VM that holds it and is not what an operator reads — and it is named as weaker rather
  than counted as the fix. A FAILED provision created nothing and claims no instance.
  `[verified: aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerActuatorTest.java — ProvisionedInstanceRecording]`
- **What this does NOT do, stated because the gap is the point:** it gives the operator no live
  inventory while the cluster runs. The ledger becomes correct at teardown, not at creation, and a
  cluster reaped by any route that does not run this sweep still leaves it silent. Closing that needs a
  replicated cluster-side record read back over the management API — which is unreachable exactly when
  `destroy` matters most, on a cluster that is already broken. `[design intent — unverified]`
- **Not verified without a live cloud run:** that the ledger written on the real teardown path names an
  auto-heal VM end to end. Every claim above is pinned by tests against injected provider and ledger
  seams; none of them provisions or reaps a real server.
