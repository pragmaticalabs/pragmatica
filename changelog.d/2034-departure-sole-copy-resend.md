### Fixed (2026-11-05 — #2034: a draining node's sole-copy DHT keys could be lost to one dropped push)
- **The departure push resends unacknowledged pages** until the drain window ends, under the same correlation id, instead of
  sending once. A nack backs off; a refusal from a target still in the ring retries the same target; a target removed from the
  ring is replaced by the next responsible node, with the departing set carried in `leaving`.
- **Keys whose only copy is on the draining node ("sole copy") get an extra window** beyond the base window that replicated keys
  get. A replicated key left unacknowledged is not at risk: a surviving replica holds it.
- **The push now runs inside the drain's absolute window** (drain start plus grace, less a 1 s halt slack, never re-anchored after
  quiesce) and reports a `DepartureOutcome`. A drain that halts with sole-copy keys unacknowledged, or whose inventory could not
  be enumerated in time, logs "drain completed with data at risk".
- **New operator events:** `dht-departure-sole-copy-at-risk` (CRITICAL, raised at most once per drain, subject
  `<nodeId>@<drainStartMillis>`) and its in-drain recovery `dht-departure-sole-copy-delivered` (INFO). `DeparturePushIncomplete`
  gains the `soleCopyAtRisk` and `outcome` details. The draining node waits, within its window, for these events to land before it
  halts.
- An at-risk event left open after a halt has no operator closer in this release (follow-up #2680).
