### Fixed (2026-09-29 — #1599: the local artifact cache could hand a later boot a torn jar)
- **`RemoteRepository` cached a downloaded jar with an unforced write to a temp file in the system temp
  directory, then a `moveReplace` that the JDK performs as copy-and-delete across filesystems.** A crash
  or power loss mid-write could leave a truncated jar at the final path, which the next boot loaded.
- The jar is now written to a sibling temp file in its own directory and forced, renamed over the target
  in one atomic rename, and the directory is forced. A failed write leaves nothing at the final path and
  keeps any previous complete jar. A `.sha256` sidecar is published next to it.
- On a cache hit, a jar that no longer matches its `.sha256` (or Maven's `.sha1`) sidecar is evicted and
  fetched again instead of loaded. A jar with no sidecar (installed by Maven itself) cannot be checked and
  is used as before.
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/repository/maven/ArtifactCacheTest.java`,
  `RemoteRepositoryCacheWiringTest.java` — local HTTP server and a temp local repository; crash simulated
  by a writer that fails mid-write, not by a power cut]
