### Fixed (2026-09-29 — #1599: the local artifact cache could hand a later boot a torn jar)
- **`RemoteRepository` cached a downloaded jar with an unforced write to a temp file in the system temp
  directory, then a `moveReplace` that the JDK performs as copy-and-delete across filesystems.** A crash
  or power loss mid-write could leave a truncated jar at the final path, which the next boot loaded.
- The jar is now written to a sibling temp file in its own directory and forced, renamed over the target
  in one atomic rename, and the directory is forced. A failed write leaves nothing at the final path and
  keeps any previous complete jar. A `.aether-sha256` sidecar, the node's checksum and its mark that it wrote the jar,
  is published next to it.
- On a cache hit, a jar carrying the node's mark that no longer matches it is not loaded and is fetched again; it
  stays in place until the fetch succeeds and the atomic publish replaces it, so a failed refetch loses nothing. A jar that fails a Maven `.sha1`/`.sha256` checksum but was not written by the node, such as a locally built
  artifact in the operator's `~/.m2`, is never deleted or overwritten: the resolve fails with
  `CachedArtifactChecksumMismatch` naming the path, and an ERROR is logged. A jar with no sidecar is used as before.
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/repository/maven/ArtifactCacheTest.java`,
  `RemoteRepositoryCacheWiringTest.java` — local HTTP server and a temp local repository; crash simulated
  by a writer that fails mid-write, not by a power cut]
