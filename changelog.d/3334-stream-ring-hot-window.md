### Changed (2026-11-18 — #3334: a stream's ring is sized from its hot window, not its retention)
- **A user stream's off-heap ring no longer grows with its retention count.** The ring index and its data region come from
  the stream's hot window: by default 16 384 entries and a 4 MiB data region (widened to the stream's `max_event_size` when
  that is larger), whatever `retention.max_count` says. A stream declared with `max_count = 10^7` no longer asks for a
  240 MB index. [mechanism: the stream manager sizes every ring from the hot window; pinned by `StreamRingWindowTest`]
- The reservation per ring copy is a fixed floor: 1 441 856 B per user-stream copy at the defaults (growth limit
  4 587 584 B), 1 288 640 B per `entity:` stream copy (10 000 entries, 64 MiB cap); two streams whose retention counts differ
  100x reserve the same. A ring frozen at its floor still accepts one event of the stream's maximum size.
  [mechanism: the stream manager sizes every ring from the hot window; pinned by `StreamRingWindowTest`]
- A declared `[hot]` window (`max_count`, `max_bytes`) sizes the ring instead of the defaults; a dimension left undeclared
  takes its default. `system:` streams keep the window their committed retention gives them.
  [mechanism: the stream manager sizes every ring from the hot window; pinned by `StreamRingWindowTest`]
- The index bound moved with it: a hot-window count past the indexable capacity is refused at create with the typed
  `RetentionCountUnindexable`, whose message now names the hot window's `max_count`; a count the node's off-heap budget
  cannot hold is refused with `STREAM_MEMORY_EXCEEDED`. A retention count that used to be refused as unindexable
  (up to `Long.MAX_VALUE`) is now accepted, and its ring is the default window.
  [mechanism: the create-time index check reads the hot-window count; pinned by `StreamRingWindowTest` and `StreamSectionBindingTest`]
