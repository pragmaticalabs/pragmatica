### Fixed (2026-11-24 — #2536: a checkpoint block put that failed indeterminately was never deleted)
- A checkpoint block put that failed with its outcome unknown (a write quorum missed while some replicas stored the block) left that
  block in storage for good: no pointer names it, the retry on the next 30 s tick writes a block of its own, and nothing deleted the
  first. The substrate now records the id of such a block (the hash of the frame, known although the put returned none) per pointer, and
  deletes the recorded blocks through `StorageInstance#delete` once a later save of the same pointer is confirmed (the re-read pointer
  names that save's block). The delete runs after the same grace as a displaced block and checks the pointers when it runs, so a block a
  pointer names is kept; a delete the store refuses past the retry bound stays recorded for the next confirmed save.
- Limits: at most 16 blocks are recorded per pointer, the oldest forgotten past that (WARN); the list is in memory, so a restart before a
  confirmed save leaks the recorded blocks (the L-12 class, already accepted).
