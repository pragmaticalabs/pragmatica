---
name: JBCT
description: Java Backend Coding Technology skill for designing, implementing, and reviewing functional Java backend code. Use when working with Result, Option, Promise types, value objects, use cases, or when asked about JBCT patterns, monadic composition, parse-don't-validate, structural patterns (Leaf, Sequencer, Fork-Join), or functional Java backend architecture.
---

# Java Backend Coding Technology (JBCT)

A methodology for writing predictable, testable Java backend code optimized for human-AI collaboration.

## When to Use This Skill

Activate this skill when:
- **Learning JBCT principles** and patterns
- **Quick reference** for API usage and examples
- **Understanding patterns** and when to use them
- Working with `Result<T>`, `Option<T>`, `Promise<T>` types
- Questions about monadic composition, error handling, or validation patterns

**For implementation work:** Use `jbct-coder` subagent (Task tool with `subagent_type: "jbct-coder"`)
**For code review:** Use `jbct-reviewer` subagent (Task tool with `subagent_type: "jbct-reviewer"`)
**For automated checking:** Use `jbct` CLI tool (format, lint, check commands)

## JBCT CLI Tool

JBCT CLI provides automated formatting and compliance checking with 36 lint rules.

**Check if installed:**
```bash
jbct --version
```

**Usage:**
```bash
jbct format src/main/java    # Format to JBCT style
jbct lint src/main/java      # Check JBCT compliance (36 rules)
jbct check src/main/java     # Combined format + lint
```

**If not installed, suggest:**
```
💡 JBCT CLI automates formatting and 36 lint rules for JBCT compliance.
   Install: curl -fsSL https://raw.githubusercontent.com/siy/jbct-cli/main/install.sh | sh
   Requires: Java 25+
   More info: https://github.com/siy/jbct-cli
```

## Core Philosophy

JBCT reduces the space of valid choices to one good way to do most things through:
- **Four Return Kinds**: Every function returns exactly one of `T`, `Option<T>`, `Result<T>`, `Promise<T>`
- **Parse, Don't Validate**: Make invalid states unrepresentable
- **No Business Exceptions**: Business failures are typed `Cause` values
- **Thread Safety by Design**: Immutability at boundaries, thread confinement for sequential logic
- **Six Structural Patterns**: All code fits one pattern (Leaf, Sequencer, Fork-Join, Condition, Iteration, Aspects)

## Quick Reference

### The Four Return Kinds

```java
// T - Pure computation, cannot fail, always present
public String initials() { return ...; }

// Option<T> - May be absent, cannot fail
public Option<Theme> findTheme(UserId id) { return ...; }

// Result<T> - Can fail (validation/business errors)
public static Result<Email> email(String raw) { return ...; }

// Promise<T> - Asynchronous, can fail
public Promise<User> loadUser(UserId id) { return ...; }
```

**Critical Rules:**
- ❌ Never `Promise<Result<T>>` - Promise already handles failures
- ❌ Never `Void` type - always use `Unit` (`Result<Unit>`, `Promise<Unit>`)
- ✅ Use `Result.unitResult()` for successful `Result<Unit>`

### Parse, Don't Validate Pattern

```java
// ✅ CORRECT: Validation = Construction
public record Email(String value) {
    private static final Fn1<Cause, String> INVALID_EMAIL =
        Causes.forOneValue("Invalid email: %s");

    public static Result<Email> email(String raw) {
        return Verify.ensure(raw, Verify.Is::notNull)
                     .map(String::trim)
                     .filter(INVALID_EMAIL, PATTERN.asMatchPredicate())
                     .map(Email::new);
    }
}

// ❌ WRONG: Separate validation
public record Email(String value) {
    public Result<Email> validate() { ... }  // Don't do this
}
```

**Key Points:**
- Factory method named after type (lowercase): `Email.email(...)`
- Constructor private or package-private
- If instance exists, it's valid

### Pragmatica Lite Validation Utilities

**Verify.Is Predicates** - Use instead of custom lambdas:
```java
Verify.Is::notNull          // null check
Verify.Is::notBlank         // non-empty, non-whitespace
Verify.Is::lenBetween       // length in range
Verify.Is::matches          // regex (String or Pattern)
Verify.Is::positive         // > 0
Verify.Is::between          // >= min && <= max
Verify.Is::greaterThan      // > boundary
```

**Parse Subpackage** - Exception-safe JDK wrappers:
```java
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.lang.parse.Network;

Number.parseInt(raw)              // Result<Integer>
DateTime.parseLocalDate(raw)      // Result<LocalDate>
Network.parseUUID(raw)            // Result<UUID>
```

