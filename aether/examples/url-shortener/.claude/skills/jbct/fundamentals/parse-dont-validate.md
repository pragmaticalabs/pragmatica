# Parse, Don't Validate

**Core Principle**: Validation is parsing. If an object instance exists, it must be valid.

## The Pattern

**Traditional validation** (❌ Wrong):
```java
public record Email(String value) {
    public Result<Email> validate() {
        if (value == null || !value.matches(PATTERN)) {
            return Result.failure(EmailError.Invalid.INSTANCE);
        }
        return Result.success(this);
    }
}

// Problem: Can construct invalid emails
Email bad = new Email(null);  // Compiles, but invalid!
```

**Parse, Don't Validate** (✅ Correct):
```java
public record Email(String value) {
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    private static final Fn1<Cause, String> INVALID_EMAIL =
        Causes.forValue("Invalid email: {}");

    // Private constructor - cannot construct directly
    private Email {}

    // Static factory - only way to create Email
    public static Result<Email> email(String raw) {
        return Verify.ensure(raw, Verify.Is::notNull)
            .map(String::trim)
            .filter(INVALID_EMAIL, EMAIL_PATTERN.asMatchPredicate())
            .map(Email::new);
    }
}

// Usage
Result<Email> result = Email.email(userInput);
// If result is success, Email is guaranteed valid
```

## Key Elements

### 1. Private Constructor

Make constructor private or package-private:

```java
public record Email(String value) {
    private Email {}  // Private canonical constructor
}

// Or package-private:
public record Email(String value) {
    Email {}  // Package-private
}
```

### 2. Static Factory Method

Factory named after type (lowercase-first):

```java
public static Result<Email> email(String raw) { ... }
public static Result<Password> password(String raw) { ... }
public static Result<UserId> userId(String raw) { ... }
```

### 3. Validation = Construction

All validation happens during parsing:

```java
public static Result<Email> email(String raw) {
    return Verify.ensure(raw, Verify.Is::notNull)        // Check not null
        .map(String::trim)                               // Normalize
        .filter(INVALID_EMAIL,                           // Validate format
                EMAIL_PATTERN.asMatchPredicate())
        .map(Email::new);                                // Construct only if valid
}
```

## Complete Examples

### Simple Value Object

```java
public record UserId(UUID value) {
    private static final Fn1<Cause, String> INVALID_USER_ID =
        Causes.forValue("Invalid user ID: {}");

    private UserId {}

    public static Result<UserId> userId(String raw) {
        return Verify.ensure(raw, Verify.Is::notNull)
            .map(String::trim)
            .filter(INVALID_USER_ID, Verify.Is::notEmpty)
            .flatMap(str -> Result.lift(() -> UUID.fromString(str))
                                  .mapError(e -> INVALID_USER_ID.apply(raw)))
            .map(UserId::new);
    }

    public static Result<UserId> userId(UUID value) {
        return Verify.ensure(value, Verify.Is::notNull)
            .mapError(e -> INVALID_USER_ID.apply("null"))
            .map(UserId::new);
    }
}
```

### Complex Value Object with Multiple Rules

```java
public record Password(String value) {
    private static final int MIN_LENGTH = 8;
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");
    private static final Pattern HAS_UPPER = Pattern.compile(".*[A-Z].*");

    private static final Fn1<Cause, String> TOO_SHORT =
        Causes.forValue("Password too short (min " + MIN_LENGTH + "): {}");
    private static final Fn1<Cause, String> NO_DIGIT =
        Causes.forValue("Password must contain digit: {}");
    private static final Fn1<Cause, String> NO_UPPER =
        Causes.forValue("Password must contain uppercase: {}");

    private Password {}

    public static Result<Password> password(String raw) {
        return Verify.ensure(raw, Verify.Is::notNull)
            .map(String::trim)
            .filter(TOO_SHORT, s -> s.length() >= MIN_LENGTH)
            .filter(NO_DIGIT, HAS_DIGIT.asMatchPredicate())
            .filter(NO_UPPER, HAS_UPPER.asMatchPredicate())
            .map(Password::new);
    }
}
```

### Composite Value Object

```java
public record Credentials(Email email, Password password) {
    private Credentials {}

    public static Result<Credentials> credentials(String emailRaw, String passwordRaw) {
        return Result.all(Email.email(emailRaw),
                         Password.password(passwordRaw))
                     .map(Credentials::new);
    }
}
```

## Naming Conventions

### Factory Methods

Named after type, lowercase-first:

```java
Email.email(String)
Password.password(String)
UserId.userId(String)
OrderId.orderId(Long)
```

