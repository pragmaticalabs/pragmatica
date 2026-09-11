### Fixed (2026-09-11 — #994: a mid-PROVISION bootstrap failure stranded every server already created)

- **The ledger, not the delete order, was the defect.** `BootstrapCleanup.inDestructionOrder` already
  ranked VMs ahead of firewalls; the observed run deleted the firewall first because the cleanup ledger
  held **no VM records at all**. `BootstrapPhaseProvision` wrote them only in `buildUpdatedState`, which
  runs *after every source has provisioned successfully* — so when a dedicated-core quota refused the
  third of three `ccx23` servers, the failing phase returned the pre-phase context, three separate layers
  discarded the nodes already created (`rotateZonesForRoleGroup` returns an empty list on failure,
  `provisionCloudWithCompute` drops its accumulator, `execute` returns the original context), and
  teardown issued **zero server deletes** while two servers kept billing. The firewall delete then failed
  `422 resource_in_use` six times against servers the cleanup could not name.
  [mechanism: each VM is appended to the **persisted** state as the provider reports it created, via a
  `recordingProvisioner` aspect composed around the per-node seam in `provisionCloudRoleGroup`; the state
  file is the only channel that survives a failed phase, because a `Result` failure carries no context
  and `ClusterBootstrapOrchestrator.cleanupOnFailure` re-loads the file]
- **The FAILED-phase marker no longer rolls the ledger back.** `markPhaseFailed` saved the *pre-phase*
  in-memory snapshot, which would have erased every record the phase had just written to the file. It now
  re-loads the persisted state and marks the phase FAILED on that, falling back to the snapshot only when
  nothing is persisted. Without this half the recording above is undone at the moment it matters.
- **The per-source cleanup handle is persisted BEFORE the first server is created.** It was also written
  only by `buildUpdatedState`, so teardown of a mid-provision failure had no credential mapping to
  re-derive the provisioning token and fell back to a raw env var that may name a different account.
- **The retry diagnostic no longer asserts a mechanism that is not running.** It printed *"servers are
  still detaching; retrying..."* on every attempt — including the observed run, where **no server delete
  had been issued**, so nothing was detaching; the message sent an operator to look at server shutdown
  while the actual problem was the ledger. It now quotes the provider's own refusal verbatim and states
  the observed accounting: how many VMs the ledger records for that source, and how many this cleanup
  deleted. Zero recorded VMs reads as *"whatever still holds the firewall is a server this cleanup cannot
  name"*, with the label selector that finds it.
- **A partial cleanup enumerates what it left behind, by type and id.** `"orphan resources may remain"`
  is replaced by a `NOT REAPED` block listing every resource that was not deleted plus the
  `cloud-reaper.sh` invocation that finishes the job, and the same enumeration is carried in the failure
  `Cause` so it survives into `BootstrapFailedWithOrphans` after the transcript has scrolled away.
- Pinned by `BootstrapPhaseProvisionLedgerTest` (the observed shape: two servers created, the third
  refused `403 resource_limit_exceeded`, ledger asserted to hold both; plus a negative control that a
  *refused* attempt records nothing, so teardown never chases a phantom id) and
  `BootstrapCleanupTest.DestructionOrder` / `.FirewallRefusalDiagnostic` (delete order asserted against a
  ledger that records the VM first, so the guarantee comes from the rank sort rather than from which phase
  recorded first). Each assertion of an absence has a positive control beside it.
- **[unverified: no cloud run]** — every claim above is established against the real compositions in-JVM.
  That two `ccx23` servers are actually deleted by a quota-refused `aether cluster bootstrap` is **not**
  demonstrated here; the confirming run is a deliberate 3×`ccx23` request against the ~8-core dedicated
  quota, which reliably creates two servers and `403`s on the third.
