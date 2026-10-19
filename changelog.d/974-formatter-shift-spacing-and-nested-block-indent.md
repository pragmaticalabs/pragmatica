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
- **A `switch` block, an anonymous class or a local class declared in a chain-aligned lambda body, or a `switch`
  expression in a broken lambda argument, printed its `case` labels and members at the enclosing method's absolute
  indent** with the closing `}` below the opener. Such a body is now indented by level from the column its aligned
  line starts at, and its own blocks no longer inherit the lambda's alignment.
- Pinned by a new golden example (`ShiftOperatorsAndNestedBlocks.java`): operators after a diamond and a `<?>` array,
  and `try`, try-with-resources, `while`, `synchronized`, `do`, `switch`, anonymous and local class bodies in an
  aligned lambda.
- The repository's own gated sources are reformatted under the new rules in the same change, whitespace only
  (`git diff -w` shows only line re-wraps caused by shifted columns).
