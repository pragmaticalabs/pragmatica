<!--
Synced into jbct-derive at implementation start for issue pragmaticalabs/pragmatica#443 (Phase A).
Source: coding-technology/book-arch-meta/NEXT-STEP-SPEC.md (READ-ONLY canonical spec).
Sync date: 2026-07-19.
This is a verbatim copy; the book wins on any disagreement (see spec §Working note).
-->

# `next_step` — Engine Specification (v0.1 draft, 2026-07-12)

*Implementation ticket: pragmaticalabs/pragmatica#443 (rc3).*

*Working spec for the derivation engine proposed by the series review (§5) and split
by user ruling 2026-07-12: schema + this spec now; engine implementation post-ship,
ranked against the Aether book. Ground truth: Appendix A (worksheet), Appendix B
(reference cards), chapters 2–5. Where this spec and the book disagree, the book
wins and this spec has a bug.*

---

## 1. Purpose and honest scope

The book claims the derivation is mechanical and the judgment points are named.
`next_step` is that claim as an executable — **the entry gate and the bookkeeping,
not the oracle**:

- It **validates** an answer sheet the way the book's entry gate does: unpriced,
  unscoped, undecomposed, untriaged answers are rejected with the book's own
  vocabulary as error messages.
- It **computes** what the book computes: the pressure matrix, forced axis moves
  with their citing answers, halts, decision records with revisit triggers, and the
  verification arithmetic.
