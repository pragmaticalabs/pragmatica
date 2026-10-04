### Fixed (2026-10-04 — #1833: the artifact repository answered 400 for `maven-metadata.xml` checksums)
- **`GET maven-metadata.xml.{md5,sha1}` and `GET`/`PUT maven-metadata.xml.{sha256,sha512}` were 400s**, and the
  accepted metadata PUTs answered a contentless 201 that read as "stored" while the bytes were discarded. The
  four checksums of the metadata are now served, computed from the exact bytes `GET maven-metadata.xml`
  returns; uploaded metadata and metadata checksums are accepted but answered `200 {"status":"derived", ...}`
  stating they were not stored (the repository derives them from its version index).
- **`maven-metadata.xml` keeps `<lastUpdated>`, now derived instead of read from the clock.** It was the wall
  clock, so two renders a second apart differed and a checksum fetched after the metadata could never match it.
  It is now the newest deploy time among the listed versions, from the metadata the store persists when a
  version's primary file is written (write-once), so the bytes and their checksums are stable until a version is
  added. A failed read of that metadata fails the request rather than omitting the field.
- Unchanged: `.sha256`/`.sha512`/`.asc` of artifact FILES are stored write-once files (#1778), and an artifact's
  `.md5`/`.sha1` upload remains a contentless 201 (the GET computes them).
