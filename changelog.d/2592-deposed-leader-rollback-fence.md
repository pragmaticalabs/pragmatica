### Fixed (2026-11-02 — #2592: a deposed leader's ALL_OR_NOTHING rollback passed the leader fence)
- **A deposed leader could write a rollback under the new leader's record.** The rollback transaction carried the
  committed `LeaderValue` read at build time, and the applier checks only that the transaction carries the committed
  value, not that its submitter is the leader it names. A node that had applied the new leader's record but not yet
  deactivated built a rollback the applier accepted. The rollback is now issued only when the committed record names
  this node and its term is active; otherwise nothing is submitted and the apply is left to the leader the record names.
