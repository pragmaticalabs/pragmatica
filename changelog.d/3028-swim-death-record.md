### Fixed
- SWIM: a death record (FAULTY incarnation and boot token, snapshotted at the FAULTY edge) now refuses a stale aliveness claim for a declared-dead node after its tombstone expires; it is cleared by a refutation at a higher incarnation, a new process token, or retirement (#3028).
