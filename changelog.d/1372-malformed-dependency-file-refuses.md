### Fixed (2026-09-20 — #1372: a present-but-malformed META-INF/dependencies file loaded the slice as dependency-free)
- **A slice whose dependency file existed but could not be parsed or read was loaded WITHOUT its declared
  dependencies** — `DependencyFile.load` answered EMPTY for every failure, so the slice started and failed
  further from the cause (class-not-found at first use, or the wrong versions resolved). It now REFUSES the
  load with `DependencyFile.DependencyFileError.Unreadable`, naming the resource, the slice jar and the
  read or parse error (kept as the cause's origin). Only an ABSENT file still means "dependency-free".
  Every caller (`DependencyResolver` ×2, `RepositoryDependencyLoader`) chains the result with `flatMap`,
  so the refusal reaches the slice load. `[verified: DependencyFileMalformedTest — an unknown section and
  a read that fails after the resource was found both refuse the load naming the jar; an absent file stays
  dependency-free; the parse error is the refusal's origin]`