### Error Causes

Use constants with descriptive names:

```java
private static final Fn1<Cause, String> INVALID_EMAIL =
    Causes.forValue("Invalid email: {}");

private static final Fn1<Cause, String> TOO_SHORT =
    Causes.forValue("Password too short: {}");
```

## Validated Inputs

Apply same pattern to use case inputs:

```java
public interface RegisterUser {
    record Request(String email, String password) {}

    // Validated input with Valid prefix
    record ValidRequest(Email email, Password password) {
        // Private constructor
        private ValidRequest {}

        // Factory method
        static Result<ValidRequest> validRequest(Request raw) {
            return Result.all(Email.email(raw.email()),
                             Password.password(raw.password()))
                         .map(ValidRequest::new);
        }
    }
}
```

**Naming**: Use `Valid` prefix (not `Validated`):
- `ValidRequest` ✅
- `ValidUser` ✅
- `ValidCredentials` ✅
- ~~`ValidatedRequest`~~ ❌

## Benefits

### Type Safety

```java
// Cannot create invalid email
Email email = new Email("invalid");  // Compilation error: private constructor

// Must go through validation
Result<Email> result = Email.email("test@example.com");
result.onSuccess(validEmail -> {
    // validEmail is guaranteed valid here
});
```

### Composability

```java
// Combine validated values
Result<ValidRequest> validated =
    Result.all(Email.email(emailRaw),
              Password.password(passwordRaw),
              UserId.userId(userIdRaw))
          .map(ValidRequest::new);
```

### No Defensive Checks

```java
public void sendEmail(Email to, String subject) {
    // No need to check if 'to' is valid - type guarantees it
    emailService.send(to.value(), subject);
}
```

### Clear Error Aggregation

```java
Result<ValidRequest> result = ValidRequest.validRequest(request);

result.onFailure(cause -> {
    if (cause instanceof CompositeCause composite) {
        // Multiple validation failures collected
        composite.causes().forEach(System.err::println);
    }
});
```

## Common Patterns

### Optional Fields

```java
public record UserProfile(
    Email email,
    Option<String> middleName,
    Option<PhoneNumber> phone
) {
    private UserProfile {}

    public static Result<UserProfile> userProfile(
        String emailRaw,
        String middleNameRaw,
        String phoneRaw
    ) {
        Result<Email> emailResult = Email.email(emailRaw);

        // Optional fields: parse if present, Option.none() if null/empty
        Option<Result<PhoneNumber>> phoneResult =
            Option.option(phoneRaw)
                  .filter(s -> !s.isBlank())
                  .map(PhoneNumber::phoneNumber);

        return emailResult.flatMap(email ->
            phoneResult.map(pr -> pr.map(phone ->
                new UserProfile(email, Option.option(middleNameRaw), Option.some(phone))
            )).orElse(Result.success(
                new UserProfile(email, Option.option(middleNameRaw), Option.none())
            ))
        );
    }
}
```

### Collections

```java
public record OrderItems(List<Item> items) {
    private static final Fn1<Cause, Integer> MIN_ITEMS_ERROR =
        Causes.forValue("Order must have at least {} items");

    private OrderItems {}

    public static Result<OrderItems> orderItems(List<String> rawItems) {
        // Validate each item
        List<Result<Item>> itemResults = rawItems.stream()
            .map(Item::item)
            .toList();

        // Collect all results
        return Result.allOf(itemResults)
            .filter(MIN_ITEMS_ERROR, list -> !list.isEmpty())
            .map(OrderItems::new);
    }
}
```

## Anti-Patterns

### ❌ Separate Validation Method

```java
// DON'T DO THIS
public record Email(String value) {
    public Result<Email> validate() { ... }  // Wrong!
}
```

### ❌ Public Constructor with Invalid States

```java
// DON'T DO THIS
public record Email(String value) {
    public Email {  // Public constructor
        if (value == null) {
            throw new IllegalArgumentException();  // Runtime failure
        }
    }
}
```

### ❌ Mutable Validation State

```java
// DON'T DO THIS
public class Email {
    private String value;
    private boolean valid = false;

    public void validate() {
        valid = checkFormat(value);
    }
}
```

## Related

- [four-return-kinds.md](four-return-kinds.md) - Using Result<T> for validation
- [no-business-exceptions.md](no-business-exceptions.md) - Typed failures instead of exceptions
- [../use-cases/structure.md](../use-cases/structure.md) - ValidRequest pattern
- [../testing/patterns.md](../testing/patterns.md) - Testing value objects