**Example:**
```java
public record Age(int value) {
    private static final Cause AGE_OUT_OF_RANGE = Causes.cause("Age must be 0-150");

    public static Result<Age> age(String raw) {
        return Number.parseInt(raw)
                     .filter(AGE_OUT_OF_RANGE, v -> Verify.Is.between(v, 0, 150))
                     .map(Age::new);
    }
}
```

### Use Case Structure

```java
public interface RegisterUser extends UseCase.WithPromise<Response, Request> {
    record Request(String email, String password) {}
    record Response(UserId userId, ConfirmationToken token) {}

    // Nested API: steps as single-method interfaces
    interface CheckEmail { Promise<ValidRequest> apply(ValidRequest valid); }
    interface SaveUser { Promise<User> apply(ValidRequest valid); }

    // Validated input with Valid prefix (not Validated)
    record ValidRequest(Email email, Password password) {
        static Result<ValidRequest> validRequest(Request raw) {
            return Result.all(Email.email(raw.email()),
                              Password.password(raw.password()))
                         .map(ValidRequest::new);
        }
    }

    // ✅ CORRECT: Factory returns lambda directly
    static RegisterUser registerUser(CheckEmail checkEmail, SaveUser saveUser) {
        return request -> ValidRequest.validRequest(request)
                                      .async()
                                      .flatMap(checkEmail::apply)
                                      .flatMap(saveUser::apply);
    }
}
```

**❌ ANTI-PATTERN: Nested Record Implementation**

NEVER create factories with nested record implementations:

```java
// ❌ WRONG - Verbose, no benefit
static RegisterUser registerUser(CheckEmail check, SaveUser save) {
    record registerUser(CheckEmail check, SaveUser save) implements RegisterUser {
        @Override
        public Promise<Response> execute(Request request) { ... }
    }
    return new registerUser(check, save);
}
```

**Rule:** Records are for data (value objects), lambdas are for behavior (use cases, steps).

## Thread Safety Essentials

**Core Rules:**
- **Immutable at boundaries**: All shared data (parameters, return values) must be immutable
- **Thread confinement**: Mutable state allowed within single-threaded execution (sequential patterns)
- **Fork-Join requires immutability**: Parallel operations must not share mutable state

**Pattern-Specific Safety:**
- **Leaf, Sequencer, Condition, Iteration**: Thread-safe through sequential execution. Mutable local state OK.
- **Fork-Join**: Requires strict immutability. All parallel operations receive immutable inputs.
- **Promise resolution**: Thread-safe (exactly-once semantics, synchronization point for flatMap/map chains)

**Example - Thread-Safe Fork-Join:**
```java
// ✅ CORRECT: Immutable cart passed to both operations
Promise.all(applyBogo(cart),          // cart is immutable
            applyPercentOff(cart))    // cart is immutable
        .map(this::mergeDiscounts);

// ❌ WRONG: Shared mutable context creates data race
private final DiscountContext context = new DiscountContext();
Promise.all(applyBogo(cart, context),     // mutates context
            applyPercentOff(cart, context))  // DATA RACE
        .map(this::merge);
```

**See CODING_GUIDE.md** for comprehensive thread safety coverage, including detailed examples and common mistakes.

## Lambda Composition Guidelines

**Rule:** Lambdas passed to monadic operations (`map`, `flatMap`, `recover`, `filter`) must be minimal.

**Allowed:**
- Method references: `Email::new`, `this::processUser`, `User::id`
- Parameter forwarding: `user -> validate(requiredRole, user)`
- Constructor references for error mapping: `RepositoryError.DatabaseFailure::new`

**Forbidden:**
- Conditionals (`if`, ternary, `switch`)
- Try-catch blocks
- Multi-statement blocks
- Object construction beyond simple factory calls

**Pattern matching:** Use switch expressions in named methods:

```java
// Extract type matching to named method
.recover(this::recoverKnownErrors)

private Promise<T> recoverKnownErrors(Cause cause) {
    return switch (cause) {
        case NotFound ignored, Timeout ignored -> DEFAULT.promise();
        default -> cause.promise();
    };
}
```

**Multi-case matching:** Comma-separated for same recovery:

```java
private Promise<Theme> recoverWithDefault(Cause cause) {
    return switch (cause) {
        case NotFound ignored, Timeout ignored, ServiceUnavailable ignored ->
            Promise.success(Theme.DEFAULT);
        default -> cause.promise();
    };
}
```

