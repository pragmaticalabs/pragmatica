# Testing Patterns

**Purpose**: Test strategies for JBCT code using functional assertions and integration-first approach.

## Core Testing Approach

**Integration-first**: Test composition, not individual components. Value objects and use cases are primary test targets.

## Functional Assertions

### Testing Success with onSuccess

```java
@Test
void validate_succeeds_forValidEmail() {
    Email.email("test@example.com")
         .onFailure(Assertions::fail)
         .onSuccess(email -> {
             assertEquals("test@example.com", email.value());
         });
}
```

### Testing Failure with onSuccess(Assertions::fail)

```java
@Test
void validate_fails_forInvalidEmail() {
    Email.email("invalid")
         .onSuccess(Assertions::fail);  // Fail if unexpectedly succeeds
}
```

### Testing Async Operations

```java
@Test
void execute_succeeds_withValidInput() {
    useCase.execute(validRequest())
           .await()  // Block until complete
           .onFailure(Assertions::fail)
           .onSuccess(response -> {
               assertNotNull(response.userId());
           });
}
```

## What to Test

### 1. Value Objects (MANDATORY)

Test all validation paths:

```java
class EmailTest {
    @Test
    void email_succeeds_forValidFormat() {
        Email.email("user@example.com")
             .onFailure(Assertions::fail)
             .onSuccess(email -> assertEquals("user@example.com", email.value()));
    }

    @Test
    void email_fails_forNull() {
        Email.email(null)
             .onSuccess(Assertions::fail);
    }

    @Test
    void email_fails_forInvalidFormat() {
        Email.email("not-an-email")
             .onSuccess(Assertions::fail);
    }

    @Test
    void email_trimsWhitespace() {
        Email.email("  user@example.com  ")
             .onFailure(Assertions::fail)
             .onSuccess(email -> assertEquals("user@example.com", email.value()));
    }
}
```

### 2. Use Cases (MANDATORY)

Test with stubs, end-to-end style:

```java
class RegisterUserTest {
    @Test
    void execute_succeeds_withValidInput() {
        var checkEmail = successStub();
        var saveUser = userStub();
        var useCase = RegisterUser.registerUser(checkEmail, saveUser);

        useCase.execute(validRequest())
               .await()
               .onFailure(Assertions::fail)
               .onSuccess(response -> {
                   assertNotNull(response.userId());
               });
    }

    @Test
    void execute_fails_whenEmailInvalid() {
        var useCase = RegisterUser.registerUser(successStub(), successStub());

        useCase.execute(new Request("invalid", "ValidPass123"))
               .await()
               .onSuccess(Assertions::fail);
    }

    @Test
    void execute_fails_whenEmailExists() {
        var checkEmail = failureStub(EmailError.AlreadyExists.INSTANCE);
        var useCase = RegisterUser.registerUser(checkEmail, successStub());

        useCase.execute(validRequest())
               .await()
               .onSuccess(Assertions::fail);
    }
}
```

### 3. Complex Leaves (RECOMMENDED)

Test leaves with complex business logic:

```java
@Test
void calculateTax_appliesCorrectRate() {
    Money subtotal = Money.of(100, USD);
    TaxRate rate = TaxRate.of(0.15);

    Money tax = calculator.calculateTax(subtotal, rate);

    assertEquals(Money.of(15, USD), tax);
}
```

### 4. Adapters (RECOMMENDED)

Test adapters with mocked repositories:

```java
@Test
void loadUser_succeeds_whenUserExists() {
    UserId id = UserId.random();
    User expected = testUser(id);

    when(repository.findById(id.value())).thenReturn(expected);

    adapter.loadUser(id)
           .await()
           .onFailure(Assertions::fail)
           .onSuccess(user -> assertEquals(expected, user));
}

@Test
void loadUser_fails_whenUserNotFound() {
    UserId id = UserId.random();

    when(repository.findById(id.value()))
        .thenThrow(new EmptyResultDataAccessException(1));

    adapter.loadUser(id)
           .await()
           .onSuccess(Assertions::fail);
}
```

## Test Organization

### Nested Test Classes

```java
class RegisterUserTest {
    @Nested
    class Validation {
        @Test
        void fails_whenEmailInvalid() { ... }

        @Test
        void fails_whenPasswordWeak() { ... }
    }

    @Nested
    class HappyPath {
        @Test
        void succeeds_withValidInput() { ... }
    }

    @Nested
    class ErrorCases {
        @Test
        void fails_whenEmailExists() { ... }

        @Test
        void fails_whenDatabaseUnavailable() { ... }
    }
}
```

### Test Data Builders

