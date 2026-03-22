# We Should Write Java Code Differently: Let's Get Practical

A few years ago I wrote about [why we should write Java code differently](https://dev.to/siy/we-should-write-java-code-differently-210b). The core argument: most of what slows us down is not the amount of code — it's the amount of context we lose while writing it. Nullable variables, business exceptions, framework magic — each one eats information that should have been explicit.

That article diagnosed the problem. This one delivers the tools.

Three types — `Option`, `Result`, and `Promise` — cover virtually every return value in a Java backend. They compose with the same `map`/`flatMap` vocabulary you already know from Streams. Once you internalize them, the code you write becomes shorter, safer, and — this is the part that surprised me — significantly easier to read months later.

---

## Option: A Value That Might Not Be There

If you've used Streams, you already understand the core mechanic — transform the value inside, chain transformations, extract at the end. `Option` applies the same thinking to absent values.

```java
findUser(id).flatMap(user -> findAddress(user.addressId())
            .map(address -> new Profile(user, address)));
```

If any step returns `Option.none()`, the whole chain short-circuits. No null checks, no early returns, no branching. The absent-value case propagates automatically.

Where does `Option` come from? At adapter boundaries — wrapping nullable external APIs:

```java
Option<String> name = Option.option(request.getParameter("name"));
```

Inside business logic, `Option` is the container for everything genuinely optional. The type makes the absence visible in the signature, not hidden in a javadoc comment.

A few things that make `Option` practical:

```java
// Pattern matching with sealed types
switch (option) {
    case Option.Some(var value) -> process(value);
    case Option.None() -> handleAbsence();
}

// Convert to Result when absence is an error
option.toResult(NOT_FOUND);

// Combine multiple Options — all must be present
Option.all(firstName, lastName, email)
      .map(Contact::new);
```

The conversion methods matter. `Option` is not an island — it flows into `Result` and `Promise` when the context demands it.

---

## Result: Errors Without Exceptions

This is where things change fundamentally.

Consider typical Java error handling:

```java
public User registerUser(RegistrationRequest request) {
    if (request.email() == null || request.email().isBlank()) {
        throw new ValidationException("Email is required");
    }
    if (userRepository.existsByEmail(request.email())) {
        throw new DuplicateEmailException(request.email());
    }
    try {
        String hash = passwordHasher.hash(request.password());
        return userRepository.save(new User(request.email(), hash));
    } catch (HashingException e) {
        throw new RegistrationFailedException("Password hashing failed", e);
    }
}
```

The method signature says it returns `User`. It lies. It can throw three different exceptions, and the compiler won't tell you about any of them. The caller has no idea what to catch. The next developer reading this code has to trace every path to understand what can go wrong.

With `Result`:

```java
public Result<User> registerUser(RegistrationRequest request) {
    return Email.email(request.email())
                .flatMap(this::ensureUnique)
                .flatMap(email -> hashAndCreateUser(email, request.password()))
                .flatMap(userRepository::save);
}

private Result<Email> ensureUnique(Email email) {
    return userRepository.existsByEmail(email)
           ? EMAIL_ALREADY_EXISTS.result()
           : Result.success(email);
}

private Result<User> hashAndCreateUser(Email email, String password) {
    return passwordHasher.hash(password)
                         .map(hash -> new User(email, hash));
}
```

The return type tells the truth — this operation can fail. Every failure path is visible in the chain. No exceptions thrown, no exceptions caught. The compiler enforces that the caller handles the `Result`.

### How Errors Work

Errors are values, not exceptions. They implement the `Cause` interface:

```java
public sealed interface RegistrationError extends Cause {
    enum General implements RegistrationError {
        EMAIL_ALREADY_EXISTS("Email already registered"),
        TOKEN_GENERATION_FAILED("Token generation failed");

        private final String message;
        
        General(String message) { 
            this.message = message; 
        }
        @Override 
        public String message() { 
            return message; 
        }
    }
}
```

Fixed messages become enum constants. Dynamic messages become records. All are sealed — the compiler knows every possible failure. Pattern matching works:

```java
switch (result) {
    case Result.Success(var user) -> sendWelcome(user);
    case Result.Failure(var cause) -> logAndRespond(cause);
}
```

### Composition

The real power shows in composition. `Result.all()` collects independent validations:

```java
record ValidRegistration(Email email, Password password, PhoneNumber phoneNumber) {
    public static Result<ValidRegistration> validRegistration(Registration raw) {
        return Result.all(Email.email(raw.email()),
                   Password.password(raw.password()), 
                   PhoneNumber.phoneNumber(raw.phone()))
              .map(ValidRegistration::new);
    }    
}
```

All three validations run. All failures are collected — not just the first one. The `map` only executes if all three succeed. One line replaces the usual cascade of if-checks-and-early-returns.

### Interfacing with Legacy Code

The world throws exceptions. `lift()` catches them at the boundary:

```java
Result.lift(DatabaseError::new, () -> legacyDao.findById(id));
```

Exception goes in, `Result` comes out. The boundary is explicit. Business logic stays clean.

---

## Promise: Result, But Async

`Promise` is `Result` where the answer hasn't arrived yet. Same `map`, same `flatMap`, same mental model — just non-blocking:

```java
public Promise<EnrichedUser> loadEnriched(UserId userId) {
    return findUser(id).flatMap(this::loadUserWithOrders);
}

private Promise<EnrichedUser> loadUserWithOrders(User user) {
    return findOrders(user.id()).map(orders -> new EnrichedUser(user, orders));
}
```

If `findUser` fails, the chain short-circuits — just like `Result`. If `loadUserWithOrders` fails, same thing. Errors are `Cause` values, same as in `Result`. The only difference: the chain executes asynchronously.

### Parallel Operations

Independent operations run in parallel with `Promise.all()`:

```java
record Dashboard(Profile profile, List<Order> orders, List<Notification> notifications) {}

public Promise<Dashboard> loadUserDashboard(UserId userId) {
    return Promise.all(fetchProfile(userId), 
                       fetchOrders(userId), 
                       fetchNotifications(userId))
                  .map(Dashboard::new);
}
```

Three async calls, all independent, all in parallel. Result combined when all complete. If any fails, the whole thing fails — with a meaningful `Cause`.

For "first success wins" semantics:

```java
Promise<Value> fetch(Key key) {
    return Promise.any(fetchFromPrimaryCache(key), 
                       fetchFromReplicaCache(key), 
                       fetchFromDatabase(key));
}
```

### Side Effects

`Promise` distinguishes between dependent and independent actions:

```java
findUser(id).flatMap(this::validateAccess)       // dependent — runs in sequence
            .flatMap(this::loadProfile)          // dependent — runs after validation
            .onSuccess(metrics::recordAccess)    // independent — runs async, doesn't block chain
            .onFailure(logger::warn);            // independent — runs async on failure
```

Dependent actions (`map`, `flatMap`) execute in order and can fail the chain. Independent actions (`onSuccess`, `onFailure`) run asynchronously and never affect the chain's outcome. This distinction eliminates an entire class of bugs where logging or metrics accidentally break the business flow.
There are also dependent side effects, for the cases when ordering matters:

```java
findUser(id).flatMap(this::validateAccess)       // dependent — runs in sequence
            .flatMap(this::loadProfile)          // dependent — runs after validation
            .withSuccess(metrics::recordAccess)  // dependent — runs on success, in order like map/flatMap
            .withFailure(logger::warn);          // dependent — runs on failure, in order like map/flatMap
```

### Timeouts and Recovery

```java
fetchFromRemoteService(request).timeout(TimeSpan.ofSeconds(5))
                               .recover(cause -> cachedFallback(request));
```

If the remote call doesn't resolve in 5 seconds, it fails. `recover` converts the failure back to success using a fallback. Clean, composable, no try-catch.

---

## The Three Types Together

The real picture emerges when all three work together. Consider a realistic operation — processing an incoming order:

```java
public Promise<OrderConfirmation> processOrder(RawOrderRequest raw) {
    return ValidOrder.validOrder(raw)              // Result<ValidOrder> — sync validation
                     .async()                      // → Promise<ValidOrder>
                     .flatMap(this::enrichOrder)
                     .flatMap(orderRepository::save)
                     .onSuccess(eventBus::publishOrderCreated);
}

private Promise<EnrichedOrder> enrichOrder(ValidOrder order) {
    return Promise.all(inventoryService.check(order.items()),
                       pricingService.calculate(order.items()),
                       customerService.find(order.customerId()))
                  .map((availability, pricing, customer) ->
                           new EnrichedOrder(order, availability, pricing, customer));
}
```

What happens here:

1. **Validation** — `ValidOrder.validOrder()` returns `Result<ValidOrder>`. Parse, don't validate. If the input is malformed, a `Cause` explains why. No exception.
2. **Sync to async** — `.async()` lifts the `Result` into a `Promise`. From here, everything is non-blocking.
3. **Parallel fetch** — `enrichOrder` calls three independent services simultaneously. All must succeed.
4. **Enrichment** — results combined into `EnrichedOrder`. The `map` only runs if all three calls succeed.
5. **Persistence** — saved to repository. Returns `Promise<OrderConfirmation>`.
6. **Side effect** — event published asynchronously. Does not affect the response.

No try-catch. No null checks. No `if (result == null) return error`. Every failure path handled by the type system. Every step clearly visible.

---

## The Decision Tree

Choosing the right type is mechanical:

```
Can this operation fail?
├── NO: Can the value be absent?
│   ├── NO → return T
│   └── YES → return Option<T>
└── YES: Is it async/IO?
    ├── NO → return Result<T>
    └── YES → return Promise<T>
```

Four return kinds. No judgment calls. The decision tree covers every method in a Java backend.

One allowed combination: `Result<Option<T>>` — when the value is genuinely optional but validation can still fail. Example: an optional referral code that, if provided, must match a specific format.

One forbidden combination: `Promise<Result<T>>` — double error channel. `Promise` already carries failure semantics. Nesting `Result` inside it means two places to check for errors.

---

## What Changes In Practice

After a few weeks of writing code this way, something shifts. You stop thinking about error handling as a separate concern. It's not something you add after the happy path — it's embedded in the types. The compiler catches what used to be runtime surprises.

Code reviews get faster. When every method returns one of four types, the shape of the code becomes predictable. You don't need to trace exception paths through five layers. The return type tells you everything.

Testing simplifies. Each step in a chain is independently testable. Failures are values you can assert on — not exceptions you have to catch. Mock a dependency to return `EMAIL_ALREADY_EXISTS.result()` and verify the chain handles it correctly.

And the types compose. A `Result` from validation flows into a `Promise` for async processing, which fans out into parallel `Promise.all()`, which combines back into a single response. Each piece connects to the next with `flatMap`. The vocabulary is always the same.

---

## Getting Started

If your codebase currently uses exceptions for business errors and null for absent values, you don't need to rewrite everything. Start at the boundaries:

1. **Pick one new feature.** Write it with `Result` returns instead of exceptions.
2. **Wrap legacy calls** with `Result.lift()` and `Option.option()` at the adapter boundary.
3. **Let the types propagate.** Once one method returns `Result`, its callers naturally follow.

The types are available in [Pragmatica Core](https://github.com/pragmaticalabs/pragmatica) — a focused library with no transitive dependencies.

This is the foundation that [JBCT](https://pragmaticalabs.io/jbct.html) (Java Backend Coding Technology) builds on. Six structural patterns, four return kinds, mechanical rules that make code deterministic and AI-friendly. But the types come first. Everything else follows from getting the types right.

---

*Previously: [Introduction to Pragmatic Functional Java](https://dev.to/siy/introduction-to-pragmatic-functional-java-142m) (2019) | [We Should Write Java Code Differently](https://dev.to/siy/we-should-write-java-code-differently-210b) (2021)*
