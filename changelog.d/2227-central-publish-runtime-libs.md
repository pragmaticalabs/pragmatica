### Fixed (2026-10-18 — #2227: Maven Central rc4 omitted http-server and postgres-async)
- **`http-server` and `postgres-async` were left out of the rc4 Maven Central publish set**, although rc1-rc3 published them,
  so an application depending on them could not resolve `1.0.0-rc4`.
- Both are now seeds of the publish set (libraries that applications built on Pragmatica consume directly), with their closure
  `tcp`, `statemachine` and the `integrations/db` parent pom: 38 modules publish. `docs/release/maven-central-publish-set.md`
  records the rule and the rows. `[verified: tools/check-publish-closure.py, 0 violations; --check-table, 0 differences]`