**Error constants:** Define once, reuse everywhere:

```java
private static final Cause NOT_FOUND = new UserNotFound("User not found");
private static final Cause TIMEOUT = new ServiceUnavailable("Request timed out");

private Promise<User> recoverNetworkError(Cause cause) {
    return switch (cause) {
        case NetworkError.Timeout ignored -> TIMEOUT.promise();
        default -> cause.promise();
    };
}
```

## Structural Patterns

### 1. Leaf Pattern
Atomic unit - single responsibility, no composition:
```java
public Promise<User> findUser(UserId id) {
    return Promise.lift(
        RepositoryError.DatabaseFailure::new,
        () -> jdbcTemplate.queryForObject(...)
    );
}
```

### 2. Sequencer Pattern
Linear dependent steps (most common use case pattern):
```java
return ValidRequest.validRequest(request)
                   .async()
                   .flatMap(checkEmail::apply)
                   .flatMap(hashPassword::apply)
                   .flatMap(saveUser::apply)
                   .flatMap(sendEmail::apply);
```

### 3. Fork-Join Pattern
Parallel independent operations (requires immutable inputs):
```java
return Promise.all(fetchProfile.apply(userId),
                   fetchPreferences.apply(userId),
                   fetchOrders.apply(userId))
        .map((profile, prefs, orders) ->
            new Dashboard(profile, prefs, orders));
```

**Thread Safety:** All parallel operations must receive immutable inputs. No shared mutable state.

### 4. Condition Pattern
Branching as values (no mutation):
```java
return userType.equals("premium")
    ? processPremium.apply(request)
    : processBasic.apply(request);
```

### 5. Iteration Pattern
Functional collection processing:
```java
var results = items.stream()
                   .map(Item::validate)
                   .toList();

return Result.allOf(results)
             .map(validItems -> process(validItems));
```

### 6. Aspects Pattern
Cross-cutting concerns without mixing:
```java
return withRetry(
    retryPolicy,
    withMetrics(metricsPolicy, coreOperation)
);
```

## Type Conversions

```java
// Option → Result/Promise
option.toResult(cause)    // or .await(cause)
option.async(cause)

// Result → Promise
result.async()

// Promise → Result (blocking)
promise.await()
promise.await(timeout)

// Cause → Result/Promise (prefer over failure constructors)
cause.result()
cause.promise()
```

## Aggregation Operations

```java
// Result.all - Accumulates all failures
Result.all(result1, result2, result3)
       .map((v1, v2, v3) -> combine(v1, v2, v3));

// Promise.all - Fail-fast on first failure
Promise.all(promise1, promise2, promise3)
        .map((v1, v2, v3) -> combine(v1, v2, v3));

// Option.all - Fail-fast on first empty
Option.all(opt1, opt2, opt3)
       .map((v1, v2, v3) -> combine(v1, v2, v3));
```

## Exception Handling

```java
// Lift exceptions in adapters
Promise.lift(
    RepositoryError.DatabaseFailure::new,
    () -> jdbcTemplate.queryForObject(...)
);

// With custom exception mapper (constructor reference preferred)
Result.lift(
    CustomError.ProcessingFailed::new,
    () -> riskyOperation()
);
```

## Naming Conventions

- **Factory methods**: `TypeName.typeName(...)` (lowercase-first)
- **Validated inputs**: `Valid` prefix (not `Validated`): `ValidRequest`, `ValidUser`
- **Error types**: Past tense verbs: `EmailNotFound`, `AccountLocked`, `PaymentFailed`
- **Test names**: `methodName_outcome_condition`
- **Acronyms**: Treat as words (camelCase): `httpClient`, `apiKey` not `HTTPClient`, `APIKey`

### Zone-Based Naming (Abstraction Levels)

