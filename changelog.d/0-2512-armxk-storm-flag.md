### Added
- The arm X/K test harness records each core's slots, decisions, commits, pending consensus batches (count and oldest age) and heap in every measurement window, and flags a window as an idle storm when its cores run more than 50 consensus slots per committed mutation, dumping every core's pending batch queue as data (#2512). Test scope only.
