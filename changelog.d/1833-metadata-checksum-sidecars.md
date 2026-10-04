### Fixed (2026-10-04 — #1833: the artifact repository answered 400 for `maven-metadata.xml` checksums)
- **`GET maven-metadata.xml.{md5,sha1}` and `GET`/`PUT maven-metadata.xml.{sha256,sha512}` were 400s**, and the
  accepted metadata PUTs answered a contentless 201 that read as "stored" while the bytes were discarded. The
  four checksums of the metadata are now served, computed from the exact bytes `GET maven-metadata.xml`
  returns; uploaded metadata and metadata checksums are accepted but answered `200 {"status":"derived", ...}`
  stating they were not stored (the repository derives them from its version index).
- **`maven-metadata.xml` no longer carries `<lastUpdated>`.** It is optional in the format, and a wall-clock
  value made every render differ, so a checksum fetched after the metadata could never match it. The metadata
  is now a pure function of the version set.
- Unchanged: `.sha256`/`.sha512`/`.asc` of artifact FILES are stored write-once files (#1778), and an artifact's
  `.md5`/`.sha1` upload remains a contentless 201 (the GET computes them).
