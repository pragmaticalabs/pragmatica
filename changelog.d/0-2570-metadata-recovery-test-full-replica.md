### Fixed
- `HierarchicalMetadataRecoveryTest` now asserts the QJ-7 full-replica behaviour (a worker holds the other community's activation directive); #2570 was a deterministic red since W4, not a flake.
