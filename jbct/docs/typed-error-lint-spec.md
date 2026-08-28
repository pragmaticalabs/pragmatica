# JBCT Typed-Error Lint Rules

## Design Specification

**Version:** 0.3
**Status:** Draft
**Companion:** `core/docs/typed-error-construction.md` (the idiom these rules enforce)
**Module:** `jbct-lint`
**Last Updated:** 2026-08-25

---

## 1. Motivation

The typed-error construction specification defines one canonical representation per failure kind:
data-carrying failures are records (data components plus a trailing `String message`, built
through a declared `FACTORY`); fixed-text failures are constants of a prescribed-shape enum. Its
rules R1–R5 are mechanical, which makes them lintable — and lint is what keeps divergent styles
from reappearing.

This specification defines a new rule category, **`JBCT-CAUSE`**, and retires one existing rule
by absorbing and refining it.

### 1.1 Refinement: JBCT-SEAL-02

`JBCT-SEAL-02` (cause variant style) had the core stance right: fixed-message failures are enum
constants, data-carrying failures are records. That stance carries forward unchanged. (An earlier
draft of this specification inverted it on a false exhaustiveness claim; the reversal is recorded
in the companion specification — qualified enum constant case labels, JEP 441, discriminate
constants in switches over the sealed interface.) Under this specification:

- `JBCT-SEAL-02` is **retired by absorption**. Its class-flagging and zero-component-record
  clauses fold into `JBCT-CAUSE-01`, which adds the prescribed enum shape, the message-only
  record check, and anonymous-cause detection; the rest of the pack adds what SEAL-02 never
  checked — the arity equation, component order, the wrap mixin, the construction rule, and the test rules.
- Existing `JBCT-SEAL-02` suppression comments become inert and can be removed; a one-release
  deprecation note in the changelog covers the transition.

### 1.2 Engine constraints inherited by every rule

Rules run on the single-file CST without type resolution (same engine as the existing rule set).
Consequently every rule below shares the `JBCT-SEAL-02` false-negative surface: a cause hierarchy
whose `extends Cause` link is cross-file or transitive is not recognized, and its variants are not
checked. This is accepted — the idiom keeps error hierarchies in one file with their sealed
interface, so conforming code is fully visible to the linter.

The pack does not run over `org.pragmatica.lang*`: the library that DEFINES the sanctioned
ad-hoc tier (`Causes.cause` and friends) must not be convicted by its own pack — the census caught
exactly that before this gate existed.

Two engine lessons the census enforced, now implementation requirements: **modifier checks run over
comment-masked text** (a doc comment attaches to the member node, so a raw `contains("default ")`
matched prose and flagged the abstract `message()` on `Cause` itself), and **member scans scope to
DIRECT members** (`findAllMethods` walks the whole subtree, so a nested interface's default
`message()` was attributed to the enclosing type too and emitted twice).

Suppression uses the existing mechanism (`SuppressionExtractor`), per rule, per site.

---

## 2. Rule Catalog

| ID | Rule | Severity | Sources | Enforces |
|---|---|---|---|---|
| JBCT-CAUSE-01 | Cause representation shape | ERROR | main | R3 |
| JBCT-CAUSE-02 | No hand-written `message()` bodies | ERROR | main | R4 |
| JBCT-CAUSE-03 | `message` component last | WARNING | main | R2 |
| JBCT-CAUSE-04 | Factory arity equation | ERROR | main | R1 |
| JBCT-CAUSE-05 | Wrapped causes use the mixin | WARNING | main | idiom |
| JBCT-CAUSE-06 | No `message()` assertions | WARNING | test | R4 corollary |
| JBCT-CAUSE-07 | No anonymous templates in domain code | WARNING | main | R1 corollary |
| JBCT-CAUSE-08 | No direct construction of cause records | WARNING | main | R1/R4 at runtime |

---

## 3. Rules

### 3.1 JBCT-CAUSE-01 — Cause representation shape

**Statement.** Every type that `implements` a `Cause`-extending interface declared in the same
file (or `Cause` directly) is either a **record** or an **enum in the prescribed shape**: a single
`String message` field, a constructor, a field-returning `message()` accessor, no other instance
state. Interfaces extending `Cause` are the hierarchy and the mixins (`Cause.Terminal`,
`Cause.Wrapped`) — never flagged. Mixin recognition is **name-based** — the single-file engine
cannot resolve cross-file types, so the qualified spellings `Cause.Terminal` / `Cause.Wrapped` in
an `implements` clause are treated as the mixins; the qualified form is self-namespacing, so
collision with a domain interface name is not a practical concern.

**Flags:**
- a **class** implementing a cause interface;
- an **anonymous class** whose supertype is a cause interface, in domain code (`Causes.cause` and
  friends are the sanctioned anonymous form and are library-side);
