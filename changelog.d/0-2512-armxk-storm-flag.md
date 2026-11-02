### Added
- The arm X/K test harness records each core's slots, decisions, commits, pending consensus batches (count and oldest age) and heap in every measurement window, and flags a window as an idle storm (more than 50 slots per commit while a batch is pending), dumping the holder's head pending batch (#2512). Test scope only.
