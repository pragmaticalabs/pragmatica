### Fixed (2026-09-20 — #1357: the per-resolve slice class loader was never closed; `SliceManifest.readFromClassLoader` was dead and parent-first)
- **Every slice resolution built a `SliceClassLoader` over the slice jar to read its dependency file
  and never closed it**, in `DependencyResolver` (two sites) and `RepositoryDependencyLoader`, so the
  jar's file handle lived until the loader was garbage-collected — a transient handle per resolution,
  not a JVM-lifetime leak, and after #1351 not a cache-poison hazard. The read now goes through
  `DependencyFile.loadFromJar`, which closes the loader before returning; a failed close is a failed
  load. `[verified: DependencyFileLoadFromJarTest — the loader is closed after the file was read (the
  content is asserted, since a loader closed too early reads as an EMPTY file, not a failure), and a
  close that throws fails the load]`
- **Removed** `SliceManifest.readFromClassLoader` and its private helpers: zero callers in the tree,
  and it resolved `META-INF/MANIFEST.MF` through `getResource`, which is parent-first, so on a slice
  loader whose parent is the app loader it would have answered with the first manifest on the parent's
  classpath rather than the slice jar's. `SliceManifest.read(URL)` reads the jar directly and is the
  only manifest reader.
