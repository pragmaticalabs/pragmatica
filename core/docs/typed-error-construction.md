# Typed Error Construction

**Status:** Proposed
**Scope:** `org.pragmatica.lang` — `Cause`, `Causes`, `Result`, `Promise`, `Verify`
**API:** constructor-reference overloads for `Causes.forOneValue`/`forTwoValues`/`forThreeValues`;
full-PECS cause-factory parameters (`? extends Cause` producers, `? super` inputs) across
`Result`, `Promise`, `Verify`; two mixin interfaces nested in `Cause` (`Cause.Terminal`,
`Cause.Wrapped`).

---

## Overview

`Cause` has exactly one abstract member: `message()`. Everything else — `result()`, `promise()`,
`source()`, `isTerminal()`, `iterate()`, `stream()` — is a default. The consequence is that every
domain failure type is forced to produce a message, and the library currently offers no guidance on
*how*. In practice three incompatible styles appear: hand-written `message()` overrides (two or more
prose styles per codebase), per-error static factory methods wrapping `Causes.cause(...)`, and
enums carrying a message field. Left unguided the styles coexist in one codebase, the two
record-based styles duplicate the error's data as prose, and all three invite string-matching in
tests. This specification standardizes one representation per failure kind — the enum style
survives, in a prescribed shape, as the canonical form for fixed-text failures.

This specification defines one canonical construction idiom for typed errors and the small API
changes that make it frictionless. The design goals:

1. **Structural message.** No hand-written `message()` override, no reliance on `toString()`.
2. **Data retained.** Every value an error mentions is available to callers as a typed component —
   errors are actionable, renderable at the boundary, and countable in telemetry as structured data.
3. **Full typing end to end.** Factories keep the concrete cause type; composition sites accept
   them without widening or adaptation.
4. **Minimal ceremony.** One line per concept: a data-carrying failure is one record line plus
   one factory line; a fixed-text failure is one enum constant line.

---

## The Canonical Form

A typed error hierarchy is a sealed interface extending `Cause`, containing two kinds of members.
A **data-carrying failure** is a record: its components are the error's data, in declaration
order, followed by a trailing `String message` component whose generated accessor *is* the
implementation of `Cause.message()` — satisfied structurally, no override. **Fixed-text failures**
share one enum in the prescribed shape — a single `String message` field, a constructor, a
field-returning accessor — with each failure declared as one constant carrying its text.

```java
public sealed interface TransferError extends Cause {

    record ExceededLimit(Amount requested, Amount limit, String message) implements TransferError {
        static final Fn2<ExceededLimit, Amount, Amount> FACTORY =
            Causes.forTwoValues("Requested %s exceeds limit %s", ExceededLimit::new);
    }

    enum General implements TransferError {
        INVALID_CREDENTIALS("Invalid email or password"),
        TRANSFERS_SUSPENDED("Transfers are temporarily suspended");

        private final String message;

        General(String message) { this.message = message; }

        @Override
        public String message() { return message; }
    }
}
```

Construction sites:

```java
ExceededLimit.FACTORY.apply(requested, limit).result();      // data-carrying
TransferError.General.INVALID_CREDENTIALS.result();          // fixed text
```

### Rules

**R1 — The data standard.** Every value the message template formats is a component of the record.
Equivalently: the factory's parameters are exactly the non-message components. This yields a
checkable arity equation:

```
template placeholders  ==  factory value-arity  ==  record components − 1
```

An error whose template mentions a value the record does not carry, or vice versa, violates R1.

**R2 — Message component last.** The trailing position lets the canonical constructor reference
serve directly as the `causeFactory` argument (see API changes below): the generated factory
receives `(values..., formattedMessage)` in constructor order. R2 governs records only; enums
have no factory and carry their text in the constant declaration.

**R3 — One discriminable case per failure.** Every failure is its own record type or its own enum
constant. Qualified enum constant case labels (Java 21, JEP 441) discriminate constants in a
switch over the sealed interface — `case General.INVALID_CREDENTIALS ->` — and listing every
constant preserves exhaustiveness, so adding a constant breaks every switch exactly as adding a
record to the hierarchy does. Verified by compilation probe in both directions (2026-08-25): the
all-constants switch with no default arm compiles; removing one constant case is a compile error.
What R3 forbids is a failure without its own case: several failures folded into one record behind
a discriminator field, or a failure that exists only as a distinct message string.

**R4 — No hand-written prose per instance.** The message text exists in exactly one place: the
template in the factory declaration, or the literal in the enum constant declaration — adjacent
to the component list or constant it must agree with. Explicit `message()` bodies are prohibited
on cause records; a cause enum implements `message()` as exactly the field-returning accessor.