- a **malformed cause enum** — any instance field beyond `message`, or constructor arity beyond
  one: a constant carrying data is a record wearing enum clothes;
- a **message-only record** (single component `message`) — a fixed-text failure modeled as a
  record; it belongs in the hierarchy's enum (SEAL-02's zero-component clause, carried one
  component up);
- a **zero-component record** — cannot implement `message()` structurally at all.

**Compliant** — the mixed shape, both kinds side by side:

```java
public sealed interface SessionError extends Cause {
    enum General implements SessionError {
        INVALID_TOKEN("Invalid session token");

        private final String message;
        General(String message) { this.message = message; }
        @Override public String message() { return message; }
    }

    record SessionExpired(Instant expiredAt, String message) implements SessionError {
        static final Fn1<SessionExpired, Instant> FACTORY =
            Causes.forOneValue("Session expired at %s", SessionExpired::new);
    }
}
```

**Violation:**

```java
enum PaymentErrors implements PaymentError {
    DECLINED("Payment declined", 402);          // JBCT-CAUSE-01: second field --
    ...                                          // data belongs in a record
}
```

**Diagnostic messages** name the fix, not just the rule: for the malformed enum, "a constant
carrying data belongs in a record: `record Declined(int code, String message)`"; for the
message-only record, "a fixed-text failure belongs in the hierarchy's enum".

**FP surface:** the `record unused()` placeholder-filler exemption from JBCT-SEAL-02 is carried
over. The message-only record check overlaps `JBCT-CAUSE-04` when a value-formatting factory is
present — both fire, for different reasons, and both point at the same fix (make the value a
component). Suppress per site during migration.

### 3.2 JBCT-CAUSE-02 — No hand-written `message()` bodies

**Statement.** A cause record must not declare an explicit `message()` method — the trailing
`message` component's generated accessor is the implementation. A cause enum implements
`message()` as exactly the field-returning accessor (`return message;`); any other body —
concatenation, conditionals, formatting — is hand-written rendering, prohibited by R4.

**Flags:** in records: any method declaration named `message` with no parameters; in enums: a
`message()` body that is not a single field-return statement; anywhere: `default` `message()`
methods on cause interfaces in the same file.

**Violation:**

```java
record AccountLocked(UserId userId, String message) implements LoginError {
    @Override
    public String message() {                        // JBCT-CAUSE-02
        return "Account is locked: " + userId;
    }
}
```

**FP surface:** a deliberate computed message. None is legitimate under the idiom; suppress if a
migration needs a grace period.

### 3.3 JBCT-CAUSE-03 — `message` component last

**Statement.** In a cause record, the `String message` component occupies the final position (R2:
the canonical constructor reference must line up with the `causeFactory` parameter shapes, which
pass the formatted message last).

**Flags:** a cause record with a component named `message` in any non-final position.

**Not flagged:** a cause record with no `message` component — the compiler already rejects it
(abstract `message()` unimplemented) unless an explicit override exists, which CAUSE-02 flags.

**Severity WARNING**, not ERROR: the code compiles and behaves; the defect is that the type cannot
join the factory idiom and convergence suffers.

### 3.4 JBCT-CAUSE-04 — Factory arity equation

**Statement.** For a factory declared via `Causes.forOneValue`/`forTwoValues`/`forThreeValues`
with a string-literal template, the R1 arity equation must hold:

```
format conversions in template  ==  N of forNValues  ==  enclosing record components − 1
```

The third term applies when the `causeFactory` argument is a constructor reference to the
enclosing record — the canonical case.

**Flags:**
- template conversion count (`%s`, `%d`, … excluding `%%` and `%n`) differing from N — a message
  that formats values the factory does not supply, or ignores values it does;
- N differing from components − 1 — including the **value-discarding form**: a message-only record
  (`components − 1 == 0`) built with a value-formatting overload, which bakes data into prose
  instead of retaining it (`record InvalidEmail(String message)` +
  `forOneValue("Invalid email: %s", InvalidEmail::new)` — the raw value must be a component:
  `record InvalidEmail(String raw, String message)`).

**Detection limits (FN surface):** the template must be a string literal at the call site — a
template built by concatenation or referenced from a constant is skipped; a factory declared
outside the record it constructs is checked only for the first equality. Both are off-idiom
shapes that CAUSE-07 or review catches.

### 3.5 JBCT-CAUSE-05 — Wrapped causes use the mixin

**Statement.** A cause record wrapping an underlying cause implements `Cause.Wrapped` with an
`origin` component; it must not hand-declare a `source()` override.

**Flags:** an explicit `Option<Cause> source()` method declaration in a cause record.

