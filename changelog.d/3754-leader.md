### Fixed (2026-11-26 — #3754: a stopped `LeaderReconciler` left its scheduled tasks armed)
- **`LeaderReconciler.deactivate()` cancelled only some of its tasks, and a body already running at the stop could still act.**
  Every task of a leader term (the activation one-shot, the debounced reconcile, the in-flight sweep, and the deficit,
  surplus and drain-grace follow-ups) is now scheduled through one `OwnerLifetime` per term; the stop ends it, and the next
  activation gets a fresh one. A body re-arms on the lifetime it was scheduled on, so a body in flight at the stop leaves no task
  behind for the next term.
- **A second check of the lifetime sits immediately before each effect that leaves the process:** the replacement provision,
  the surplus drain and the durable-stall operator warning. It narrows the window and does not close it: a stale leader is
  refused for the provision and the drain only by a fence at the receiver, which is a separate change.
- The debounce state (in-flight flag, pending trigger) is reset by the stop, since the cancelled debounce task no longer clears it.
