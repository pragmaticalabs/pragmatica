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

### Fixed (2026-09-11 — #994 adversarial-verification round: the ledger's own durability)

The four findings below come from the adversarial verification of the fix above. None was a live
defect in the shipped behaviour; each narrows a guarantee the fix asserts, or pins a production line
that was removable without reddening a single test.

- **The ledger write is all-or-nothing, and an UNREADABLE ledger is never overwritten.**
  [verification finding SF-1] The fix above promotes `bootstrap-state.json` to the sole surviving
  record of money-bearing resources — and nothing verified that it could be written, or that what came
  back from it was real. Three holes, all closed:
  - `SecureFiles.writeSecure` opened the target itself with `TRUNCATE_EXISTING` and wrote in place, so
    a process that died part-way through left a zero-byte or half-written file: `Files.exists` true,
    `fromJson` failing, the ledger of paid VMs reading as **EMPTY**. Both the #994 and #995 incidents
    ended with the operator **killing** the process. Content now lands in a sibling temp file and
    arrives at the target by `ATOMIC_MOVE`.
    [mechanism: `rename(2)` within one directory either completed or did not, and the page cache
    survives process death; the temp file's bytes are `force`d before the rename, so what a completed
    rename publishes is on the device]
    **[unverified: host power loss]** — the DIRECTORY entry is not fsync'd, so a power loss inside the
    window can still lose the last write. Process death, the observed failure mode, cannot.
  - **`absent` and `unparseable` are now different answers.** `BootstrapStatePersistence.read` returns
    `Result<Option<BootstrapState>>`; `load` keeps its old shape for callers that only want a state if
    there is one. Under the old `load` both arrived as an empty `Option`, and the two callers whose
    decisions are money-bearing both guessed wrong: `markPhaseFailed` saved the VM-less **pre-phase
    snapshot over a torn ledger** — leaving valid JSON that nothing downstream could distinguish from a
    cluster that created nothing, which is #994's outcome reached by another route — and
    `cluster destroy` read a torn ledger as *"no bootstrap state — skipping resource cleanup"*,
    returned cleanup-OK, removed the registry entry and exited 0 while every server the ledger named
    kept billing. Both now REFUSE, name the file, and print the `cloud-reaper.sh` invocation; destroy
    additionally keeps its registry entry and exits non-zero, which is #521's property applied to a
    ledger it cannot read. Failing closed costs nothing on either path: `markPhaseFailed` runs only
    after the bootstrap has already failed, and an unreadable ledger names no resource destroy could
    have reaped anyway.
  - **Every PROVISION-path write is consumed, and every way recording can fail prints the SERVER ID.**
    The pre-phase save that guarantees a file exists, an absent ledger, an unreadable one, and a failed
    append were all silent — and `recordProvisionedVm` is a no-op when the load is empty, so an
    unchecked save at the top of the phase silently dropped every VM the phase created. The id now
    reaches stderr even when the ledger cannot hold it, because with the ledger broken it is the only
    thing that can still name the server. It **warns rather than aborting**: the VM already exists and
    already bills, so failing the run cannot un-bill it, while a full disk under `~/.aether` would turn
    a recoverable problem into a dead bootstrap.
- **Production's USE of the recording composition is pinned, not just the composition.**
  [verification finding SF-2] `provisionAndRecordRoleGroup` was covered; the line in
  `provisionCloudRoleGroup` that CALLS it was not, so replacing that call with a direct
  `rotateZonesForRoleGroup` — deleting the entire recording behaviour — left all 724 tests green. #994
  *was* an unwired mechanism: `buildUpdatedState` existed, worked, and never ran on the failure path,
  so a regression that re-unwires recording is the same defect class. Pinned by
  `BootstrapPhaseProvisionLedgerTest.ProductionWiresTheRecorder`, which drives
  `provisionCloudRoleGroup` through a real `ComputeProvider`: every layer between the provider's
  "created" and the state file is production code.
- **The `NOT REAPED` enumeration covers all THREE teardown paths.** [verification finding SF-4] The
  claim above was true of the ledger-driven `cleanupWith` and **false of the command as a whole**: the
  label-scoped VM sweep reported `"VM sweep failed: <message>"` and the ssh-key sweep a joined string,
  neither carrying a type or an id — and the VM sweep is the path whose whole purpose is the
  **unrecorded billable VMs** that are #994's theme. Both now route their failures through
  `ReapFailure` over a real `CreatedResource`, so all three paths print the same enumerated block and
  carry it in their `Cause` (`VmSweepFailed` / `SshKeySweepFailed`, both now `(detail, notReaped)`).
  A swept VM's `role` is recorded as `label-swept` rather than guessed: a VM the ledger never held has
  no recorded role, and that absence is why the sweep exists.
- **Test-state isolation.** `BootstrapPhaseProvisionLedgerTest` writes the real
  `$HOME/.aether/clusters/<name>` path (`AETHER_DIR` is an interface constant off `user.home`), and
  used a FIXED cluster name — so two concurrent `aether/cli` reactors on one machine shared it and one
  test's `@BeforeEach` delete could race the other's write. Same class as #939's fixed port. The name
  now carries a per-JVM random suffix and the directory is removed afterwards.