**R5 — Zero data is a property, not an omission.** `General.INVALID_CREDENTIALS` carries nothing
*because the design says so* (it must not reveal which credential failed). R1 governs values the
error mentions; it does not require inventing components.

### Rendering, redaction, and tests

- User-facing text is produced at the boundary by an exhaustive switch over the sealed interface,
  composed from the record's data components. The `message` component is for logs and operators,
  never the user channel.
- `String.format` renders value objects through their own `toString()`, so a value object that
  masks itself (`TaxId`) stays masked in every message with no per-error effort. Templates that
  format raw, pre-parse input (the failing string of a parse) embed that input verbatim — where the
  raw input is sensitive, the template must format a sanitized form.
- Tests assert on the cause: constant equality for fixed-text failures, type and data components
  for records — never `message()` text.
- Record `equals` includes the `message` component, so whole-record equality assertions are safe
  only when the expected instance is built through the same `FACTORY`; asserting on type and
  components avoids the coupling entirely.

---

## Shape Evolution

A hierarchy's shape is not chosen — it is the residue of per-failure decisions, and it moves as
failures evolve. Three shapes are the same idiom at different points of its life: **enum-only**
(every failure fixed text), **mixed** (the general case), **records-only** (the last fixed-text
failure moved out and the enum was deleted). The transition worth showing explicitly is a
fixed-text failure acquiring data:

```java
// Before: every failure is fixed text
public sealed interface SessionError extends Cause {
    enum General implements SessionError {
        SESSION_EXPIRED("Session expired"),
        INVALID_TOKEN("Invalid session token");

        private final String message;
        General(String message) { this.message = message; }
        @Override public String message() { return message; }
    }
}

// After: SESSION_EXPIRED acquires the expiry instant
public sealed interface SessionError extends Cause {
    enum General implements SessionError {
        INVALID_TOKEN("Invalid session token");    // SESSION_EXPIRED removed -- every switch
                                                   // that listed it now fails to compile
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

Three properties make the migration safe:

1. **Compiler-guided completeness.** Removing the constant makes every switch that listed it
   non-exhaustive and every constant-equality assertion a missing symbol; each site is rewritten
   `case General.SESSION_EXPIRED ->` to `case SessionExpired e ->`. No site can be missed — the
   machinery that made the representation safe is the machinery that migrates it.
2. **Boundary invisibility.** User-facing text and codes are produced by the boundary switch, not
   by the representation, so the migration changes no external contract.
3. **Name continuity.** The constant's name becomes the record's name in type case
   (`SESSION_EXPIRED` → `SessionExpired`); history and search survive the move.

The whole hierarchy stays in the sealed interface's file: `permits` remains implicit, and the
hierarchy stays fully visible to single-file tooling.

---

## API Changes

### 1. `Causes` — constructor-reference overloads

The existing overloads accept `Fn1<C, String> causeFactory` (message-only records). One more rung
lets the *canonical constructor reference of a data-carrying record* be the factory. The
`causeFactory` receives the values and the formatted message, in constructor order (R2):

```java
static <T, C extends Cause> Fn1<C, T> forOneValue(String template, Fn2<C, T, String> causeFactory) {
    return input -> causeFactory.apply(input, String.format(Locale.ROOT, template, input));
}

static <T1, T2, C extends Cause> Fn2<C, T1, T2> forTwoValues(String template,
                                                             Fn3<C, T1, T2, String> causeFactory) {
    return (in1, in2) -> causeFactory.apply(in1, in2, String.format(Locale.ROOT, template, in1, in2));
}

