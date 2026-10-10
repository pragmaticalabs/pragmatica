### Fixed (2026-10-04 — #1102: `aether artifact info` could not read a dotted group)
- **`GET /repository/info/<group>/<artifact>/<version>` was a typed route of three single-segment
  parameters**, so a group that spans segments (`org/example`, which is how the CLI sends `org.example`
  and how the docs write it) matched no route. It is now served by `MavenProtocolRoutes` and reads the
  group as every segment before the last two, through the same parser `DELETE` uses. The one-segment
  form `org.example/hello/1.0.0` keeps working. `RepositoryRoutes` is removed; nothing else used it.
- `ARTIFACT_DELETE` having no handler, the other half of #1102, was already fixed by #1821.