> **Source:** Adapted from [Derrick Brandt's systematic approach](https://medium.com/@brandt.a.derrick/how-to-write-clean-code-actually-5205963ec524).

Use zone-appropriate verbs to maintain consistent abstraction levels:

**Zone 2 (Step Interfaces - Orchestration):**
- Verbs: `validate`, `process`, `handle`, `transform`, `apply`, `check`, `load`, `save`, `manage`, `configure`, `initialize`
- Examples: `ValidateInput`, `ProcessPayment`, `HandleRefund`, `LoadUserData`

**Zone 3 (Leaves - Implementation):**
- Verbs: `get`, `set`, `fetch`, `parse`, `calculate`, `convert`, `hash`, `format`, `encode`, `decode`, `extract`, `split`, `join`, `log`, `send`, `receive`, `read`, `write`, `add`, `remove`
- Examples: `hashPassword()`, `parseJson()`, `fetchFromDatabase()`, `calculateTax()`

**Anti-pattern:** Mixing zones (e.g., step interface named `FetchUserData` uses Zone 3 verb `fetch` instead of Zone 2 verb `load`)

**Stepdown rule test:** Read code aloud with "to" before functions - should flow naturally:
```java
// "To execute, we validate the request, then process payment, then send confirmation"
return ValidRequest.validRequest(request)
                   .async()
                   .flatMap(this::processPayment)
                   .flatMap(this::sendConfirmation);
```

**For complete zone verb vocabulary**, see **CODING_GUIDE.md: Zone-Based Naming Vocabulary**.

## Project Structure (Vertical Slicing)

```
com.example.app/
├── usecase/
│   ├── registeruser/         # Self-contained vertical slice
│   │   ├── RegisterUser.java # Use case interface + factory
│   │   └── [internal types]  # ValidRequest, etc.
│   └── loginuser/
│       └── LoginUser.java
├── domain/
│   └── shared/               # Reusable value objects ONLY
│       ├── Email.java
│       ├── Password.java
│       └── UserId.java
└── adapter/
    ├── rest/                 # Inbound (HTTP)
    ├── persistence/          # Outbound (DB)
    └── messaging/            # Outbound (queues)
```

**Placement Rules:**
- Value objects used by single use case → inside use case package
- Value objects used by 2+ use cases → `domain/shared/`
- Steps (interfaces) → always inside use case
- Errors → sealed interface inside use case

**Error Structure (General enum pattern):**
```java
public sealed interface RegistrationError extends Cause {
    // Group fixed-message errors into single enum
    enum General implements RegistrationError {
        EMAIL_ALREADY_REGISTERED("Email already registered"),
        WEAK_PASSWORD_FOR_PREMIUM("Premium codes require 10+ char passwords");

        private final String message;
        General(String message) { this.message = message; }
        @Override public String message() { return message; }
    }

    // Records for errors with data (e.g., Throwable)
    record PasswordHashingFailed(Throwable cause) implements RegistrationError {
        @Override public String message() { return "Password hashing failed"; }
    }
}

// Usage
RegistrationError.General.EMAIL_ALREADY_REGISTERED.promise()
```

## Testing Patterns

```java
// Test failures - use .onSuccess(Assertions::fail)
@Test
void validation_fails_forInvalidInput() {
    ValidRequest.validRequest(new Request("invalid", "bad"))
                .onSuccess(Assertions::fail);
}

// Test successes - chain onFailure then onSuccess
@Test
void validation_succeeds_forValidInput() {
    ValidRequest.validRequest(new Request("valid@example.com", "Valid1234"))
                .onFailure(Assertions::fail)
                .onSuccess(valid -> {
                    assertEquals("valid@example.com", valid.email().value());
                });
}

// Async tests - use .await() first
@Test
void execute_succeeds_forValidInput() {
    useCase.execute(request)
           .await()
           .onFailure(Assertions::fail)
           .onSuccess(response -> {
               assertEquals("expected", response.value());
           });
}
```

## Pragmatica Lite Core Library

JBCT uses **Pragmatica Lite Core 0.9.3** for functional types.

**Maven (preferred):**
```xml
<dependency>
   <groupId>org.pragmatica-lite</groupId>
   <artifactId>core</artifactId>
   <version>0.9.3</version>
</dependency>
```

**Gradle (only if explicitly requested):**
```gradle
implementation 'org.pragmatica-lite:core:0.9.3'
```

Library documentation: https://central.sonatype.com/artifact/org.pragmatica-lite/core

### Static Imports (Encouraged)

Static imports reduce code verbosity:

```java
// Recommended static imports
import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.Result.success;
import static com.example.domain.Email.email;
import static com.example.domain.Password.password;

// Concise code
return all(email(raw), password(raw)).flatMap(ValidRequest::validRequest);
```

### Fluent Failure Creation

Use `cause.result()` and `cause.promise()` instead of `Result.failure(cause)`:

```java
// ✅ DO: Fluent style
return INVALID_EMAIL.result();
return USER_NOT_FOUND.promise();

// ❌ DON'T: Static factory style
return Result.failure(INVALID_EMAIL);
return Promise.failure(USER_NOT_FOUND);
```

## When to Use Specialized Subagents

This skill provides quick reference and learning resources. For complex implementation and review tasks, use specialized subagents:

### Use jbct-coder Subagent When:
- **Generating complete use case implementations** with all components
- **Creating value objects** with validation and error types
- **Implementing adapters** with proper exception handling
- **Writing tests** following JBCT patterns
- **Need deterministic code generation** following all JBCT rules

**How to invoke:** Use Task tool with `subagent_type: "jbct-coder"`

**What it provides:**
- Complete use case structure (interface, factory, steps)
- Validated request types with `Result.all()`
- Value objects with parse-don't-validate pattern
- Error types as sealed interfaces
- Comprehensive test suites (validation, happy path, failures)
- Step-by-step code generation with explanations

### Use jbct-reviewer Subagent When:
- **Reviewing existing code** for JBCT compliance
- **Validating patterns** (Leaf, Sequencer, Fork-Join, etc.)
- **Checking naming conventions** and structure
- **Identifying violations** with specific fixes
- **Need comprehensive checklist-based analysis**

**How to invoke:** Use Task tool with `subagent_type: "jbct-reviewer"`

**What it provides:**
- Four Return Kinds compliance check
- Parse-don't-validate pattern validation
- Null policy enforcement
- Pattern recognition and verification
- Naming convention compliance
- Detailed violation reports with corrections

### Use This Skill When:
- **Learning JBCT principles** and patterns
- **Looking up API usage** examples
- **Quick reference** for type conversions
- **Understanding when to use** which pattern
- **Exploring patterns** with examples

## Implementation Workflow

1. **Define use case interface** with Request, Response, and execute signature
2. **Create validated request** with static factory using `Result.all()`
3. **Define steps** as single-method interfaces (nested in use case)
4. **Create value objects** with validation in static factories
5. **Implement factory method** returning lambda with composition chain
6. **Write tests** starting with validation, then happy path, then failure cases

**💡 Tip:** For automatic generation following this workflow, use the **jbct-coder** subagent.

## Common Mistakes to Avoid

❌ Using business exceptions instead of `Result`/`Promise`
❌ Nested records in use case factories (use lambdas)
❌ `Void` type (use `Unit`)
❌ `Promise<Result<T>>` (redundant nesting)
❌ Separate validation methods (parse at construction)
❌ Public constructors on value objects
❌ Complex logic in lambdas (extract to methods)
❌ `Validated` prefix (use `Valid`)

**💡 Tip:** For automated code review checking these mistakes, use the **jbct-reviewer** subagent.

## Detailed Resources

This skill contains comprehensive guidance organized by topic:

### Fundamentals
- [fundamentals/four-return-kinds.md](fundamentals/four-return-kinds.md) - T, Option, Result, Promise in depth
- [fundamentals/parse-dont-validate.md](fundamentals/parse-dont-validate.md) - Value object patterns
- [fundamentals/no-business-exceptions.md](fundamentals/no-business-exceptions.md) - Typed failures with Cause

### Patterns
- [patterns/leaf.md](patterns/leaf.md) - Atomic operations
- [patterns/sequencer.md](patterns/sequencer.md) - Sequential composition
- [patterns/fork-join.md](patterns/fork-join.md) - Parallel operations
- [patterns/condition.md](patterns/condition.md) - Branching logic
- [patterns/iteration.md](patterns/iteration.md) - Collection processing
- [patterns/aspects.md](patterns/aspects.md) - Cross-cutting concerns

### Use Cases
- [use-cases/structure.md](use-cases/structure.md) - Anatomy and conventions
- [use-cases/complete-example.md](use-cases/complete-example.md) - Full RegisterUser walkthrough

### Testing & Organization
- [testing/patterns.md](testing/patterns.md) - Test strategies and assertions
- [project-structure/organization.md](project-structure/organization.md) - Vertical slicing

### Specialized Subagents
- **../../jbct-coder.md** - Autonomous code generation agent (invoke with Task tool)
  - Generates complete use cases with validation, tests, and adapters
  - Follows deterministic algorithms for consistent output
  - Includes evolutionary testing strategy
- **../../jbct-reviewer.md** - Autonomous code review agent (invoke with Task tool)
  - Comprehensive JBCT compliance checking
  - Pattern validation and naming convention enforcement
  - Detailed violation reports with fixes

### Documentation
- **../../CODING_GUIDE.md** - Complete technical reference (100+ pages)
- **../../series/** - 6-part progressive learning series
- **../../TECHNOLOGY.md** - High-level pattern catalog
- **../../CHANGELOG.md** - Version history and changes

Repository: https://github.com/siy/coding-technology
