### Fixed (2026-11-13 — #3156: the stall round counter no longer survives stream destroy and recreate)
- The stream-removed listener (`clearStalledOf`) now drops the stream's stall round counters, and a stream that no longer exists counts no stalled round, so a round of the destroyed incarnation still in flight cannot put a counter back. A raise in the recreated stream needs the full `MAX_STALLED_ROUNDS` stalled rounds again.

### Changed (2026-11-13 — #3155: the all-stalled warning is settled as of the next reconcile pass)
- The documented guarantee of `reconcileStalled` is narrowed from "settled when the call returns" to "settled as of the next reconcile pass": a report raised or an owner flip landing after the pass's snapshot can stay open until the next pass, which closes it (the warning is advisory). The embedded `Stalled` model carries the same property; `models.tsv` rows `settled` and `early` are now `pass`, with known-bad controls `settledweak` and `earlynoreset`.
