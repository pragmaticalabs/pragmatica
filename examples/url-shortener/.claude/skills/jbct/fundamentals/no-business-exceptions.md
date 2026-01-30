# No Business Exceptions

**Core Principle**: Business failures are expected outcomes, not exceptions. They flow through `Result` or `Promise` as typed `Cause` values.

## The Problem with Exceptions

### Traditional exception-based code:

```java
public User registerUser(String email, String password) throws
    InvalidEmailException,
    WeakPasswordException,
    EmailAlreadyExistsException,
    DatabaseException {

    if (!isValidEmail(email)) {
        throw new InvalidEmailException(email);
    }

    if (!isStrongPassword(password)) {
        throw new WeakPasswordException();
    }

    if (userRepository.existsByEmail(email)) {
        throw new EmailAlreadyExistsException(email);
    }

    return userRepository.save(new User(email, password));
}
```

**Problems**:
1. Exceptions not visible in signature (unchecked) or verbose (checked)
2. Mixes business logic failures with technical errors
3. Difficult to compose (try-catch blocks break flow)
4. Forces client to handle with try-catch
5. Stack trace generation is expensive

## The JBCT Solution: Typed Causes

### Using Result<T> with Cause:

```java
public Result<User> registerUser(Email email, Password password) {
    return checkEmailAvailable(email)
        .flatMap(e -> hashPassword(password))
        .flatMap(hash -> saveUser(email, hash));
}

// Each step returns Result<T> with typed failures
private Result<Email> checkEmailAvailable(Email email) {
    if (userRepository.existsByEmail(email.value())) {
        return RegistrationError.EmailAlreadyExists.INSTANCE.result();
    }
    return Result.success(email);
}
```

## Defining Cause Types

### Simple Causes (Singletons)

```java
public sealed interface RegistrationError extends Cause {

    enum EmailAlreadyExists implements RegistrationError {
        INSTANCE;

        @Override
        public String message() {
            return "Email address already registered";
        }
    }

    enum WeakPassword implements RegistrationError {
        INSTANCE;

        @Override
        public String message() {
            return "Password does not meet security requirements";
        }
    }
}
```

### Causes with Context

```java
public sealed interface UserError extends Cause {

    record NotFound(UserId userId) implements UserError {
        @Override
        public String message() {
            return "User not found: " + userId.value();
        }
    }

    record Locked(UserId userId, Instant until) implements UserError {
        @Override
        public String message() {
            return "User " + userId.value() + " locked until " + until;
        }
    }
}
```

### Composite Causes

Used automatically by `Result.all()` to accumulate failures:

```java
Result<ValidRequest> result =
    Result.all(Email.email(emailRaw),
              Password.password(passwordRaw))
          .map(ValidRequest::new);

// If both fail, result contains CompositeCause with both errors
result.onFailure(cause -> {
    if (cause instanceof CompositeCause composite) {
        composite.causes().forEach(error ->
            System.err.println(error.message())
        );
    }
});
```

## Error Hierarchies

### Use Sealed Interfaces

```java
public sealed interface OrderError extends Cause {

    // Validation errors
    sealed interface ValidationError extends OrderError {
        enum EmptyCart implements ValidationError {
            INSTANCE;
            @Override
            public String message() {
                return "Cannot place order with empty cart";
            }
        }

        record InvalidQuantity(int quantity) implements ValidationError {
            @Override
            public String message() {
                return "Invalid quantity: " + quantity;
            }
        }
    }

    // Business rule errors
    sealed interface BusinessRuleError extends OrderError {
        record InsufficientStock(ProductId productId, int available)
            implements BusinessRuleError {
            @Override
            public String message() {
                return "Insufficient stock for " + productId.value()
                     + ", available: " + available;
            }
        }

        record PaymentFailed(String reason) implements BusinessRuleError {
            @Override
            public String message() {
                return "Payment failed: " + reason;
            }
        }
    }
}
```

## Converting Causes

### Creating Results from Causes

```java
// Prefer this:
Cause cause = UserError.NotFound.INSTANCE;
Result<User> result = cause.result();

// Over this:
Result<User> result = Result.failure(UserError.NotFound.INSTANCE);
```

### Creating Promises from Causes

```java
// Prefer this:
Cause cause = DatabaseError.ConnectionFailed.INSTANCE;
Promise<User> promise = cause.promise();

// Over this:
Promise<User> promise = Promise.failure(DatabaseError.ConnectionFailed.INSTANCE);
```

## Exception Boundaries

### Lift Exceptions at Adapter Edges

Technical exceptions (I/O, network, database) should be caught at adapter boundaries:

```java
public Promise<User> loadUser(UserId id) {
    return Promise.lift(
        DatabaseError::connectionError,  // Map SQLException → DatabaseError
        () -> jdbcTemplate.queryForObject(
            "SELECT * FROM users WHERE id = ?",
            new Object[]{id.value()},
            this::mapUser
        )
    );
}
```

### Custom Exception Mappers

```java
public sealed interface DatabaseError extends Cause {
    static Cause connectionError(Throwable throwable) {
        if (throwable instanceof SQLTimeoutException) {
            return Timeout.INSTANCE;
        }
        if (throwable instanceof SQLIntegrityConstraintViolationException) {
            return ConstraintViolation.INSTANCE;
        }
        return UnknownError.of(throwable.getMessage());
    }

    enum Timeout implements DatabaseError {
        INSTANCE;
        @Override
        public String message() {
            return "Database operation timed out";
        }
    }

    enum ConstraintViolation implements DatabaseError {
        INSTANCE;
        @Override
        public String message() {
            return "Database constraint violation";
        }
    }

    record UnknownError(String detail) implements DatabaseError {
        @Override
        public String message() {
            return "Database error: " + detail;
        }
    }
}
```

