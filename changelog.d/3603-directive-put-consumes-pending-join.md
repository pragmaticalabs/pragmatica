### Fixed (2026-11-26 — #3603: a worker directive removed right after its commit was written again)
- **A pending worker join outlived its own directive until the next reconcile pass.** The directive's Put queued that pass at the mailbox
  tail, so a removal already in the mailbox was handled first; the pass then evaluated the still-pending join with the directive absent and
  assigned it again (a write expecting none, refused when the removal's replacement landed first). The directive's Put now consumes the
  pending join in the handler that observes it (`Directory.onDirectivePut` calls `Joins.reevaluate`, as `DepartureFlow` does when a
  record arrives), so no pending join survives its directive and a later removal has none to supersede. Every writer that reads the pending
  join (the reconcile pass, a departure record's Put or Remove) finds none.
- Pinned by `DirectiveRemovalSupersedesPendingJoinTest`, which drives the production input order (join, directive Put and settlement,
  removal, first pass) and the two record triggers.