**Diagnostic message** teaches the trap: "declare a `Cause origin` component and implement
`Cause.Wrapped`; a component cannot be named `source` because the accessor's return type clashes
with `Cause.source()`".

**Severity WARNING:** the override is functionally equivalent; the defect is divergence and the
naming trap left armed for the next author.

### 3.6 JBCT-CAUSE-06 — No `message()` assertions in tests

**Statement.** Test code asserts on cause types and data components, never on `message()` text.
Message text is a rendering; string-matching it couples tests to prose and is the practice the
mutation-testing guidance warns against.

**Flags:** any invocation of `.message()` in test sources. This deliberately over-approximates
(no type resolution — the receiver may not be a `Cause`); the diagnostic is WARNING and
suppressible for the rare legitimate case (a test of a boundary formatter).

**New capability required:** the lint engine currently scopes by package
(`LintContext.shouldLint`), not by source set. This rule needs a main/test distinction: the Maven
plugin passes the source-set root; the CLI infers it from the conventional `src/test/java` path
segment. This is the only rule in the pack gated on that capability; the other seven ship without it.
The Maven-plugin half of the capability already exists — the `jbct.includeTests` source-root
plumbing (#624/#625) hands the plugin both source roots; only `LintContext` needs to carry the
source-set marker to the rules.

### 3.7 JBCT-CAUSE-07 — No anonymous templates in domain code

**Statement.** The single-argument template overloads (`forOneValue(String)` etc.) produce
anonymous, value-discarding causes; in production sources a parameterized failure is worth naming
and retaining (R1), via the `causeFactory` overloads. The single-argument forms remain sanctioned
in tests and scripts.

**Flags:** calls to `Causes.forOneValue`/`forTwoValues`/`forThreeValues` with a single argument,
in main sources.

**Explicit non-rule:** `Causes.cause(String)` is **not** flagged. The line between a typed failure
and a cheap string cause is "can a caller act differently on it" — a semantic judgement no
CST rule can decide. Flagging `cause(String)` would convict the sanctioned ad-hoc tier; the
boundary stays with review.


### 3.8 JBCT-CAUSE-08 — No direct construction of cause records

**Statement.** A cause record is constructed through its declared `FACTORY` (fixed-text failures
are referenced as enum constants) — never instantiated directly. `FACTORY` is the only path on
which the R1 arity equation holds *at runtime*: `new ExceededLimit(a, b, "hand-typed prose")`
compiles and silently decouples the stored message from the declared template, which is exactly
the drift R1/R4 exist to prevent. House precedent: this is the cause-flavored `JBCT-VO-02`
(constructor bypass); `CstConstructorBypassRule` is the implementation template.

**Flags:** an instantiation expression `new X(...)` in main sources where `X` is a cause record
declared in the current file **that declares a `forXValues` factory**. The gate is the census's
doing: ungated, the rule fired 320 times on the monorepo — nearly all the pre-idiom
smart-constructor pattern (`new InvalidRequest(...)` inside the record's own static factory), which
is not drift FROM the idiom because the idiom is not there yet. A record without a factory is the
pilot migration's business, not this rule's.

**Exempt** — the two sanctioned constructor uses:
- the constructor *reference* (`ExceededLimit::new`) passed as the `causeFactory` argument of a
  `Causes.forXValues` call — trivially distinguishable in the CST (method reference vs
  instantiation expression);
- an instantiation within the declaring record's **own static factory member** — a static field
  initializer or a static method of the record returning the record type. This is the hand-rolled
  factory the companion specification prescribes for errors carrying more than three values
  (its Decision 3: three is the `forXValues` ceiling), and the same shape
  `CstConstructorBypassRule` already exempts for value objects' own factory methods.

**Detection limits (FN surface):** the single-file engine recognizes "cause record" only inside
the declaring file, so same-file bypass is caught reliably while `new ExceededLimit(...)` in
another file is a documented false negative. No language-level enforcement exists — a public
nested record cannot restrict its canonical constructor — so lint is the available mechanism, and
drift that starts inside the hierarchy file is caught where it starts.

**Scope:** main sources only. Tests constructing expected instances directly are out of scope
initially — the equality guidance (build the expected instance through `FACTORY`) already pushes
tests the right way; the census decides whether test-side flagging earns its noise.

**Severity WARNING**; calibrates on the pilot migration (track B in §5) — today's corpus has no
`FACTORY` declarations to bypass.

---

## 4. What Is Deliberately Not Ruled

- **`FACTORY` naming.** The constant's name is convention, not load-bearing; naming rules live in
  the `JBCT-NAM` category if ever wanted. Nothing breaks if a codebase says `TEMPLATE` —
  exhaustiveness and the arity equation carry the guarantees.
- **Bare type patterns over cause enums in switches.** `case General e ->` legally collapses all
  constants back into one arm, un-discriminating them. A candidate `JBCT-CAUSE-09` (WARNING),
  deferred until there is evidence the pattern occurs in practice.
- **`Causes.cause(String)` usage** (see 3.7).
- **`Cause.Terminal` application.** Whether a failure is terminal is a semantic fact about the
  domain; no structural signal exists to check it against.

## 5. Rollout — measured, two tracks

> **FROZEN 2026-08-28 — ruling [#713](https://github.com/pragmaticalabs/pragmatica/issues/713),
> versioned against JBCT 5.0.0:** CAUSE-01/02/04 = ERROR (this section's proposed table adopted),
> CAUSE-03/05/07/08 = WARNING; CAUSE-06 excluded (unimplemented — WARNING-at-introduction when it
> lands). Enforcement landed with explicit migration-bound overrides pinning CAUSE-01/02 to warning
> in the two reference corpora (monorepo root `jbct.toml` → #720; ticketing `jbct.toml` → its held
> typed-error migration); CAUSE-04 carries no override anywhere (zero corpus hits). The prose below
> is the pre-freeze plan, kept as the record of how the gates were defined.

The pack's rules split by what they judge, and the gates differ. **Track A** rules judge existing
code (CAUSE-01, 02, 06, 07): they are corpus-measurable today, and severities freeze only after
the census. **Track B** rules judge code that does not exist yet (CAUSE-03, 04, 05, 08 — each
presupposes the new idiom): a census over today's corpus returns a meaningless zero — the
instrument's silence, not conformance — so their gate is fixtures plus a pilot migration.

1. **Implement the pack with every rule at WARNING** and severities unassigned; the severity
   configuration is the last commit of the pack, not the first. Rules still land together with
   the Pragmatica core changes they reference (CAUSE-04/07/08 mention the `causeFactory`
   overloads; CAUSE-05's suggested fix requires `Cause.Wrapped` to exist).
2. **Census (track A) — RUN, 2026-08-26** (monorepo main sources, post-fix; corpora similar in
   shape). Counts: CAUSE-01 **105**, CAUSE-02 **661**, CAUSE-03 **9**, CAUSE-04 **0**, CAUSE-05
   **20**, CAUSE-07 **151** (the predicted ≈147), CAUSE-08 **0** (after the factory gate). The
   census also corrected the track split: CAUSE-03 and CAUSE-05 judge PRE-IDIOM shapes
   (message-first records, hand-declared `source()` overrides) and are census-measurable after
   all; only CAUSE-04 and the gated CAUSE-08 are genuinely track-B. And it found three
   implementation defects before rollout — the prose-"default" FP, the nested-interface double
   emission, and the pack convicting `Causes.java` itself — which is the census doing exactly
   what this section exists for. CAUSE-06's ~656 `.message()` assertion sites stand from the
   earlier measurement.
3. **Pilot migration (track B):** convert one real mid-size hierarchy containing both fixed-text
   and data-carrying failures, with the pack running. Each track-B rule must fire on a
   deliberately broken intermediate state and be silent on the final state; the companion
   specification's Shape Evolution section is the test script.
4. **Severity freeze criteria, per rule:**
   - **ERROR** requires corpus hits = 0 after burn-down, or every survivor suppressed with a
     recorded reason (the disposition pattern already used for the easy-tier promotions).
   - **WARNING** requires a ~20-hit sample audit with FP below ~10% and a diagnostic that names
     the fix.
   - Neither → the rule ships default-disabled as a census rule (the `JBCT-SHAPE-02` pattern).
5. **Outcomes after the census:** CAUSE-01 (105) and especially CAUSE-02 (661) are migration
   backlogs, not FP noise — the sites are pre-idiom house style, true by the letter of the new
   idiom — so neither is ERROR-promotable until the burn-down; whether 661 warnings on every
   build is acceptable during migration, or CAUSE-02 should sit default-disabled until the
   backlog shrinks, is the explicit severity-freeze decision this section defers. CAUSE-07's 151
   are the same long burn and likely stay WARNING permanently. CAUSE-04/08 calibrate on the
   pilot (their zeros are the idiom's absence, not conformance). CAUSE-06 ships
   **default-disabled** until its ~656 count trends under a promotion threshold — a rule
   greeting users with hundreds of unactionable warnings gets trained away, which is the
   noise-generator failure this pack exists to avoid.
6. `JBCT-SEAL-02` retires in the same release; the changelog carries the absorption note. No
   existing suppression comments reference it (verified: zero in the monorepo), so the
   transition is free.
7. CAUSE-06 follows when source-set awareness lands in `LintContext`; it must not block the pack.
8. The rule set feeds `RuleCategoryMapping` with the new `CAUSE` category for scoring.
