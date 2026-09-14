### Fixed (2026-09-14 — #281: artifact-repo keyed by GAV only, so a coordinate's pom collided with its jar; delete kept the version listed; `<latest>` was deploy order)
- **`ArtifactStore` keyed every file of a Maven coordinate under one GAV key**, and
  `MavenProtocolHandler` parsed the classifier and extension only to discard them. A standard
  `mvn deploy` PUTs the jar then the pom: the pom came back `already-present` and was dropped, and
  `GET ….pom` served the jar's bytes; a `-sources.jar` collided the same way. Every file is now its
  own entry: `ArtifactFile(artifact, classifier, extension)` is the store's unit, the `Artifact`-typed
  operations address the coordinate's PRIMARY file (`jar`, no classifier) so slice resolution keeps
  its signature, and the handler passes the parsed file through.
  [verified: `aether/resource/services/artifact-repo/src/test/java/org/pragmatica/aether/resource/artifact/MavenFileRoundTripTest.java`
  — jar then pom both `uploaded`, each GET serves its own bytes, a classified jar is distinct, a
  never-deployed sibling is 404; red at `2005ea7d2`]
- **Storage format (CTO ruling, session 19):** one metadata key per file,
  `artifacts/<group>/<artifact>/<version>/<[classifier.]extension>/meta` — `…/jar/meta`,
  `…/pom/meta`, `…/sources.jar/meta` — the primary included, no special case. The pre-#281
  `…/<version>/meta` key is **not read**: artifact-store contents are cluster DHT state with no
  pre-GA compatibility promise, and a dual-read path would otherwise stay forever. The versions
  list stays per coordinate (a pom deploy re-registers the same version, idempotently).
  [verified: `ArtifactStoreTest.KeyShapeTests` pins the literal key strings and that the legacy key
  is not consulted — a renamed segment reddens it]
- **`delete` removed only the metadata key and left the version listed.** It now also drops the
  version from the versions list when the PRIMARY file is deleted (a version without its jar is
  not resolvable; deleting a sidecar leaves the version listed). The content chunks are still not
  released: `[known: chunks are content-addressed and shared across artifacts and the
  cluster-shared DHT tier — a re-deploy of unchanged bytes under a new version shares every chunk,
  so releasing them safely needs a cluster-wide reference index; filed separately by the CTO]`.
  Note `ArtifactStore.delete` has no production caller today (`ARTIFACT_DELETE` is unserved, #1102).
  [verified: `ArtifactStoreTest.FileDeleteTests` — primary delete empties the entry, sidecar delete
  keeps it, one of two versions survives]
- **`maven-metadata.xml` reported the LAST DEPLOYED version as `<latest>`.** `<latest>` is now the
  highest version and `<release>` the highest non-SNAPSHOT one, with `<versions>` ascending, by
  `VersionOrder`: numeric `major.minor.patch`, then Maven's canonical qualifier order
  (`alpha`/`a` < `beta`/`b` < `milestone`/`m` < `rc`/`cr` < unknown < `snapshot` < none/`ga`/`final`/
  `release` < `sp`), equal tokens by their number. This is not Maven's full `ComparableVersion`
  algorithm (no dotted qualifier lists). [verified: `VersionOrderTest` (16 ordered pairs incl.
  `1.0.0-rc4` < `1.0.0`, `1.0.0-SNAPSHOT` < `1.0.0`, `1.0.0` < `1.0.0-sp1`, `alpha` < `beta` < `rc1`),
  `MavenFileRoundTripTest#mavenMetadata_latestAndReleaseFollowVersionOrder_notDeployOrder`]
- `ArtifactStoreError` records now name the file (`org.example:lib:1.0.0:pom`), so a missing pom is
  reported as a missing pom. [unverified: the versions-list get-then-put race (ticket item 4) is
  unchanged and now also applies to removal; the hash helpers' swallowed exceptions and the
  checksum GET's 404-for-500 (item 4) are untouched.]
