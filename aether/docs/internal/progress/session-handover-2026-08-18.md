# Session handover — 2026-08-18: peglib 0.7.2 in jbct, two lint PRs, pg-tools parked upstream

**Nothing in this session touched `release-1.0.0-rc3` except this document.** All work is on two open
PRs and one unpushed WIP branch. The release branch builds exactly as it did.

| Item | State |
|---|---|
| **PR #600** `feat/peglib-0.7.1` — peglib 0.7.2 + lint interface/record restoration | ✅ green, MERGEABLE · **one open decision, §1** |
| **PR #602** `fix/lint-annotation-trivia-and-bnd01` — bug-report fixes | ✅ green, MERGEABLE |
| `feat/pg-tools-peglib-0.7.2` — pg-tools → 0.7.2 | ⛔ **blocked upstream, waiting on peglib** · unpushed, no PR, **do not merge** |
| `release-1.0.0-rc3` | untouched; pg-tools on peglib 0.6.0, jbct on 0.6.2 |

---

## §1 PR #600 — peglib 0.7.2, and the one decision it needs

Started as a 0.6.2 → 0.7.1 bump; now pinned at **0.7.2**. All 11 jbct modules green.

The substance is not the version. 0.7.1 changed how the grammar spells type members — interfaces and
records lost the `Member` wrapper level that classes keep — and **the linter silently stopped reporting
1962 of 13274 findings** on the aether corpus. `CstNodes.findAllMethods` keyed on `MEMBER`, so it
returned empty for every interface, which is JBCT's primary subject matter. 48 call sites inherited it.
Fixed by reconciling the shapes in `CstNodes` and migrating ~20 rule sites. Details in
`session-handover-2026-08-15-peglib-071.md` on that branch.

0.7.2 re-pin verified rather than trusted: parser and visitor regenerate byte-identical, the lexer diff
is a **pure permutation** of 54 keyword-map entries, `regen_rulekind.py` delta zero, regeneration
deterministic, goldens green, corpus differential **identical** (14 236 findings, 0 lost, 0 new).

> **DECISION NEEDED — the PR pins a version that does not exist outside this machine.**
> `~/.m2` has 0.7.0 and 0.7.1 as published artifacts (`.asc` signatures, sources, javadoc), while
> **0.7.2 is a local `mvn install` only** (jar + pom, `.lastUpdated`, empty repo id). A reviewer, CI, or
> a release would fail to resolve it. Two ways out:
>
> 1. **Publish peglib 0.7.2.** It carries three real generator fixes plus `%import`; peglib has it on
>    `release-0.7.2`, untagged, and offered to ship it.
> 2. **Re-pin #600 to 0.7.1** and take 0.7.2 later — jbct is indifferent, since the 0.7.1 → 0.7.2
>    corpus differential is byte-identical.
>
> Also still open on #600: the **Maven plugin is only unit-verified**. Exercising `mvn jbct:check`
> against this branch's rules needs `mvn install`, which the shared `~/.m2` forbids. Raised in the PR,
> not worked around.

## §2 PR #602 — the jbct-maven-plugin bug report, plus one found while fixing it

Both reported defects reproduce and are fixed; the reported *cause* of the first was wrong.

- **A trailing comment after a bare annotation disabled it.** Not line-text matching — a node's SPAN
  reaches past its last real token into trailing trivia, so `text(qualifiedName)` returned
  `"Contract  // reason"`. `@SuppressWarnings` was immune only because its `(...)` ends the name span.
  It ran the other way too, which the report missed: `@Test  // comment` stopped registering as a test,
  so **JBCT-NAM-05 silently skipped those methods** — a false negative no corpus diff can surface.
  Fixed with `CstNodes.tokenText` (non-trivia tokens).
- **JBCT-BND-01 matched boundary types by simple name**, so a domain `Expression.Optional` was reported
  as `java.util.Optional` (18 false positives in a real project). Now matched by ORIGIN, needing no
  cross-file resolution: none of these types are in `java.lang`, so a bare `Optional` can only be the
  JDK one if the file imports it. Corpus **byte-identical**, BND-01's 4 genuine findings intact.
