### Changed (2026-09-27 — #1549: declared stream retention and event-size limits now take effect)
- **Behaviour change: `time`/`size` retention also cap at the default count/bytes unless declared; eviction
  at whichever limit is hit first.** `retention = "time"`, `"size"` and `"compound"` built a policy with every
  undeclared bound at `Long.MAX_VALUE`; they never reached the runtime before #1549, and once they did, stream
  creation THREW (the ring's index is sized from the count). Every bound they do not declare is now the
  `RetentionPolicy` default (100,000 events, 256 MB, 24 h), and the engine refuses a retention it cannot build
  as a typed failure (`StreamError.RetentionBoundInvalid` below 1, `StreamError.RetentionCountUnindexable`
  past the indexable capacity) instead of throwing.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamSectionBindingTest.java`]
- **The shipped `examples/notification-hub` changes behaviour.** Its `[streams.notifications]` declares
  `retention = "time"`, `"5m"` and `max-event-size = "64KB"`; until #1549 both were silently replaced by the
  defaults (24 h retention, 1 MiB events). It now runs at
  `RetentionPolicy[maxCount=100000, maxBytes=268435456, maxAgeMs=300000, mode=ANY]` with a 64 KB event
  limit: events older than 5 minutes are evicted and events over 64 KB are refused at publish. The same
  64 KB limit now applies to every fixture declaring `max-event-size = "64KB"`.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamSectionBindingTest.java`]
- **Keys the streaming spec documented but nothing ever read — `backpressure`, `storage`,
  `storage-instance` — are now refused** as `unknown-stream-key` instead of being ignored.
