### Fixed (2026-11-11 — #2834: the FSM2 leader-local open warnings are committed, so a successor inherits them)
- **A leader change no longer re-raises, or fails to close, the cluster FSM's leader-local warnings.** The open set of 11 warning
  families (`invalid-placement`, `retry-exhausted-unsettled`, `schema-status-unknown`, `stray-instance`, `schema-hold`, `join-unmatched`,
  `worker-no-community-policy`, `community-assignment-unplaced`, `worker-absence-unjudgeable`, `departure-inferred-suppressed`,
  `community-growth-blocked`) was memory-only, so the successor raised a still-open warning a second time and could never publish
  the close of one that ended between leaders.
- **Mechanism.** One committed KV entry per open warning (`FsmWarningKey` / `FsmWarningValue`, wire tags 2902 and 2903, excluded from
  worker replicas), reconciled by one `WARNINGS` transaction per drain, in the same `apply` batch as the decision that raised the
  warning. A refused or expired write is drafted again from the corrected mirror; a failed one by the next reconcile pass. The
  successor's rebuild seeds its family sets from the committed entries and closes those whose subject left between leaders.
- The 11 recovery codes now close across nodes (their open condition is committed state, ruling A2 on #2831).
- Residual: a leader deposed between raising a warning and the commit of its `WARNINGS` write leaves that warning unpublished-to-KV
  (the write is refused by the leader fence); `join-unmatched` of a live node with no pending join at the successor stays open until its
  next join evaluation.
