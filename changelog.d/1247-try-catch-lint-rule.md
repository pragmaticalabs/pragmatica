### Added (2026-09-19 — #1247: JBCT lint has no try/catch rule)
- **No lint rule matched `try` or `catch`, so a green gate said nothing about the construct**, and
  `@SuppressWarnings("JBCT-EX-01")` on a catching method was decorative (JBCT-EX-01 sees only exception
  classes, `throw` and `throws`).
- New rule **JBCT-EX-03** flags every `catch` clause outside a method marked as a JDK boundary with
  `@SuppressWarnings("JBCT-EX-03")`. A JBCT-EX-01 suppression does not exempt a `catch`. `try`/`finally` and
  try-with-resources without a `catch` are not flagged, and test classes are exempt.
  `[verified: jbct/jbct-lint/src/test/java/org/pragmatica/jbct/lint/cst/rules/CstTryCatchRuleTest.java]`
- **Default severity is WARNING.** At introduction the rule hit 330 `catch` clauses across 50 main-source
  modules, 131 of them in 26 `aether/` modules inside the gated scope. Raise it to ERROR once that count
  reaches zero. `aether-stream` is at zero: its four legitimate JDK-boundary sites in `OffHeapRingBuffer`
  carry the mark.