```java
class TestData {
    static User user(UserId id) {
        return new User(
            id,
            Email.unsafe("test@example.com"),
            "Test User",
            Instant.now()
        );
    }

    static RegisterUser.Request validRequest() {
        return new RegisterUser.Request(
            "test@example.com",
            "ValidPass123"
        );
    }

    static RegisterUser.Request requestWith(String email, String password) {
        return new RegisterUser.Request(email, password);
    }
}
```

### Stub Builders

```java
class Stubs {
    static <T> Fn1<Promise<T>, ?> successStub(T value) {
        return ignored -> Promise.success(value);
    }

    static <T> Fn1<Promise<T>, ?> failureStub(Cause cause) {
        return ignored -> cause.promise();
    }

    static RegisterUser.CheckEmail checkEmailStub(boolean exists) {
        return exists
            ? valid -> EmailError.AlreadyExists.INSTANCE.promise()
            : valid -> Promise.success(valid);
    }
}
```

## Parameterized Tests

```java
@ParameterizedTest
@CsvSource({
    "user@example.com, true",
    "invalid, false",
    "@example.com, false",
    "user@, false"
})
void email_validation(String input, boolean shouldSucceed) {
    Result<Email> result = Email.email(input);

    if (shouldSucceed) {
        result.onFailure(Assertions::fail);
    } else {
        result.onSuccess(Assertions::fail);
    }
}
```

## Testing Error Messages

```java
@Test
void error_containsExpectedMessage() {
    Email.email("invalid")
         .onFailure(cause -> {
             assertTrue(cause.message().contains("Invalid email"));
         })
         .onSuccess(Assertions::fail);
}

@Test
void compositeCause_containsAllErrors() {
    var request = new Request("invalid", "weak");

    ValidRequest.validRequest(request)
                .onFailure(cause -> {
                    assertTrue(cause instanceof CompositeCause);
                    CompositeCause composite = (CompositeCause) cause;
                    assertEquals(2, composite.causes().size());
                })
                .onSuccess(Assertions::fail);
}
```

## Assertions Utilities

### ResultAssertions

```java
public class ResultAssertions {
    public static <T> void assertSuccess(Result<T> result) {
        result.onFailure(cause -> fail("Expected success but got: " + cause.message()))
              .onSuccess(value -> {});
    }

    public static <T> void assertFailure(Result<T> result) {
        result.onSuccess(value -> fail("Expected failure but got success"))
              .onFailure(cause -> {});
    }

    public static <T> void assertFailureType(
        Result<T> result,
        Class<? extends Cause> expectedType
    ) {
        result.onSuccess(value -> fail("Expected failure but got success"))
              .onFailure(cause -> {
                  assertTrue(
                      expectedType.isInstance(cause),
                      "Expected " + expectedType.getName() +
                      " but got " + cause.getClass().getName()
                  );
              });
    }
}
```

### PromiseTestUtils

```java
public class PromiseTestUtils {
    public static <T> Result<T> awaitAndExpectSuccess(Promise<T> promise) {
        Result<T> result = promise.await();
        result.onFailure(cause -> fail("Expected success: " + cause.message()));
        return result;
    }

    public static <T> void awaitAndExpectFailure(Promise<T> promise) {
        promise.await()
               .onSuccess(value -> fail("Expected failure but got success"));
    }
}
```

## Integration Tests

### With Test Containers

```java
@Testcontainers
class RegisterUserIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15");

    @Test
    void registerUser_persistsToDatabase() {
        // Real database, real adapters
        var useCase = RegisterUser.registerUser(
            realCheckEmail,
            realHashPassword,
            realSaveUser,
            realSendEmail
        );

        var response = useCase.execute(validRequest())
                              .await()
                              .onFailure(Assertions::fail)
                              .value();

        // Verify in database
        User saved = repository.findById(UUID.fromString(response.userId()));
        assertNotNull(saved);
    }
}
```

## Test Naming

Pattern: `methodName_outcome_condition`

```java
@Test
void email_succeeds_forValidFormat() { ... }

@Test
void email_fails_forNull() { ... }

@Test
void execute_fails_whenEmailExists() { ... }

@Test
void execute_succeeds_withValidInput() { ... }
```

## What NOT to Test

- Framework code (Spring, JOOQ, etc.)
- Simple getters/setters
- Trivial mappers with no logic
- Generated code

## Related

- [../use-cases/structure.md](../use-cases/structure.md) - What to test
- [../use-cases/complete-example.md](../use-cases/complete-example.md) - Full test suite
- [../fundamentals/parse-dont-validate.md](../fundamentals/parse-dont-validate.md) - Value object testing
