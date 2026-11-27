### Fixed (2026-11-27 — #3835: a stale epoch answer restarted a rewound stream consumer more than once)
- **A forwarding node abandoned and re-attached a declarative stream consumer once per pass, not once per answer.** The
  committed rewind epoch a forwarding node acts on is the last answered read, and a stale FOUND (a checkpoint deleted and
  recreated at a lower epoch) stays the answer until a later read is recorded. Every pass between the restart and that
  recording found the restarted consumer behind the same stale epoch and abandoned it again, discarding its unflushed
  progress. `StreamConsumerManager` now remembers the epoch it restarted each key for and restarts at most once per
  answered epoch; the memory ends with the key, or when a pass's answer for it is any other epoch or none, so a new rewind
  to the same epoch value still restarts.
