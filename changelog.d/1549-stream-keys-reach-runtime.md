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
  committed config carries every declared key. Deploy validation applies the parser's rules to the blueprint's `resources.toml` only;
  at slice activation the same parser, with the same typed refusals, reads the slice's composite configuration (`resources.toml` plus `slice.toml`, node configuration, the KV overlay and environment), so a value an overlay contributes is validated at activation, not at deploy.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamSectionBindingTest.java`]
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamAckedRecordsOwnerKillTest.java`]
- A key directly under `[streams.X]` that the parser does not read is refused — at deploy under the rule
  `unknown-stream-key`, at activation as a configuration failure — naming the key it resembles
  (`min_sync_replicas` → `min-sync-replicas`), including a quoted dotted key or a sub-table other than
  `consumers.*`. A malformed, overflowing, non-integer or below-minimum value — `"1.5MB"`, `"5 min"`,
  `"999999999999999d"`, a count of 0, `partitions = 0` — is refused under `stream-key-invalid` as a typed
  `StreamDeclarationError` at deploy and at activation alike; before, once these values reached the
  runtime, they threw (`NumberFormatException`, a division by zero, a negative allocation) or wrapped
  silently into negative bounds. `consistency_mode`, which #1262 pinned because the record binder read it,
  is now one of the unknown keys.
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/validation/StreamResourceValidatorPartitionTest.java`]
- A section-binding resource factory with no slice configuration provider in its context is refused
  (`ResourceProvisioningError.SectionBinderNeedsProvider`) instead of falling back to the record binder's
  silent defaults — reachable only when a slice loads without a slice-composite, or through the
  context-free `provide(type, section)` overload.
- With `replicas = 3, min-sync-replicas = 3` a publish now acks only after both replicas hold the event.
  Killing the owner immediately after the last ack, then joining a fresh-id replacement, loses no acked
  event: both survivors hold all of them, and the surviving replica that takes ownership serves all of
  them in order (3 of 3 runs on a 5-node Ember cluster; the committed `minSyncReplicas = 3` is asserted before
  the kill as well as after it). Ownership was observed to move only after the
  replacement joined; with no membership event after the kill it does not move (#1550).
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamAckedRecordsOwnerKillTest.java`]
- A dedicated `test-stream-acked` blueprint (`replicas = 3, min-sync-replicas = 3`) carries the Forge
  test, so no existing fixture's semantics change for it. The unused
  `StreamConfigParser.parse(String)`, a second, unvalidated section parse with no caller, is removed.
