### Fixed (2026-11-24 — #3266, #3268: guarantees.md app-cursor scope and dead-letter durability)
- `guarantees.md` (row 19 and the `stream.consume` bullet) now states that the app `StreamAccess` cursor is node-local: a partition-ownership change resumes from the new owner's local cursor, typically none, so from offset 0 (replay, not loss) (#3266).
- It also states the dead-letter guarantee per family: durable-topic groups dead-letter to a durable DLQ stream; declarative `[streams.X]` consumers dead-letter to process memory only, lost on that node's restart while #3267 is open (#3268).