## Error Recovery

### Recover from Specific Failures

```java
public Promise<User> loadUserWithFallback(UserId id) {
    return loadUser(id)
        .recover(cause -> {
            if (cause instanceof UserError.NotFound) {
                return getDefaultUser();  // Fallback to default
            }
            return cause.result();  // Propagate other errors
        });
}
```

### Provide Default Values

```java
public Promise<Theme> getUserTheme(UserId id) {
    return loadTheme(id)
        .or(Theme.DEFAULT);  // Use default theme if not found
}
```

## Handling Errors in Use Cases

### Pattern 1: Early Return

```java
public Result<Response> execute(Request request) {
    return ValidRequest.validRequest(request)
        .flatMap(this::checkBusinessRules)
        .flatMap(this::processOrder)
        .map(this::toResponse);
}
// First failure short-circuits the chain
```

### Pattern 2: Accumulate Failures

```java
public Result<Response> execute(Request request) {
    // Validate all fields, collect all errors
    return Result.all(
        Email.email(request.email()),
        Password.password(request.password()),
        UserId.userId(request.userId())
    ).map(ValidRequest::new)
     .flatMap(this::process);
}
```

### Pattern 3: Conditional Recovery

```java
public Promise<Response> execute(Request request) {
    return ValidRequest.validRequest(request)
        .async()
        .flatMap(loadUser::apply)
        .recover(cause -> {
            if (cause instanceof UserError.NotFound) {
                // Create new user if not found
                return createUser.apply(request).async();
            }
            return cause.promise();  // Propagate other errors
        })
        .map(this::toResponse);
}
```

## Testing Error Cases

### Test Failure Paths

```java
@Test
void execute_fails_whenEmailInvalid() {
    var request = new Request("invalid-email", "ValidPass123");

    useCase.execute(request)
           .onSuccess(Assertions::fail);  // Fail if unexpectedly succeeds
}

@Test
void execute_fails_whenEmailAlreadyExists() {
    var request = new Request("existing@example.com", "ValidPass123");

    useCase.execute(request)
           .async()
           .await()
           .onFailure(cause -> {
               assertTrue(cause instanceof RegistrationError.EmailAlreadyExists);
           })
           .onSuccess(Assertions::fail);
}
```

### Test Error Messages

```java
@Test
void error_hasDescriptiveMessage() {
    Cause error = UserError.NotFound.of(UserId.userId(UUID.randomUUID()));

    assertTrue(error.message().contains("User not found"));
    assertTrue(error.message().contains(userId.toString()));
}
```

## Best Practices

### 1. Use Sealed Interfaces

Ensures exhaustive handling:

```java
public String handleError(OrderError error) {
    return switch (error) {
        case OrderError.ValidationError.EmptyCart e -> "Cart is empty";
        case OrderError.ValidationError.InvalidQuantity e -> "Invalid quantity: " + e.quantity();
        case OrderError.BusinessRuleError.InsufficientStock e -> "Out of stock";
        case OrderError.BusinessRuleError.PaymentFailed e -> "Payment failed";
        // Compiler ensures all cases covered
    };
}
```

### 2. Prefer Enum Singletons for Simple Errors

```java
enum NotFound implements UserError {
    INSTANCE;
    @Override
    public String message() { return "User not found"; }
}
```

### 3. Use Records for Errors with Context

```java
record InsufficientFunds(Money required, Money available) implements PaymentError {
    @Override
    public String message() {
        return "Insufficient funds: required " + required + ", available " + available;
    }
}
```

### 4. Never Throw Business Exceptions

```java
// ❌ WRONG
if (!isValid(email)) {
    throw new InvalidEmailException(email);
}

// ✅ CORRECT
if (!isValid(email)) {
    return EmailError.Invalid.INSTANCE.result();
}
```

### 5. Lift Technical Exceptions Only at Boundaries

```java
// Adapter layer - lift exceptions
public Promise<User> loadUser(UserId id) {
    return Promise.lift(
        DatabaseError::cause,
        () -> repository.findById(id)
    );
}

// Business logic layer - use Result/Promise
public Result<Order> validateOrder(Order order) {
    if (order.items().isEmpty()) {
        return OrderError.EmptyCart.INSTANCE.result();
    }
    return Result.success(order);
}
```

## Anti-Patterns

### ❌ Throwing Exceptions for Business Logic

```java
// DON'T
public User getUser(UserId id) {
    return userRepository.findById(id)
        .orElseThrow(() -> new UserNotFoundException(id));
}
```

### ❌ Using String Messages Instead of Typed Causes

```java
// DON'T
Result.failure(Causes.fromMessage("User not found"));

// DO
UserError.NotFound.INSTANCE.result();
```

### ❌ Catching and Rethrowing in Business Logic

```java
// DON'T
try {
    return processOrder(order);
} catch (Exception e) {
    throw new OrderProcessingException(e);
}

// DO - use Result/Promise composition
```

## Related

- [four-return-kinds.md](four-return-kinds.md) - Result<T> and Promise<T> for failures
- [parse-dont-validate.md](parse-dont-validate.md) - Validation failures as Causes
- [../patterns/leaf.md](../patterns/leaf.md) - Exception lifting in adapters
- [../testing/patterns.md](../testing/patterns.md) - Testing error cases
