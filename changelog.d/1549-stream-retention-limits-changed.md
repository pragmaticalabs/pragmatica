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
- **A durable topic's `retention` under 1 ms (`"0s"`, `"500us"`) is refused at declaration**
  (`TopicConfigError.RetentionBelowOneMillisecond`); its stream refuses such a bound at creation, so it
  previously declared cleanly and failed later.
  [mechanism: `DurableTopicSpec.durableTopicSpec` refuses it; pinned by `TopicConfigTest`]
- **`CLUSTER_EVENTS_MAX_COUNT` / `_BYTES` / `_AGE_MS` / `_EVENT_SIZE_BYTES` are checked at boot.** A set
  value must be a whole number from 1 to the bound's maximum (the count at most the ring's indexable
  capacity); otherwise the node refuses to boot naming the variable (`ClusterEventsLimits.InvalidLimit`).
  Before, an unparseable value fell back to the default silently, and `0` reached the stream engine, which
  refused the system stream while its registrar kept retrying. Unset or blank still means the default.
  Recovery: unset the variable or set it in range.
  [mechanism: `AetherNode.createNode` binds `ClusterEventsLimits` before building anything; the values are
  pinned by `ClusterEventsLimitsTest`, the boot wiring by no test — it reads the process environment]
- **A consumer's `checkpoint-interval` that is not a duration of at least 1 ms is refused at deploy** as
  `stream-key-invalid` on its stream's section; before, `"5 min"` threw `NumberFormatException` out of
  deploy validation.
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/validation/StreamResourceValidatorPartitionTest.java`]
