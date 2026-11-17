### Fixed (2026-11-12 — #2642 items 1+2: a same-term consumer-group re-activation could tie an in-flight rebalance epoch)
- **The rebalance counter restarted on every leader re-activation.** A re-activation at the same `(incarnation, term)`
  reseeded only from the COMMITTED epoch, so a batch the previous activation had submitted but Rabia had not yet
  committed was invisible to it and the new activation could stamp the same epoch; the KV fence accepts an equal
  epoch, so whichever committed last won, older member list included. The counter now lives on the coordinator and
  is shared by every activation; the mint is one atomic `updateAndGet`, so the in-flight batch carries a strictly
  lower epoch and the fence refuses it.
- A refused mint now also advances the counter (a gap; only order matters).
- Pinned (no behaviour change): a deposed leader's non-last-member leave is refused with `SUPERSEDED` before submit,
  and a leave-derived batch that commits late is refused by the KV fence.
- Not covered: a same-term re-entry across a process restart (counter restarts at 0); unverified that #1525 closes it.
