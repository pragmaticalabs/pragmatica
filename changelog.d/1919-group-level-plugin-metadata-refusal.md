### Fixed (2026-10-04 — a group-level `maven-metadata.xml` was refused as an unparseable path)
- **`<group path>/maven-metadata.xml` (and its `.md5`/`.sha1`/`.sha256`/`.sha512`) answered `400 Cannot parse path`** for a
  two-segment group such as `org/example`. That is the plugin-prefix metadata `mvn deploy` writes for a `maven-plugin`
  packaging. It is now recognised and refused accurately (PUT `400`, GET `404`: "Group-level maven-metadata.xml is not
  supported: it is the plugin-prefix metadata Maven writes when deploying a Maven plugin, and the built-in artifact
  repository stores slices and libraries, not Maven plugins"). Not stored: the repository is an internal cache for
  deployed slices (`operators/artifact-repository.md`), and serving plugin prefixes would need the store to read each
  pom's packaging. A plugin deploy still stops at that PUT, now with a message that says why. Related to #1919
  (the version-level form). A group with three or more segments (`com/acme/tools`) is indistinguishable from an
  artifact-level path and keeps its existing handling.
