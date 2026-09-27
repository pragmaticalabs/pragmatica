### Fixed (2026-09-27 — #1549: blueprint stream keys min-sync-replicas / max-event-size never reached the runtime)
- **A `[streams.X]` section was read by two binders that disagreed**: deploy validation used
  `StreamConfigParser` (dashed keys, `retention` as a type plus a value, size strings), while slice
  activation used the generic record binder, which reads `StreamConfig`'s snake_case component names and
  resolves anything it does not find from `StreamConfig.DEFAULT`. A validated declaration was therefore
  provisioned as follows before this fix — only the two keys whose spellings agree survived:

  | Key | Provisioned as |
  |---|---|
  | `partitions`, `replicas` | the declared value |
  | `min-sync-replicas` | `0` — every blueprint stream acked on the owner's local write |
  | `max-event-size` | 1 MiB |
  | `retention`, `retention-value`, `retention-mode`, `max-age`, `max-count`, `max-bytes` | the default `RetentionPolicy` |
  | `auto-offset-reset` | `earliest` |
  | `consistency` | `EVENTUAL` |
  | `compression` | `NONE` |
  | `encryption-key-id` | none |

- The stream resource factories now bind their section with the deploy-time parse
  (`StreamConfigParser.parseStreamConfig` over the slice's configuration provider, via a new opt-in
  `ResourceFactory.sectionBinder()` that `SpiResourceProvider` prefers over the record binder), so the
  committed config carries every declared key and deploy and runtime cannot disagree.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamSectionBindingTest.java`]
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamAckedRecordsOwnerKillTest.java`]
- A key directly under `[streams.X]` that the parser does not read is refused — at deploy under the rule
  `unknown-stream-key`, at activation as a configuration failure — naming the key it resembles
  (`min_sync_replicas` → `min-sync-replicas`); a non-integer `partitions`/`replicas`/`min-sync-replicas`
  is refused under `stream-key-not-integer`. `consistency_mode`, which #1262 pinned because the record
  binder read it, is now one of these unknown keys.
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/validation/StreamResourceValidatorPartitionTest.java`]
- With `replicas = 3, min-sync-replicas = 3` a publish now acks only after both replicas hold the event.
  Killing the owner immediately after the last ack, then joining a fresh-id replacement, loses no acked
  event: both survivors hold all of them, and the surviving replica that takes ownership serves all of
  them in order (3 of 3 runs on a 5-node Ember cluster). Ownership was observed to move only after the
  replacement joined; with no membership event after the kill it does not move (#1550).
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamAckedRecordsOwnerKillTest.java`]
- **Behaviour clarification — time/size retention also cap at the default count/bytes unless declared;
  eviction at whichever limit is hit first.** `retention = "time"`, `"size"` and `"compound"` built a policy
  with every undeclared bound at `Long.MAX_VALUE`; they never reached the runtime before this fix, and once
  they did, stream creation THREW (the ring's index is sized from the count). Every bound they do not
  declare is now the `RetentionPolicy` default (100,000 events, 256 MB, 24 h), and a count the ring cannot
  index is refused as `StreamError.RetentionCountUnindexable` instead of throwing. The shipped
  `examples/notification-hub` (`retention = "time"`, `"5m"`) now resolves to
  `RetentionPolicy[maxCount=100000, maxBytes=268435456, maxAgeMs=300000, mode=ANY]` and its stream
  creates; before this fix it silently ran at the 24 h default, and with the binder fixed alone it would
  have failed to create. [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamSectionBindingTest.java`]
- **Keys the streaming spec documented but nothing ever read — `backpressure`, `storage`,
  `storage-instance` — are now refused** as `unknown-stream-key` instead of being ignored.
- A dedicated `test-stream-acked` blueprint (`replicas = 3, min-sync-replicas = 3`) carries the Forge
  test, so no existing fixture's semantics change for it. The unused
  `StreamConfigParser.parse(String)`, a second, unvalidated section parse with no caller, is removed.
