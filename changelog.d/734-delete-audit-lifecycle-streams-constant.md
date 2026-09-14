### Removed (2026-09-14 — #734: `AuditLifecycleStreams` was a dead constant whose doc described a gate-evading creation path)
- **Decision: delete.** `AuditLifecycleStreams.AUDIT_LIFECYCLE_COMMANDS` (topic `audit.lifecycle.commands`)
  was referenced by nothing — no `createStream`, no `SystemStreams.ALL` entry, no publisher; the lifecycle
  audit publisher and `RecentCommandsBuffer` it was written for were deleted earlier (the `AetherNode`
  comment at the system-stream registration point records it). Its doc comment described creating the
  stream from `AetherNode.start()` via `StreamPartitionManager.createStream` directly — a third
  framework-internal creation path that bypasses both `SystemStreamBootstrap`/`SystemStreams.ALL` and
  slice DI, which is exactly the gap the #300 write-gate cannot see. Nothing is planned for the topic, so
  wiring it (option 1 of the ticket) would materialise a stream no consumer reads; per the ticket's own
  caution the name is NOT added to `SystemStreams.ALL`, whose bootstrap iterates it to create streams.
  The `audit` package is now empty and gone. [mechanism: `git grep 'AuditLifecycleStreams|AUDIT_LIFECYCLE|audit\.lifecycle'`
  over `*.java` and `*.toml` returns nothing; root `mvn clean install -DskipTests` 145/145;
  `SystemStreamsTest#all_engineKeys_matchExpectedEnumeratedSet` still pins the enumerated set, its doc
  updated]
- Left as-is and noted: `cli.md`'s `aether cluster audit` section still narrates `audit.lifecycle.commands`
  events for a command that does not exist — already recorded as known drift in the cli-docs gate's
  waiver (`command: aether cluster audit`); removing the section is that waiver's own work item, not this
  constant's.
