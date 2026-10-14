### Fixed (2026-10-04 — #974: `jbct:format` damaged shift operators and nested block indentation)
- **Shift and relational operators lost the space before them** after a numeric literal, an uppercase constant or an
  indexed expression (`1<< 21`, `MAX< x`, `arr[0]<< 2`): the formatter guessed "generic bracket or operator" from the
  previous token's text, and read a stale word after a literal. It now takes the answer from the parser: outside
  `TypeArgs`/`TypeParams` a `<` or `>` is an operator. A diamond `<>` and a wildcard-only `<?>` are childless nodes and
  raise the type context too, so `new ArrayList<>()` and `new Class<?>[0]` keep their glued form.
- **A block nested in a statement of a chain-aligned lambda body printed at the wrong indent** (`.stream()
  .forEach(x -> { if (c) { throw ...; } })` put the `throw` at the `if`'s own column and the closing `}` below it):
  the nested block reused the enclosing aligned column instead of its owning statement's. It now aligns to that
  statement. The output compiled either way, which is why it went unnoticed.
- Pinned by a new golden example (`ShiftOperatorsAndNestedBlocks.java`). **Not yet applied to the repository's own
  sources:** gated modules still contain the old output, so a `jbct:format` run produces a whitespace-only diff there;
  that mechanical reformat lands as its own commit at the end of the rc4 wave.