- It **stops** where the book stops. Named judgment points (recovery ties,
  contradiction choices, target setting, product picks — Card 5's refusals) are
  *emitted as output*, never resolved. An engine that auto-picks would contradict
  ch. 12 and overclaim exactly the way the book warns against.

**Non-goals (permanent, not v1 deferrals):** auto-resolving judgment ties; ledger
formalization beyond the published entries (false precision is a named genre
failure); recommending products; any output not derivable from sheet + rules.

## 2. Why it exists (strategic case, from the review)

1. Proves the mechanizability thesis in the strongest form — the thesis ships as
   software.
2. Delivers PFD Prediction #1 (linter-style tooling) by the author's own hand.
3. Concretizes the falsification invitation: `answer-sheet + observed-vector`
   issues on `siy/derivation-artifacts` become the counterexample corpus.
4. The schema becomes the series interchange format (PFD Phase-4 rows, JBCT
   use-case contracts, Arch sheets on one substrate).
5. The four published runs become the engine's golden tests.

## 3. The schema (deliverable now)

One TOML document per sheet (JSON isomorphic; TOML is the human-authored form).
`schema_version` from day one; engine pins the major.

```toml
schema_version = "0.1"

[meta]
system      = "companies-house"        # slug
era         = "2017-2025"              # era-pinning is mandatory
author      = "..."
date        = 2026-07-12               # registration date
mode        = "greenfield"             # greenfield | living

# ---- The nine questions. One [[answers.qN]] block per scoped row. ----
# Common row fields:
#   scope     = "operation:submit-filing" | "data-class:filings" | "path:search" | "system"
#   statement = the answer in prose (numbers inline)
#   status    = "answered" | "UNKNOWN"          # UNKNOWN is valid input, never guessed
#   price     = what the number costs / the 53rd-minute consequence (Q1/Q2 rows)
#   source    = citation or "elicited:<who>"    # provenance survives into output

[[answers.q1]]   # Time budget — per operation
scope      = "path:company-search"
statement  = "P95 <= 200 ms at the register's read peak"
shape      = "system-clock"            # system-clock | requester-clock (triage, Card 3)
price      = "read path engineering; see Q8 envelope"
status     = "answered"

[[answers.q2]]   # Failure budget — per operation: error budget + criticality
[[answers.q3]]   # Loss budget — per data class: rpo, retention, never_lose
[[answers.q4]]   # Consistency contract — strict | bounded(named) | eventual; ryw scope
[[answers.q5]]   # Load — magnitude steady/peak, shape = volume|contention|burst|deadline,
                 #        concentration, window   (shape is the second-copy triage, Card 3)
[[answers.q6]]   # External constraints — kind = audit|replay|residency|mandate;
                 #        mandates carry strikes = ["axis:value", ...]
[[answers.q7]]   # Release structure — cadence divergence between named parts
[[answers.q8]]   # Cost & capacity envelope — money + who operates (headcount)
[[answers.q9]]   # Multi-X — partition gifts + legal pins

# ---- Second row source: domain-shape facts + change-driver facts ----
[[domain_shape]]
operation   = "accept-filing"
inverse     = "none"                   # domain inverse | none | notes
decays      = false
reshapeable = ["append-only"]          # idempotent | commutative | append-only | none

[[change_drivers]]                     # the PFD seam (ch. 2, 0.3.0)
scope      = "policy:filing-rules"
volatility = "per-legislative-cycle"
source     = "process analysis"

# ---- Living systems only ----
[current_vector]
topology    = [{ value = "single deployable", scope = "system" }]
substrate   = [{ value = "direct",            scope = "system" }]
read_write  = [{ value = "unified",           scope = "system" }]
state       = [{ value = "current-state",     scope = "system" }]
persistence = [{ value = "single shared",     scope = "system" }]
recovery    = [{ operation = "accept-filing", class = "design-out" }]

# ---- Verification inputs (v1: user-supplied; the engine does arithmetic, not physics) ----
[[floors]]
path  = "path:company-search"
hops  = [{ name = "lb", p50_ms = 1 }, { name = "db-read", p50_ms = 5 }]
```

Schema notes:
- **Scope is a typed string** (`operation:` / `data-class:` / `path:` / `policy:` /
  `system`) — the scope test and narrowest-scope rule need it machine-comparable.
- **Composite/bundled answers are representable but flagged**: the entry gate
  rejects "we need full history" style rows unless decomposed (`q6.kind` forces
  audit vs replay; team-independence rows force ownership vs release fields).
- Anything the book marks judgment stays **prose in `statement`** — the schema
  never invents enums the method doesn't have.

## 4. Pipeline (mirrors Card 5 exactly)

```
parse → 1 normalize → 2 prune → 3 press → 4 resolve → 5 verify → emit
```

**1. Normalize = the entry gate as validation errors.** Error catalog (messages use
the book's vocabulary; each cites its card):
- `UNPRICED` — Q1/Q2 row without a price: "state the 53rd-minute consequence."
- `UNSCOPED` — row at `system` scope where the question demands per-operation /
  per-data-class / per-path.
- `UNDECOMPOSED` — "audit" without audit-vs-replay; "team independence" without
  ownership-vs-release; bundled answers generally.
- `UNTRIAGED` — time answer without requester-vs-system clock (F22); failure answer
  citing an observed failure as if it were a target (F23).
- `BARE_ILITY` — "scalability", "highly available", bare means: not an answer.
- `MISSING_SHAPE` — Q5 row without volume/contention/burst/deadline.
- `MISSING_DOMAIN_SHAPE` — effectful operation named anywhere without its
  domain-shape row (recovery cannot be derived without it).
- `UNKNOWN` rows pass the gate (valid input) and propagate as UNKNOWN pressure —
  surfaced in output, never guessed (grade-honesty; ch. 7's register).

**2. Prune.** Mechanical: `q6` mandate rows carry explicit `strikes`; struck values
leave the menu with the striking answer recorded. Binary; no weights.

**3. Press.** Per row: containment walk up the rungs (hardware → cache →
coalescing → replicas → projections) using the row's shape to pick the mechanism
family (Card 3); contained ⇒ *inert* (recorded as a result, not discarded);
uncontained ⇒ pressure record `(axis, direction, scope, mechanism)`.
**Combination check is first-class** (F24/F26): pairs of rows from different
questions converging on one axis are evaluated after singles. Output: the pressure
matrix, inert rows included.

**4. Resolve.** Mechanical where the book is mechanical: cheapest containing value ·
fewest new mechanisms · narrowest scope · rungs before moves · scope exclusion
before hardening. Conflict rule: different scopes ⇒ split at the boundary with the
four named split prices attached; same scope ⇒ decompose further; still opposed ⇒
**CONTRADICTION halt** emitting the renegotiation-menu skeleton (each branch priced,
each branch marked "re-enters the derivation"). Recovery per effectful operation
from domain shape, design-out checked first; **a recovery tie is a judgment point,
emitted and not resolved.**

**5. Verify.** The exit gate, arithmetic only: latency decomposition down the
critical path against `floors`; tail composition (slow-fractions in series, fan-out
harvest); envelope composition by correlation; availability multiplication with
earned-independence checks (shared deploys/certs/regions/config named in the sheet);
Rule 5's mechanism bill (count of standing mechanisms × who-operates) against the Q8
envelope. Floors the user didn't supply ⇒ `UNVERIFIED: floor missing`, never a
default.

**Emit.** Two formats from one result object:
- Human: markdown report — vector (every position with its citing answers, F10),
  pressure matrix, decision records (`position · forced by · via · costs ·
  revisit when`), halts, judgment points, inert-rows appendix.
- Machine: JSON — the same, for the artifacts repo and for diffing runs.

**Halts (all five, ch. 8):** fake-answer (fails gate), contradiction, floors-exceed-
budget, envelope-exceeded, instrument-gap ("unexplored territory": a demand no rule
prices — emitted verbatim as such).

## 5. CLI surface

- `next_step check sheet.toml` — entry gate only (the linter; PFD Prediction #1).
- `next_step derive sheet.toml` — full pipeline, greenfield or living (uses
  `current_vector` as the start when `mode = "living"`).
- `next_step audit sheet.toml` — living systems: for every held position, name the
  forcing answer; **silence = debt, by construction** (Card 7). v1.5, after derive.
- `next_step increment --changed <row-id>` — re-derive only implicated axes. v2.
- Exit codes: 0 clean · 1 gate errors · 2 halts/contradictions · 3 judgment points
  pending (scriptable in CI — the sheet as a checked artifact).

## 6. Golden tests

The four published runs' sheets, transcribed into schema form, live in
`siy/derivation-artifacts/schema/`. `derive` must reproduce each derivation's
recorded moves (grade-A CH run first — it is the cleanest transcript). A divergence
is either an engine bug or a book bug; both are findings.

## 7. Placement (recommendation, from the 2026-07-12 jbct survey)

**Build it in the jbct repo as a new module; expose as `jbct derive` from day one;
add a standalone `next_step` wrapper at book ship.**

Survey facts (jbct @ `../pragmatica/jbct`): picocli CLI + mirror Maven plugin, 14
subcommands registered in a static array in `JbctCommand`; Java 25, Maven
multi-module. TOML parsing is already first-class (`org.pragmatica-lite:toml` via
`TomlParser`/`ConfigLoader`); `jbct-core` carries a generic `Diagnostic` record
(file/line/col/severity/message — not Java-coupled) and shared IO; distribution is
`install.sh` + `jbct upgrade` self-update off GitHub releases. The Java-specific
center of mass (PEG parser → CST, 36 CST lint rules) is irrelevant to this engine
and stays untouched.

Why this placement:
- **Near-zero new infrastructure.** The engine is a TOML-consuming,
  diagnostic-emitting CLI — exactly what jbct's non-Java-specific layer already is.
  Entry-gate errors map 1:1 onto `Diagnostic` (TOML sheets have line numbers).
  A second distribution channel for a standalone tool is a standing mechanism the
  Rule-5 arithmetic says we shouldn't buy while one already exists.
- **Same product thesis.** `jbct check` lints code against the methodology;
  `jbct derive` lints-and-derives the sheet. PFD Prediction #1 lands as one tool
  family, and JBDT (JBCT's design phase) already establishes that design-phase
  tooling belongs here.
- **The naming friction is real but deferrable.** The book is stack-independent;
  "install jbct" is the wrong first touch for a non-Java architect. Resolution: a
  thin `next_step` launcher (same tarball, same jar, different entry name — the
  binary named after the book's own procedure) added at ship, when that audience
  materializes. The open umbrella-brand decision can rename the wrapper later for
  the price of a symlink.

Implementation notes: new module `jbct-derive` depending on `jbct-core` only (no
parser, no aether-runtime needs); `@Command` class in the `cli` package + one entry
in `JbctCommand`'s `subcommands` array; machine output JSON — check pragmatica-lite
for an existing codec before adding any dependency (none is present in jbct today).

## 8. Risks and mitigations

- **False-precision drift** — contributors will ask the engine to resolve more.
  Mitigation: §1 non-goals are permanent; refusals are tested behavior (golden
  tests assert judgment points are *emitted*, not resolved).
- **Support burden post-announce** — entry-gate errors reframe "the tool got it
  wrong" into "the sheet is incomplete," which is the correct conversation; issue
  templates route the rest to counterexample intake.
- **Schema ossification** — `schema_version` + the four golden sheets as migration
  tests.