static <T1, T2, T3, C extends Cause> Fn3<C, T1, T2, T3> forThreeValues(String template,
                                                                        Fn4<C, T1, T2, T3, String> causeFactory) {
    return (in1, in2, in3) -> causeFactory.apply(in1, in2, in3, String.format(Locale.ROOT, template, in1, in2, in3));
}
```

Overload resolution is unambiguous: the message-only and data-carrying `causeFactory` parameters
differ in functional arity, and a constructor reference has the arity of its record's component
list. `Fn4` already exists in `Functions`, so no prerequisite work. Formatting pins `Locale.ROOT` so
numeric conversions render identically across JVMs; the single-argument variants adopt the same
pin in the same pass.

The single-argument template variants (`forOneValue(String)` etc.) remain unchanged, for ad-hoc
causes in scripts and tests where a named type is not warranted.

Three is the ceiling (decided 2026-08-25): the corpus has zero call sites at arity two and three,
so higher rungs have no demand behind them; an error carrying more than three values hand-rolls
its factory — one line, no helper.

Custom value rendering is the second reason to hand-roll, found by the pilot migration (PR #638):
a `%s` template renders a component through `toString()`, so a message that needs any other
projection of the value — `QuicTransportError.IdentityMismatch` renders `NodeId.id()`, not the
record's `NodeId[id=...]` — cannot be expressed as a template at any arity. The hand-rolled
factory line formats the projection explicitly; CAUSE-08's exemption for the record's own static
factory member covers it.

### 2. Cause-factory variance (full PECS)

Generics are invariant: `Fn1<ExceededLimit, T>` is not a `Fn1<Cause, T>`, so a fully-typed factory
field does not fit today's composition sites without widening the field's declared type or
adapting with `::apply`. The fix is the standard variance annotation in the producer position —
`? extends Cause` — applied across the public API wherever a cause factory or supplier is
accepted. From the current source, the sites are:

| Site | Parameter today | After |
|---|---|---|
| `Result.filter(causeMapper, predicate)` | `Fn1<Cause, T>` | `Fn1<? extends Cause, ? super T>` |
| `Result.mapError(mapper)` | `Fn1<Cause, ? super Cause>` | `Fn1<? extends Cause, ? super Cause>` |
| `Promise.filter(causeMapper, predicate)` — both overloads | `Fn1<Cause, T>` | `Fn1<? extends Cause, ? super T>` |
| `Promise.mapError(transformation)` | `Fn1<Cause, ? super Cause>` | `Fn1<? extends Cause, ? super Cause>` |
| `Promise.failAsync(supplier)` | `Supplier<Cause>` | `Supplier<? extends Cause>` |
| `Verify.ensure` / `ensureOption` family — every `causeProvider` | `Fn1<Cause, T>` | `Fn1<? extends Cause, ? super T>` |

The enumeration is from the current source and must be re-verified mechanically at implementation
time (`grep -rn 'Fn1<Cause\|Fn2<Cause\|Fn3<Cause\|Supplier<Cause'` over `org.pragmatica.lang`);
any site added since follows the same rule: producer position of `Cause` gets `? extends`.

**Compatibility.** Binary-compatible: the erasure of both parameterizations is the raw functional
interface, so method descriptors are unchanged. Source-compatible: every argument accepted today —
invariant `Fn1<Cause, T>` values, lambdas, method references — is accepted after the change;
lambda inference is unaffected because the ground target type resolves the wildcard to its bound.
With `Promise` sealed (`permits PromiseImpl`, PR #635), no external implementor can exist, so the
claim carries no implementor caveat.

**Verified by compilation probe** (2026-08-24): a minimal reproduction with a fully-typed record
factory field compiled against both the proposed signature and the full-PECS variant
(`Fn1<? extends Cause, ? super T>`) at all call forms — typed field, plain lambda, method
reference — and the PECS variant additionally accepted reuse of a more-generic factory. This
specification adopts **full PECS** (decided 2026-08-25): producer positions take `? extends Cause`
and value-input positions take `? super`, as the table shows — the probe verified every call form
under both, and pre-GA is the window where the widening is free.

With this change the idiom keeps concrete types end to end:

```java
static final Fn1<InvalidEmail, String> FACTORY =            // fully typed, no widening
    Causes.forOneValue("Invalid email: %s", InvalidEmail::new);

Verify.ensure(raw, Email::isValid, InvalidEmail.FACTORY);   // drops in directly
result.filter(InvalidEmail.FACTORY, Email::isValid);        // likewise
```

### 3. Mixin interfaces

Two defaults-only interfaces remove the remaining per-type overrides. They are **nested in
`Cause`** (decided 2026-08-25): the precedent is `Functions.Fn1` and `Causes.CompositeCause`, the
top level of `org.pragmatica.lang` stays closed, and the qualified `implements Cause.Wrapped`
spelling is self-namespacing — single-file tooling recognizes the mixins by that spelling without
colliding with domain interface names.

```java
public interface Cause {
    // ... existing members ...

    /// A cause reporting a settled condition: no retry of the failed operation can change
    /// the outcome. Implementing this interface is the classification.
    interface Terminal extends Cause {
        @Override
        default boolean isTerminal() {
            return true;
        }
    }

    /// A cause wrapping an underlying cause. The `origin` component of the implementing
    /// record supplies `source()` — the component cannot be named `source`, because the
    /// record accessor's return type would clash with `Cause.source()`.
    interface Wrapped extends Cause {
        Cause origin();

