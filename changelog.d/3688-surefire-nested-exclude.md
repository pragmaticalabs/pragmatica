### Fixed (2026-11-25 — #3688: surefire excludes replaced the default `**/*$*`)
- **Four surefire `<excludes>` lists replaced surefire's default `**/*$*`** (root `pom.xml`, `aether/pom.xml`,
  `aether/environment/hetzner`, `integrations/db/postgres-async`), so every `@Nested` class (`Outer$Inner`) was scanned as
  a test class of its own and, with `forkCount > 1`, ran twice (#3664). The root list now carries `**/*$*`;
  `aether/pom.xml` inherits it, `hetzner` and `postgres-async` append their one extra exclude
  (`combine.children="append"`), and `aether/node` drops its own copy (#3665). Failsafe sets `<includes>` only and keeps
  its default.
- **A static nested class with test methods is dropped by that exclude and nothing reported it.**
  `NestedTestScanTest` fails the build on one over every `src/test/java` and `src/it/java` tree (`@Nested` classes and
  abstract bases that a top-level or `@Nested` class extends are legal), and on any pom whose surefire or failsafe
  `<excludes>` list would drop the default again. `PlanWitnessTest.PlannedFixture` became a top-level class.
