### Fixed
- The projection commit hook no longer fails the commit it observes: a registry that throws (custom registry, null consumer group) is counted on `reportFailureCount` and logged once per group and partition, and the commit's outcome is returned unchanged.
