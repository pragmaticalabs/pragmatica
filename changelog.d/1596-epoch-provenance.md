### Added (2026-09-28 — #1596: owner-epoch provenance for stream partition logs, storage and epoch plumbing)
- **Every stream partition log now records which owner epoch first wrote each of its records**, durably and
  beside the log (spec #1569 §7.5.1). That is what allows two copies of a partition to be told apart as
  different histories, where before they could only be ranked by head. The promotion gate that consumes it is a
  follow-up.
- Epoch history format v2 stores each epoch as an opaque key ordered by the caller. A v1 history held one number
  per epoch and was never written by production. It now reads as empty, so its log counts as having no provenance
  and is flagged rather than trusted (pre-GA, no migration). Pinned by `AppendLogEpochHistoryTest`.
- `AppendLog.write(offset, payload, ts, key, order)` records the epoch start under the same write lock, before
  the first frame of that epoch `[mechanism: the history entry is made durable before the frame is written]`.
  - A history entry that cannot be made durable fail-stops the log, as a failed frame write does.
  - A key older than the last one is refused without writing anything, and the log stays writable.
- Recording sites `[mechanism: one attributed-write path, fed by each append's own epoch]`, pinned by
  `PartitionProvenanceRecordingTest`:
  - An owner publish, single or batch, records the owner's stamp.
  - A live replica receive records the batch epoch.
  - A catch-up apply records nothing itself. It checks the source's history slice against its own (N13), then
    installs it. On a mismatch nothing is applied, and the partition is quarantined at the first offset that
    differs.
  - A log that holds records but has no history records nothing (the empty-history rule).
  - An append of an epoch older than one the log already records is refused before the ring takes it
    (`StreamError.ProvenanceRegression`).
- The source now ships its history with every replica catch-up answer. `ReadForwardResponse.history` is a
  layout widening, and `ProvenanceEntry` takes wire tag 1711. Pinned end to end over the forward path by
  `CatchUpProvenanceTest`.
- `ProvenanceComparison` is the divergence rule of §7.5.2 as one pure function, shared by cold-restart detection
  (AD7) and the promotion gate. `LogProvenance.SOURCE_ORDER` ranks by last epoch, then by head. Pinned by
  `ProvenanceComparisonTest`, over the rev1569 T7 sequences F1, N3, S-1, R5-1, (l) and (p).
- `[unverified: #1625 — the epoch key reserves a per-incarnation ULID slot that nothing populates until #1529
  part 2. Until then, two lineages that reuse an incarnation can mint equal epochs, and their histories compare
  as consistent]`
- `[unverified: no two owners share an epoch — #1230, #1529]`
