### Fixed (2026-11-26 — #3528: API-key audit entries were never removed)
- **The API-key audit is bounded: the newest 1000 entries per key are kept.** Key create, rotate, revoke, expire and the
  bootstrap admin key each `Put` an audit entry into the consensus KV store, and nothing ever removed one, so every key
  operation grew the replicated state, every snapshot and every backup without limit. The cluster leader's existing
  60-second key sweep now also removes, through a consensus batch, every entry beyond the newest 1000 of its key
  (ordered by the entry's timestamp), at most 1000 removals per sweep. No node prunes its own store, and an entry of
  another key is never touched. The bound is a count enforced once per sweep, so between two sweeps a key can hold
  more than 1000 entries (the writes of at most one sweep period); a leader change leaves the next leader's sweep to
  finish the job.
- **Restoring an old, unbounded backup still succeeds.** The restore applies every decoded entry as before (the backup
  codec is unchanged and imposes no bound); the first leader sweep after it removes the excess, over several sweeps
  when it exceeds 1000 entries.
- The Management API (`GET /api/v1/cluster/keys/audit`) and the guarantees reference state the bound.
