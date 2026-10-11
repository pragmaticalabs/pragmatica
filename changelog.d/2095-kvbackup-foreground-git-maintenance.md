### Fixed
- The KV backup's git commands run auto-gc and auto-maintenance in the foreground (`gc.autoDetach=false`, `maintenance.autoDetach=false`) instead of daemonizing it: the repository still compacts, but nothing keeps writing into the backup directory after a git command returned, past a stopped worker (#2095).
