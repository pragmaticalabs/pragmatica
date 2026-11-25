### Fixed (2026-11-25 — #3283: a worker-hosted stream consumer resumed from the reset point)
- **A consumer on a worker node resumed from the group's reset point instead of its committed cursor**, redelivering
  (`earliest`) or skipping (`latest`) everything a predecessor had committed: a worker's replica excludes cursor
  checkpoints, so its resume read an empty mirror and took "not replicated here" for "no cursor". A worker now reads the
  committed cursor through consensus — a `KVCommand.Get` forwarded to a core over the same transport as its forwarded
  commits (#3116) — and the rewind epoch its reconcile pass acts on takes the same read. A core node's path is unchanged.
- The rewind-epoch read costs one consensus command list per reconcile pass (every 5 s) per worker that hosts consumers,
  carrying one `Get` per subscription the pass holds, with at most one such read outstanding per node; a pass that finds
  it unanswered sends none. The pass acts on what earlier reads answered: an epoch is as old as the last answered read
  for a subscription held since that read was sent (only the latest pass's subscriptions are kept). A stale epoch (a
  slow answer, a failing read, a checkpoint deleted and recreated at a lower epoch, which the rewind fence does not
  cover) never moves the position: the restart abandons the consumer without a flush and re-fetches its cursor through
  the authoritative read. It costs at most one restart per pass, and none once a read sent after the change has answered.
- Only an answer that the cursor is absent starts a partition at the reset point. A read that gets no answer (no core
  peer, a forward or apply timeout, an error or malformed reply) fails the fetch: the consumer does not start, retries
  with backoff, and raises the new `stream-consumer-cursor-unreadable` warning once per run of failures of one consumer
  key, closed by `stream-consumer-cursor-readable`; a successor consumer for the same key takes an open run over.
- Guarantee: a FOUND or ABSENT answer reflects every checkpoint whose consensus commit completed before the read was sent
  `[mechanism: the Get is decided in a Rabia slot after every slot decided before it was submitted, and the core answers
  only when it applies that slot]`. A commit still in flight while the read runs may land before or after it.
