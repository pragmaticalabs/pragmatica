### Fixed (2026-10-04 — #1919: a version-level `maven-metadata.xml` was refused as an unparseable path)
- **`<group>/<artifact>/<version>/maven-metadata.xml` (and its `.md5`/`.sha1`) answered `400 Cannot parse path`** for
  PUT and GET, telling a client its well-formed request was malformed. That is the form Maven writes for a SNAPSHOT
  version, which the built-in store does not hold (#1778), so it is now recognised and refused accurately: PUT
  answers `400` and GET answers `404`, both saying "Version-level maven-metadata.xml is not supported: it exists
  only for SNAPSHOT versions ... use the artifact-level maven-metadata.xml, or publish a release version". Nothing
  is stored. Not served on purpose: a release `mvn deploy` never writes it, and serving it would imply SNAPSHOT
  support the store deliberately lacks. Artifact-level metadata is unchanged, and a genuinely malformed path still
  answers `Cannot parse path`.
