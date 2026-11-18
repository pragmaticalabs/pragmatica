### Fixed (2026-11-17 — #3330: a worker's halt on the removal of its own directive depended on store timing)
- **A live worker now halts on the removal of its own activation directive whatever its store holds.** The decision read
  the store when the removal was handled and skipped the halt if a directive was there, so "remove, then create in
  another community" in two transactions halted the worker or moved its runtime in place depending on apply timing. The
  halt is now decided from the delivered change alone: a configured worker that was directed and is not already
  draining halts (`EXIT_DRAINED`) and raises `own-directive-retired`.
- **The in-place move of a live worker's runtime to another community is deleted.** A worker changes community only by
  retirement and a fresh join. A directive naming the community the worker already runs in is still a repeated
  activation with one runtime owner, even when another field of the directive changed. One naming another community
  halts the worker like the removal (`EXIT_DRAINED`, `own-directive-retired`, at most once): a removal and a re-create
  committed while a worker resubscribes reach it as one such put, because a projection install delivers a changed key
  as a put, never as a remove followed by a put.
