### Fixed (2026-09-29 — #1195: ParameterMismatch is the wrong failure record for a class-resolution failure)
- **A slice whose referenced class is missing from a loader that serves its package failed with
  "Parameter mismatch in …"**, sending the operator to the factory's signature.
- It now fails with its own record, `SliceLoadingFailure.Fatal.ServedPackageLacksClass`, and the message
  starts "Class resolution failed in …". The evidence and the two possible causes (#758) are unchanged.
  `[mechanism: SliceFactory.classLoadFailure's served-package branch builds the new record; pinned by SliceFactoryTest]`
