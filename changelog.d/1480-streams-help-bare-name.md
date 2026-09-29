### Fixed (2026-09-29 — #1480: aether streams --help still advertised the removed bare-name default)
- **`aether streams status|publish|read|delete --help` said a bare name defaults to
  `system:name:1.0.0`**, the convention #1044 removed; following it produced the parser's own refusal.
- The help now states that the full `namespace:stream:version` address is required, that a bare name is
  refused, and how to spell a system stream. The same stale sentence and bare-name examples are
  corrected in `aether/docs/reference/cli.md`.
  `[verified: aether/cli/src/test/java/org/pragmatica/aether/cli/StreamsHelpTextTest.java]`
