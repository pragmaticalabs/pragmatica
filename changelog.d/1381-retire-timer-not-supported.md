### Removed (2026-10-04 — #1381: `EntityError.TimerNotSupported` retired)
- **The sealed `EntityError` variant `TimerNotSupported` is gone.** After #1286 and #1270 moved the in-memory durable
  entities to test sources, no production path constructs it: the fenced-log backing a node provisions schedules and cancels
  timers as fenced writes (#345 I4). Every slice matching the sealed `EntityError` no longer carries an arm no production
  path reaches. The two test-only backings (`InMemoryDurableEntity`, `FencedDurableEntity`) now refuse a timer with
  `StorageFailed`. Pre-GA, so no migration path.
  [mechanism: `EntityError` is not wire-pinned (no tag in `SystemTags`, no line in the wire baseline); a repo-wide `git grep` of the name
  finds only history (CHANGELOG, handovers, changelog fragments).]
- **Breaking (source):** a slice naming `EntityError.TimerNotSupported` no longer compiles; delete that arm. `EntityError` is
  `DurableEntity`'s public sealed type.
