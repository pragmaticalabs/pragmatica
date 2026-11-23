### Fixed (2026-11-23 — #3610: FlakeScan missed a flake pattern the formatter wrapped across lines)
- **The flake-pattern gate matched one line at a time**, so `assertThat(elapsed)` followed by `.isLessThan(` on the next
  line (the shape `jbct:format` produces) was invisible, and the shrink-only allow-list undercounted wall-clock bounds,
  literal ports, short awaits and sleeps. A match that spans lines is now found over the statement (text between `;`,
  `{` and `}` outside string literals, comments dropped) and reported at the statement's first line; the patterns also
  tolerate whitespace around the dot and before the call parenthesis. A match on one line is keyed exactly as before,
  so every existing allow-list entry stays valid. Two wrapped hits already in the tree are listed (the allow-list grows
  from 1305 to 1307, the one time the gate itself changed what it examines).
- Pinned by `FlakeScanTest`: a wrapped plant per pattern kind that can wrap, statement-boundary and literal-handling
  cases, and the passing wrapped forms. `[verified: testing/src/test/java/org/pragmatica/testing/FlakeScanTest.java]`
