# Thinking in Slices

You already know how to write a service. This page is about the one thing that is genuinely different when you write it as an Aether **slice**: the **functional model** — no thrown exceptions, no `null`, no `*Impl` classes, and composition in place of control flow. It is the steepest step in the learning curve and the highest-value one. Read it once and the rest of the slice docs stop feeling foreign.

This is a conceptual on-ramp, not an API reference. Every code excerpt here is real, from the repo's own `examples/` tree, cited by file. When you want the hands-on version, [Getting Started](getting-started.md) builds a slice line by line; when you want the full API, the pattern references are linked at the end.

## One vocabulary, three altitudes

A slice is written in a vocabulary that is the same at every layer of the ecosystem, by design (`docs/rfc/RFC-0000-ecosystem-foundation.md`):

| Layer | What it is | What it gives you |
|-------|-----------|-------------------|
| **Pragmatica Core** (`pragmatica-lite`) | The functional library — `Result`, `Option`, `Promise`, `Cause` | The four return types and the words for success, absence, and failure |
| **JBCT** (Java Backend Coding Technology) | How you compose those types — parse-don't-validate, the structural patterns, no exceptions/nulls/`*Impl` | One good way to structure business logic |
| **Aether** | The runtime that deploys and scales the result | Distribution, routing, failover — transparently |

The continuity is the point: the `Promise<T>` you return from a slice method is the same `Promise<T>` the library defines and the same one the runtime routes across the cluster. You learn the vocabulary once and it carries from a value object up to a distributed deployment. The rest of this page walks the four shifts that vocabulary asks of you.

## Shift 1 — Failures live in the return type

In conventional Java, a method that can fail either throws or returns something you must null-check. In JBCT, **a method that can fail says so in its return type** by returning `Result<T>` (or `Promise<T>` when it is also asynchronous). There is no `throw` in business code.

`examples/banking/shared/.../Money.java:34-40` — a constructor that can fail returns `Result<Money>`:

```java
public static Result<Money> money(BigDecimal amount, Currency currency) {
    return Verify.ensure(amount,
                         a -> a.compareTo(BigDecimal.ZERO) >= 0,
                         NEGATIVE_AMOUNT)
                 .map(a -> a.setScale(2, RoundingMode.HALF_UP))
                 .map(a -> new Money(a, currency));
}
```

**What it buys you:** the failure is visible in the signature, so the compiler makes you handle it — you cannot forget a `try/catch` you never saw. Failures are ordinary values you can `map`, chain, and combine, instead of a control-flow escape hatch that unwinds the stack. This is **parse, don't validate**: because `money(...)` returns `Result<Money>`, *every* `Money` that exists is already valid — there is no "unvalidated Money" to guard against later. Failures are typed, not stringly — see Shift 4.

## Shift 2 — Absence is a type, not `null`

There is **no `null`** in a slice. A value that may be absent has type `Option<T>`, and you operate on it without ever asking "is it null?".

`examples/pricing-engine/.../tax/TaxSlice.java:40-43` — a lookup that may find no rate resolves the absent case as a value:

```java
private static TaxResponse resolveTax(TaxRequest request, Option<Integer> maybeRate) {
    return maybeRate.map(bps -> taxFor(request, bps))
                    .or(zeroTax(request));
}
```

`maybeRate.map(...)` runs only when a rate is present; `.or(...)` supplies the fallback when it is not. No `if (rate == null)`, no `NullPointerException` waiting to happen. Absence is handled where it arises, in the type, rather than defended against everywhere downstream.

## Shift 3 — Behavior is functions, data is records; there is no `*Impl`

A slice is a **Java interface** plus a **static factory method** — never an `OrderServiceImpl`. Data is carried by `record`s; behavior is supplied by the factory. You will not write a class whose name ends in `Impl`, and you will not find mutable service fields.

This is why a slice reads as a contract with its wiring attached: the interface says *what*, the factory says *what it needs* (its parameters are its dependencies — the equivalent of constructor injection), and records carry the *data* in and out. Shift 4 shows the factory in full.

## Shift 4 — Composition replaces control flow

This is the shift that ties the others together. Instead of imperative steps with `try/catch` and null-checks between them, you **compose** the steps into a chain. Each step returns a `Result` or `Promise`; `map` transforms a success, `flatMap` sequences the next fallible step, and a failure anywhere short-circuits the rest automatically — no `if (error) return` after every line.

`examples/ecommerce/place-order/.../PlaceOrder.java:207-216` — an entire use case as one pipeline:

```java
public Promise<OrderConfirmation> execute(PlaceOrderRequest request) {
    return ValidOrder.validOrder(request)
                     .async()
                     .flatMap(this::checkStockAvailability)
                     .flatMap(this::calculateFullPricing)
                     .flatMap(this::reserveStock)
                     .flatMap(this::processPayment)
                     .flatMap(this::createShipment)
                     .map(this::buildConfirmation);
}
```

Read it aloud with "then": validate the order, *then* check stock, *then* price it, *then* reserve, *then* charge, *then* ship. If stock check fails, payment never runs and the failure flows straight to the caller as the `Promise`'s failure — you wrote no error-handling plumbing to make that happen.

Validation composes the same way. `examples/ecommerce/place-order/.../PlaceOrder.java:122-134` builds a validated request by combining independent checks, **accumulating** all failures rather than stopping at the first:

```java
public static Result<ValidOrder> validOrder(PlaceOrderRequest raw) {
    return Result.all(CustomerId.customerId(raw.customerId()),
                      LineItem.lineItems(raw.items()),
                      validateAddress(raw.shippingAddress()),
                      validatePayment(raw.paymentMethod()))
                 .map((customerId, items, address, payment) -> new ValidOrder(...));
}
```

Failures here are **typed values**, not strings or exceptions. `examples/banking/shared/.../Money.java:15-29,60-64` shows the shape — a sealed `Cause` with one variant per failure, produced fluently with `.result()`:

```java
public sealed interface MoneyError extends Cause {
    record NegativeAmount(BigDecimal amount) implements MoneyError { ... }
    record CurrencyMismatch(Currency expected, Currency actual) implements MoneyError { ... }
}
// ...
private Result<Money> verifySameCurrency(Money other) {
    return currency.equals(other.currency)
           ? Result.success(this)
           : new MoneyError.CurrencyMismatch(currency, other.currency).result();
}
```

Because failures are a sealed set, a caller can `switch` over them exhaustively — the compiler knows every way the operation can fail.

## The four return kinds

Every slice method returns exactly one of four kinds. Choosing the return type *is* the design decision — it declares the method's contract.

| Return type | Meaning | Use when |
|-------------|---------|----------|
| `T` | Cannot fail, always present | Pure computation (formatting, arithmetic) |
| `Option<T>` | May be absent, cannot fail | Lookups that can legitimately find nothing |
| `Result<T>` | May fail, synchronous | Validation and business rules |
| `Promise<T>` | May fail, asynchronous | Anything touching I/O — the default for slice methods |

One rule worth memorizing early: **never `Promise<Result<T>>`** — a `Promise` already carries failure. The full treatment of these types and the structural patterns (Leaf, Sequencer, Fork-Join, Condition, Iteration, Aspects) lives in the JBCT methodology and the [Slice Patterns](slice-patterns.md) guide; this page only needs you to recognize them.

## A slice is this model, deployed

Put the shifts together and a whole slice is small. `examples/pricing-engine/.../tax/TaxSlice.java:11-38` — an interface, its request/response records, one `Promise` method, and a factory that declares its one dependency (a SQL connector):

```java
@Slice
public interface TaxSlice {
    record TaxRequest(String regionCode, int amountCents) { ... }
    record TaxResponse(int taxAmountCents, String regionCode, int taxRateBps) { ... }

    Promise<TaxResponse> calculateTax(TaxRequest request);

    static TaxSlice taxSlice(@Sql SqlConnector db) {
        record taxSlice(SqlConnector db) implements TaxSlice {
            @Override
            public Promise<TaxResponse> calculateTax(TaxRequest request) {
                return db.queryOptional(SELECT_TAX_RATE, RATE_BPS_MAPPER, request.regionCode())
                         .map(maybeRate -> resolveTax(request, maybeRate));
            }
            // resolveTax / taxFor / zeroTax as above
        }
        return new taxSlice(db);
    }
}
```

Everything on this page is in those lines: failure and asynchrony in the return type (`Promise<TaxResponse>`), absence as a type (`Option<Integer>` from `queryOptional`, resolved by `resolveTax`), data as records, no `*Impl`, and composition (`.map(...)`) instead of control flow. Aether takes this interface, generates the transport and routing around it, and deploys it — the model you wrote locally is the model that runs distributed.

## What to unlearn

Coming from conventional Java, these are the habits to drop:

| Conventional Java | In a slice |
|-------------------|------------|
| `throw new SomeException(...)` | Return `Result<T>` / `Promise<T>`; produce failures with `cause.result()` / `cause.promise()` |
| `try/catch` around business steps | `flatMap` the steps; failure short-circuits the chain |
| `if (x == null) ...`, `Optional.get()` | `Option<T>` with `map` / `or` / `onPresent` |
| Validate first, use later (and hope) | Parse at construction — a value that exists is valid |
| `OrderServiceImpl implements OrderService` | An interface with a static factory returning the behavior |
| `String` or exception error messages | Typed, sealed `Cause` variants you can `switch` over |
| Imperative `if/else` mutating a result | Branch as a value (a ternary returning the next step) |

Exceptions still exist at the very edges — when you call a JDBC driver or an HTTP client, that boundary code *lifts* the exception into a `Result`/`Promise` (e.g. `Promise.lift(...)`). Inside your business logic, though, there are none.

## Where to go next

- [Getting Started](getting-started.md) — build and deploy your first slice, line by line (includes the parse-don't-validate walkthrough)
- [Slice Patterns](slice-patterns.md) — service vs lean slices, error modeling, dependency composition
- [Resource Reference](resource-reference.md) — `@Sql`, `@Http`, streams, pub/sub, and the other resources a slice factory can declare
- [Testing Slices](testing-slices.md) — testing `Result`/`Promise` code
- [`docs/rfc/RFC-0000-ecosystem-foundation.md`](../../../docs/rfc/RFC-0000-ecosystem-foundation.md) — the ecosystem structure behind "one vocabulary, three altitudes"
- The **JBCT methodology** (Pragmatica Core `Result`/`Option`/`Promise`, parse-don't-validate, the six structural patterns) is the design leg of the same vocabulary — the `jbct` skill and the **JBCT book** ([pragmatica.dev](https://pragmatica.dev)) are its full reference.
