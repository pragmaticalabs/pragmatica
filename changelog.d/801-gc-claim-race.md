### Fixed (2026-09-21 — #801: GC filter-then-delete race: a concurrent claim can resurrect an orphan between scan and delete)
- **GC scanned a snapshot of the lifecycle records and then deleted on that verdict, while a deduplicating
  `put` for the same content credited the scanned orphan and handed out its id.** From the producers:
  `DefaultStorageGarbageCollector.collectGarbage` filters `listAllLifecycles()` (a copy) and calls
  `deleteFromPrivateTiers(id)`, which deleted the tier bytes and then `removeLifecycle`d unconditionally;
  `DefaultStorageInstance.handlePut`, on a lost `claimBlock`, incremented the existing record and returned the
  id without looking at whether the increment landed. Three interleavings broke `put`'s contract that an id
  it returns is readable: the dedup landing between scan and delete (bytes deleted under a live refCount 1,
  record removed, and the cycle counted it as collected); the put arriving while the tier delete ran (same
  outcome); and the record vanishing between the lost claim and the credit (the id returned with no record
  and no bytes). [verified: `StorageGarbageCollectorClaimRaceTest` — three seam-driven interleavings, each
  red on `d444d22c6` with `the id put handed out must be readable -- GC deleted the block under it`]
- **Per operation, what the fix earns and how.** *Collect:* `deleteFromPrivateTiers` now takes the record AS
  SCANNED and takes it FIRST by compare-and-remove (`MetadataStore.releaseClaim(id, scanned)` —
  `ConcurrentHashMap.remove(key, value)`); any touch after the scan (a dedup credit, a read's access stamp, a
  ref) changes the record, the remove fails, nothing is deleted and the cycle counts 0 for it. *Put after the
  record is gone:* its claim succeeds instead of deduplicating; the instance's `collecting` map, written
  BEFORE the compare-and-remove, holds a promise per in-flight collection and the claimant chains its tier
  write behind it, so GC's tier deletes cannot wipe the fresh bytes. *Dedup:* the credit counts only if
  `computeLifecycle` reports it landed; an empty result means the record was taken and the put goes round
  again (claim succeeds, write ordered behind the collection). *Failed tier delete:* the scanned record is
  put back with `claimBlock` (only if no claimant took the slot) so the next cycle retries, as before — and
  INSIDE the resolution of the failed delete, before the claimant is released and before the returned promise
  resolves, not as an `onFailure` on the promise chain (which the executor runs after the caller has already
  seen the result; measured absent-on-return 19/200 with an async-failing tier, rev1411 P4).
  [mechanism: `DefaultStorageInstance.deleteFromPrivateTiers`, `collected`, `afterCollection`, `deduplicateBlock`]
  [verified: `claimantChainedBehindFailedTierDelete_keepsItsRecordAndIsReleased` — reds when the restore is
  unconditional (`createLifecycle`) and when the claimant is released only on success;
  `asyncTierDeleteFailure_restoresRecordBeforeTheCollectionResolves` — the test thread fails the held delete
  and the restore must run on that thread; red with the restore on `onFailure` (ran on a virtual executor
  thread)]
  [unverified: the happens-before between GC's `collecting.put` and a claimant's `collecting.get` rests on
  `ConcurrentHashMap`'s memory effects for a removal observed by a later `putIfAbsent` on the same key — the
  same assumption `claimBlock`/`releaseClaim` already make; not pinned by a test]
- Recorded, not changed: explicit `delete(id)` keeps the delete-then-unconditional-remove shape and a deduplicating
  put landing during its tier delete still gets an unreadable id (rev1411 P3, red at this head). That path is
  #981's, which reuses the take-the-record-first shape from here.
- Scope: the exclusion is node-local, matching the operation — GC deletes only this node's private tiers on
  this node's metadata (#250). A deduplicating put on ANOTHER node against a cluster-shared record is not in
  this ticket. `ContentStore.delete`'s own refcounting is untouched (#981).
