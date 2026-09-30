### Fixed (2026-09-29 — #1599: the local artifact cache could hand a later boot a torn jar)
- **`RemoteRepository` cached a downloaded jar with an unforced write to a temp file in the system temp
  directory, then a `moveReplace` that the JDK performs as copy-and-delete across filesystems.** A crash
  or power loss mid-write could leave a truncated jar at the final path, which the next boot loaded.
- The jar is now written to a sibling temp file in its own directory and forced, renamed over the target
  in one atomic rename, and the directory is forced. A failed write leaves nothing at the final path and
  keeps any previous complete jar. A `.aether-sha256` sidecar, the node's checksum and its mark that it wrote the jar,
  is published next to it.
- On a cache hit, a jar that fails its checksum is never loaded, deleted or overwritten: the resolve fails with
  `CachedArtifactChecksumMismatch` naming the path, and an ERROR is logged. That holds for a jar failing a Maven
  `.sha1`/`.sha256` checksum, such as a locally built artifact in the operator's `~/.m2`, and equally for a jar the
  node wrote whose bytes changed since, such as an operator's `mvn install` over it: the node does not fetch over it.
  A jar with no sidecar is used as before.
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/repository/maven/ArtifactCacheTest.java`,
  `RemoteRepositoryCacheWiringTest.java` — local HTTP server and a temp local repository; crash simulated
  by a writer that fails mid-write, not by a power cut]
