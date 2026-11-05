### Changed
- Internal: the capacity lifecycle now reads and writes the per-node reservations and the fleet counter through one `CapacityRecords` port (O3-2, the storage seam of the per-node lifecycle record). Behaviour is unchanged: the golden transition table is identical and the commands the lifecycle submits are byte-identical to before.
