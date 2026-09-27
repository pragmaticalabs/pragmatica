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
- With `replicas = 3, min-sync-replicas = 3` a publish now acks only after both replicas hold the event,
  and the two surviving replicas hold every acked event when the owner is killed immediately after the
  last ack. Serving that data after the owner's loss is still blocked by #1550 (ownership never leaves the
  killed node); the Forge test asserts that stall as a tripwire.
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamAckedRecordsOwnerKillTest.java`]
- `test-stream-repl` now declares `replicas = 3, min-sync-replicas = 3`. The unused
  `StreamConfigParser.parse(String)`, a second, unvalidated section parse with no caller, is removed.