        @Override
        default Option<Cause> source() {
            return Option.option(origin());
        }
    }
}
```

A record opts in via `implements Cause.Terminal`. An enum whose constants are all terminal
implements it the same way; where only some constants are terminal, a constant body overrides per
constant (`INVALID_TAX_ID("…") { public boolean isTerminal() { return true; } }`) — one type, the
fact stated at the constant it belongs to.

`Wrapped.source()` deliberately uses `Option.option`, not `Option.some`: `some(null)` wraps a null
without complaint, and a present-but-null source is strictly worse than an absent one.

Usage, composing with the canonical form:

```java
record PaymentFailed(Cause origin, String message) implements TransferError, Cause.Wrapped {
    static final Fn1<PaymentFailed, Cause> FACTORY =
        Causes.forOneValue("Payment step failed: %s", PaymentFailed::new);
}

// translation at a composition boundary:
paymentStep.execute(order).mapError(PaymentFailed.FACTORY::apply);
```

(After the variance pass, `mapError(PaymentFailed.FACTORY)` also typechecks; the `::apply` form is
shown for code that predates it.)

The `origin` component name documents a real constraint: a record component named `source` of type
`Cause` fails to compile against `Cause` — the generated accessor `Cause source()` clashes with
the interface's `Option<Cause> source()`. `origin()` is the component name that avoids the trap,
and the mixin turns the workaround into the idiom.

---

## What Is Deliberately Unchanged

- **`Cause` itself.** `message()` stays abstract. A default such as
  `message() → toString()` was considered and rejected: it silently produces
  `ClassName@hash` garbage for any non-record implementor, and the record-component approach
  satisfies the method structurally without touching the interface.
- **Enums for fixed-text failures — reinstated.** An earlier draft of this specification banned
  cause enums, claiming a multi-constant enum defeats per-failure exhaustive matching. The claim
  is false on the supported platform: qualified enum constant case labels (JEP 441, Java 21)
  discriminate constants in a switch over the sealed interface, and constant coverage preserves
  exhaustiveness — verified by compilation probe in both directions (2026-08-25). The draft's
  singleton-record `INSTANCE` workaround is withdrawn together with the claim that motivated it.
- **Anonymous causes.** `Causes.cause(String)`, `Causes.terminal(String)`, `fromThrowable`, the
  single-argument template overloads, and `CompositeCause` are untouched. They remain the
  sanctioned form for ad-hoc causes where no caller acts on the distinction.
- **`isTerminal()` default.** Still `false` on `Cause`; `Cause.Terminal` is opt-in per type.

---

## Downstream (out of scope here)

- **JBCT book**: the *Defining Typed Errors* section and the API appendix rewrite onto this idiom;
  boundary-rendering guidance (exhaustive switch composes user text from components).
- **jbct-cli lint pack** over the idiom, all mechanically checkable: no explicit `message()`
  bodies (records: none at all; enums: the field-returning accessor only); every `Cause`
  implementor is a record or a prescribed-shape enum (no classes, no anonymous domain causes, no
  data fields in enums, no message-only records); the R1 arity equation (template placeholders vs
  factory arity vs component count); `message` component last; direct construction of cause records flagged (`FACTORY` is the
  construction path); wrapped causes implement `Cause.Wrapped` rather than shadowing `source()`; no assertions on `message()` text in test
  sources; single-argument `forXValues` flagged in domain code.

## Decisions

1. **Mixins are nested in `Cause`** — `Cause.Terminal`, `Cause.Wrapped` (2026-08-25). Precedent
   (`Functions.Fn1`, `Causes.CompositeCause`), a closed top level for `org.pragmatica.lang`, and
   the qualified `implements` spelling is self-namespacing for single-file tooling.
2. **The variance pass adopts full PECS** (2026-08-25) — proven by the compilation probe; pre-GA
   is the window where the widening is free.
3. **Three is the `forXValues` ceiling** (2026-08-25) — the corpus has zero call sites at arity
   two and three; larger errors hand-roll the factory line. Custom value rendering (a projection
   `%s`/`toString()` cannot express) is the second hand-roll reason — pilot finding, 2026-08-26.

## Open Items

1. Whether boundary switches should be linted against bare type patterns over cause enums —
   `case General e ->` legally collapses all constants back into one arm, un-discriminating
   them; deferred until there is evidence the pattern occurs in practice (candidate
   `JBCT-CAUSE-09` in the companion lint specification).
