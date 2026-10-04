### Fixed (2026-10-04 — #1436: a transient `[shared]` locate failure registered the library as runtime-provided)
- **`SharedDependencyLoader.locateOptional` folded EVERY locate failure to "not found".** A repository timeout,
  network error, corrupt download or an unparseable coordinate therefore registered the `[shared]` library as
  present at the requested version with no jar loaded anywhere: the slice load succeeded and failed later with
  `NoClassDefFoundError`, and the retry the `Intermittent` typing exists for never happened. Only a genuine
  not-found (a repository's `Repository.Absent` answer, or the composite's `ArtifactNotFound` once every
  repository said so, #1769) now takes the runtime-provided path; everything else surfaces as itself and fails
  the load `Intermittent`. The composite half of the problem (keeping the unavailable cause, typing not-found)
  was already done by #1769.
