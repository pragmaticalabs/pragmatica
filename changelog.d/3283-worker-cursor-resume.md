### Fixed (2026-11-25 — #3283: a worker-hosted stream consumer resumed from the reset point)
- **A consumer on a worker node resumed from the group's reset point instead of its committed cursor**, redelivering
  (`earliest`) or skipping (`latest`) everything a predecessor had committed: a worker's replica excludes cursor
  checkpoints, so its resume read an empty mirror and took "not replicated here" for "no cursor". A worker now reads the
  committed cursor through consensus — a `KVCommand.Get` forwarded to a core over the same transport as its forwarded
  commits (#3116) — and the rewind epoch its reconcile pass acts on takes the same read. A core node's path is unchanged.
- Only an answer that the cursor is absent starts a partition at the reset point. A read that gets no answer (no core
  peer, a forward or apply timeout, an error or malformed reply) fails the fetch: the consumer does not start, retries
  with backoff, and raises the new `stream-consumer-cursor-unreadable` warning once per run of failures, closed by
  `stream-consumer-cursor-readable`.
- Guarantee: a FOUND or ABSENT answer reflects every checkpoint whose consensus commit completed before the read was sent
  `[mechanism: the Get is decided in a Rabia slot after every slot decided before it was submitted, and the core answers
  only when it applies that slot]`. A commit still in flight while the read runs may land before or after it.
