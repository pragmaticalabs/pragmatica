### Fixed (2026-09-14 — #281: artifact-repo keyed by GAV only, so a coordinate's pom collided with its jar; delete kept the version listed; `<latest>` was deploy order)
- **`ArtifactStore` keyed every file of a Maven coordinate under one GAV key**, and
  `MavenProtocolHandler` parsed the classifier and extension only to discard them. A standard
  `mvn deploy` PUTs the jar then the pom: the pom came back `already-present` and was dropped, and
  `GET ….pom` served the jar's bytes; a `-sources.jar` collided the same way. Every file is now its
  own entry: `ArtifactFile(artifact, classifier, extension)` is the store's unit, the `Artifact`-typed
  operations address the coordinate's PRIMARY file (`jar`, no classifier) so slice resolution keeps
  its signature, and the handler passes the parsed file through — including Maven 3's timestamped
  SNAPSHOT names (`lib-1.0.0-20260914.010203-1-sources.jar` under `1.0.0-SNAPSHOT/`), which the
  first round still read as unclassified (verify-1132 B1); the plain `lib-1.0.0-SNAPSHOT-sources.jar`
  name addresses the same entry.
  [verified: `aether/resource/services/artifact-repo/src/test/java/org/pragmatica/aether/resource/artifact/MavenFileRoundTripTest.java`
  — jar then pom both `uploaded`, each GET serves its own bytes, a classified jar is distinct, a
  never-deployed sibling is 404 (red at `2005ea7d2`); `#timestampedSnapshotDeploy_keysEveryFileSeparately`
  — jar/pom/sources/javadoc of one timestamped deploy each under their own `<file>/meta`,
  `<versions>` lists `1.0.0-SNAPSHOT` once (red at `70059cfb6`)]
- **Storage format (CTO ruling, session 19):** one metadata key per file,
  `artifacts/<group>/<artifact>/<version>/<[classifier.]extension>/meta` — `…/jar/meta`,
  `…/pom/meta`, `…/sources.jar/meta` — the primary included, no special case. The pre-#281
  `…/<version>/meta` key is **not read**: artifact-store contents are cluster DHT state with no
  pre-GA compatibility promise, and a dual-read path would otherwise stay forever. The versions
  list stays per coordinate (a pom deploy re-registers the same version, idempotently), and each
  version keeps the list of its deployed files at `…/<version>/files`.
  [verified: `ArtifactStoreTest.KeyShapeTests` pins the literal key strings (incl. the file list's
  content) and that the legacy key is not consulted — a renamed segment reddens it]
  Note `ArtifactStore.Metrics.artifactCount` (`artifact.count` on the metrics route) now counts
  FILES — a standard deploy adds four.
- **`delete` removed only the metadata key and left the version listed.** It now also drops the
  file from the version's file list and, when that was the version's LAST file — primary or not —
  the version from the versions list (CTO ruling, round 2: a pom-only version is delisted with its
  pom; a version keeps its listing while any file of it exists). The content chunks are still not
  released: `[known: chunks are content-addressed and shared across artifacts and the
  cluster-shared DHT tier — a re-deploy of unchanged bytes under a new version shares every chunk,
  so releasing them safely needs a cluster-wide reference index; filed separately by the CTO]`.
  Note `ArtifactStore.delete` has no production caller today (`ARTIFACT_DELETE` is unserved, #1102).
  [verified: `ArtifactStoreTest.FileDeleteTests` — primary-with-pom-remaining and pom-with-jar-remaining
  keep the listing, the last file delists, a pom-only version delists with its pom, one of two
  versions survives, deleting a never-deployed file is a no-op]
- **`maven-metadata.xml` reported the LAST DEPLOYED version as `<latest>`.** `<latest>` is now the
  highest version and `<release>` the highest non-SNAPSHOT one, with `<versions>` ascending, by
  `VersionOrder`: numeric `major.minor.patch`, then the qualifier as Maven's `ComparableVersion`
  ranks it — `alpha` < `beta` < `milestone` < `rc`/`cr` < `snapshot` < none/`ga`/`final`/`release`
  < `sp` < any unknown qualifier (unknowns lexically among themselves); `a`/`b`/`m` alias only with
  a number (`a1`), bare they are unknown; equal tokens by their number; `<token>-SNAPSHOT` sorts
  directly below its `<token>` (`1.0.0-rc4-SNAPSHOT` < `1.0.0-rc4`). The first round placed
  unknowns below `snapshot` and a `<token>-SNAPSHOT` above its token and called that Maven's
  order (verify-1132 S1); every row is now checked against `ComparableVersion` 3.9. Still not the
  full algorithm: no dotted qualifier lists, no digit/letter splitting inside a token.
  [verified: `VersionOrderTest` (24 ordered pairs incl. `1.0.0-rc4` < `1.0.0`, `1.0.0-SNAPSHOT` <
  `1.0.0`, `1.0.0` < `1.0.0-sp1` < `1.0.0-custom`, `alpha` < `beta` < `rc1`, `rc4-SNAPSHOT` < `rc4`),
  `MavenFileRoundTripTest#mavenMetadata_latestAndReleaseFollowVersionOrder_notDeployOrder`]
- `ArtifactStoreError` records now name the file (`org.example:lib:1.0.0:pom`), so a missing pom is
  reported as a missing pom. The operator doc's "Key Format" (`aether/docs/operators/artifact-repository.md`)
  now states the per-file keys and that chunks are content-addressed, not coordinate-keyed.
  `[known: #1102 — `PUT/GET …/1.0.0-SNAPSHOT/maven-metadata.xml` (version-level metadata, the last
  step of every SNAPSHOT deploy) still fails with 400: `parseMetadataPath` reads the dotted version as
  the artifactId; pre-existing, the #1102 positional-parse family]`
  [unverified: the versions-list get-then-put race (ticket item 4) is unchanged and now also covers
  removal and the file list; the hash helpers' swallowed exceptions and the checksum GET's
  404-for-500 (item 4) are untouched.]