- **Found while linting my own diff: JBCT-STY-03 reported qualified names inside string literals and
  comments.** Worst case was javadoc — `[org.pragmatica.lang.Cause]` cross-references *must* be
  qualified, so the rule penalised correct documentation. 1130 → 957 findings; all 173 removals
  classified individually (25 comments, 148 string literals/text blocks), **zero genuine losses**.

Note for review: four existing `CstBoundaryTypeRuleTest` fixtures referenced `CompletableFuture`/`Mono`
with **no import** — code that does not compile, and the only shape where simple-name matching was
load-bearing. I added the imports rather than reverting the fix, and said so in the PR.

## §3 pg-tools → 0.7.2 — blocked upstream, and that is the standing position

**We are waiting for peglib. Do not poll, do not re-attempt the grammar.** Branch
`feat/pg-tools-peglib-0.7.2` at `a2f0a0834`; full diagnosis in
`aether/docs/internal/progress/pg-tools-peglib-072-blocked.md` on that branch.

Done: poms migrated to the tokens-first artifact shape; `PostgresParser` facade rewritten over
`CstArray`/`TokenArray` with its public `CstNode`/`SourceSpan` API byte-identical for all 22 consumers;
`GrammarTestBase` re-pointed at the generated parser; 0.6.0 CST baseline captured as the acceptance
sensor (34 files, 10 725 lines).

Blocked because `ColId <- !ReservedKeyword (QuotedIdentifier / UnicodeIdentifier / UnquotedIdentifier)`
cannot be expressed under lex-then-parse. peglib's identifier-fallback is the right mechanism but two
gates exclude it (CI literals filtered to `/cs`; skip-prefix bodies may not reference rules), **and the
grammar-side repair is blocked by a third rule** — a rule referencing only lexer rules is demoted to
LEXER. The guard and the alternatives cannot live in the same place. That framing is now an OPEN item
in peglib's `docs/HANDOVER.md` with `postgres.peg` cited by path as the required test case.

Inlining the guard was priced, not assumed: 78 `ReservedKeyword` alternatives × 69 `ColId` references.

## §4 What came out of the peglib exchange

Three generator defects found here, reported with executed reproductions, fixed upstream and verified
on the real grammar: a constant-name collision for case-variant literals (10-line repro), a lexer
constant-pool overflow (**75 641 oversized int literals → 0**, lexer 1.82 MB → 897 KB), and an
inline-literal lookup break. Plus a staleness stamp, from a suggestion made here.

## §5 Instruments and traps worth reusing

- **The corpus differential is the instrument that found everything.** Build the CLI at the pre-change
  commit in a worktree, lint the same tree with both binaries, diff. It caught 1962 silently-dropped
  findings that every test suite was blind to.
- **Build both sides against the SAME tree.** A first run showed 392 lost / 420 new across 10 rules,
  which was not the parser at all — the baseline predated a merge, so the corpus had grown.
- **Diff as a multiset.** JBCT emits basenames, not paths, and 21 of 2141 aether basenames collide; a
  set-based diff collapses ~880 duplicate triples and can hide a change behind a colliding file.
- **A green suite does not prove a rule still fires.** The bump dropped 1962 findings while everything
  outside `jbct-lint` stayed green.
- **"The corpus shows no divergence" is necessary, not sufficient.** It proves no regression against
  THIS tree; it cannot prove correctness for a shape the tree does not contain. An adversarial review
  pass found 5 defects the corpus could not, four of them latent in exactly that gap.
- **After a generator upgrade, check for `skipped (up-to-date)` first.** peglib's mojo decided staleness
  from mtimes alone; "I regenerated and nothing changed" meant "it skipped". This produced a wrong bug
  report. Now mitigated by the version stamp peglib added.

## §6 Resume

1. **Decide §1** — publish peglib 0.7.2, or re-pin #600 to 0.7.1. #600 cannot merge until then.
2. Get #600 and #602 reviewed by the main stream. They are independent; either can merge first.
3. **JBCT-EX-02 burn-down** — #600's repaired rule surfaces 53 previously-invisible `error`-severity
   violations (49 in tests, 4 in production). Anything gating on a clean lint run goes red until done.
4. pg-tools stays parked until peglib lands the identifier-fallback work. Nothing to do here.
