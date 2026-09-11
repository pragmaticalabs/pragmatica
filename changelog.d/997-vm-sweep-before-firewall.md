### Fixed (2026-09-11 — #997: cluster destroy deleted the firewall before the label VM sweep, stranding it)

- **The label sweep now runs INSIDE the ledger walk, between the last VM delete and the firewall delete.**
  `runCleanup` ran `resourceCleaner` — which deletes the firewall — and only then `vmSweeper`. The sweep
  exists to reap VMs the bootstrap ledger never recorded (stage-5 cluster-provisioned workers, auto-heal
  replacements), so when one of those held the firewall the delete burned all 6 attempts, **failed**, and
  the sweep then removed the VMs: leaving the firewall stranded, with its registry entry deliberately kept.
  The resource that survived was the one the ordering existed to protect.
  [mechanism: `cleanupWith` takes an optional pre-firewall hook fired at `SWEEP_BEFORE_RANK`, the
  firewall's own `destructionRank`, so the order is cores -> label-swept VMs -> firewall -> keys]
- **RFC-0017 stage 6 / C3's constraint is preserved, not traded away.** The ledger's VMs — the CORES among
  them — are deleted before the hook fires, so the leader's worker reconciler is already dead and cannot
  re-provision what the sweep reaps. Both constraints hold simultaneously; the fix is an insertion point,
  not a reordering of the two.
- **The sweep is ABSENT on the bootstrap path, not a no-op there.** `sweepClusterVms` REFUSES a cluster in
  `PROTECTED_CLUSTERS`, and a refusal is a `Result` failure, so wiring it in unconditionally would have
  made every bootstrap rollback of a protected cluster fail on a sweep it never asked for — a new failure
  mode on the money path, to fix a destroy-path ordering bug. The hook is an `Option`: bootstrap passes
  none, and the hazard is off that path by construction rather than by a guard.
- **The hook had to be an `Option` rather than a no-op for a second reason, caught by its own control.** A
  no-op returning success is indistinguishable from a real sweep that succeeded, and the firewall
  diagnostic reports whether a sweep ran — so the first version of this fix printed *"the VM sweep ran and
  reported success"* on the bootstrap path, where no sweep exists. That is the #994 class (a diagnostic
  asserting an action instead of reporting an observation) reintroduced by the fix for #997. The absent
  case is now `SweepVerdict.NOT_RUN`.
- **`VmAccounting` reports the sweep, because the reorder made its old wording false.** With an empty
  ledger it said *"this cleanup has issued no server delete for it"* and advised *"check for unrecorded
  VMs"* — both contradicted by a sweep that had just deleted unrecorded VMs immediately above. It now
  qualifies the ledger-driven claim and states the sweep's verdict, including that a FAILED sweep leaves a
  VM that may still hold the firewall.
- **A failed sweep fails the cleanup, so the bootstrap ledger is KEPT.** It used to be reported beside the
  cleanup: the ledger file was deleted while the failure merely kept the registry entry, and a second
  `destroy` then found no state, printed *"No bootstrap state — skipping resource cleanup"*, removed the
  entry and exited 0 over VMs that were still billing. Keeping it makes the retry the idempotent operation
  it is already advertised as.
- **The sweep still runs when the ledger records nothing.** The walk is skipped entirely for an empty
  ledger, so moving the sweep inside it would have removed the sweep for exactly #994's observed state —
  every created VM unrecorded and billing, the label selector the only thing that can find them. That
  branch runs the sweep directly and a failure there fails the cleanup too.
- Pinned by `BootstrapCleanupTest.PreFirewallVmSweep` (one interleaved call sequence asserted as
  `terminate:vm-1 -> sweep -> disposeIngress:77`; `SWEEP_BEFORE_RANK` asserted strictly between the VM rank
  and the firewall's own `destructionRank` rather than against a literal; exactly-once with no firewall in
  the ledger and with an empty ledger, so neither the in-walk nor the post-loop call can go missing or
  double-fire; and a failing sweep failing the cleanup) plus
  `BootstrapCleanupTest.FirewallRefusalDiagnostic` (the SUCCEEDED and FAILED clauses, with the bootstrap
  path's NOT_RUN as the control that makes the verdict a read value rather than a constant string) and
  `ClusterDestroyCommandTest.CleanupInvocation` (the empty-ledger sweep and its failure).
  `vmSweeper` previously had **no test assignment anywhere**, which is why this ordering was unpinned and
  the defect was reachable only by inspection.
- **[unverified: no cloud run]** — established in-JVM against the real compositions. That a live Hetzner
  destroy now releases the firewall after sweeping an unrecorded worker is **not** demonstrated here; it
  needs a cluster with a label-matching VM the ledger does not record.
