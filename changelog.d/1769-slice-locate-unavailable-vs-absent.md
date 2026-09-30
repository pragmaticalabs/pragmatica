### Fixed (2026-09-30 — #1769: "Artifact not found" could not tell an absent artifact from an unreachable one)
- **`SliceStore`'s repository lookup swallowed every failure into `ArtifactNotFound`.** It chained repositories with
  `.orElse`, so a timeout or network error on one repository ended as "Artifact not found in any repository", and a log
  line could not say whether the artifact was absent or merely unreachable under node churn.
- `ArtifactNotFound` is now returned only when **every** repository answered "absent". If any repository could not
  answer, the load fails with the new `Intermittent.ArtifactUnavailable`, whose message lists each repository's outcome
  in lookup order (`repository #0 unavailable: ...`, `repository #1 absent: ...`).
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/SliceStoreLocateTest.java`]
- "Absent" is typed by the marker `Repository.Absent`: a missing local file, an HTTP 404 from a remote, the built-in
  store's own not-found. Any other remote status, a timeout or a DHT error stays a plain failure.
- **Disposition is unchanged**: both causes are `Intermittent`, so the leader retries either under the same budget; only
  the message text differs. [unverified: that a DHT read of a live artifact under repair cannot itself answer "not found"
  — that case still reads as absent]
