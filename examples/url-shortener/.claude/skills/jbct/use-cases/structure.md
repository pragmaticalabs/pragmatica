# Use Case Structure

**Purpose**: Define the anatomy of a JBCT use case with proper structure, naming, and composition.

## Basic Template

```java
public interface UseCaseName extends UseCase.WithPromise<Response, Request> {
    // Input from external world
    record Request(String field1, String field2) {}

    // Output to external world
    record Response(String result) {}

    // Validated input (Valid prefix, not Validated)
    record ValidRequest(ValueObject1 vo1, ValueObject2 vo2) {
        private ValidRequest {}  // Private constructor

        static Result<ValidRequest> validRequest(Request raw) {
            return Result.all(
                ValueObject1.valueObject1(raw.field1()),
                ValueObject2.valueObject2(raw.field2())
            ).map(ValidRequest::new);
        }
    }

    // Steps as single-method interfaces
    interface Step1 { Promise<Intermediate> apply(ValidRequest valid); }
    interface Step2 { Promise<Result> apply(Intermediate intermediate); }

    // Factory method returns lambda
    static UseCaseName useCaseName(Step1 step1, Step2 step2) {
        return request -> ValidRequest.validRequest(request)
            .async()
            .flatMap(step1::apply)
            .flatMap(step2::apply)
            .map(result -> new Response(result.value()));
    }
}
```

## Key Elements

### 1. Interface Declaration

```java
public interface RegisterUser extends UseCase.WithPromise<Response, Request> {
    // UseCase.WithPromise<OUT, IN> for async use cases
    // UseCase.WithResult<OUT, IN> for sync use cases
}
```

### 2. Request Record

External input - uses primitive types or strings:

```java
record Request(String email, String password, String userId) {}
```

**Not** domain types - validation happens in ValidRequest factory.

### 3. Response Record

External output - can use domain types or primitives:

```java
record Response(UserId userId, String confirmationToken) {}
```

### 4. ValidRequest Record

Parsed, validated input with domain types:

```java
record ValidRequest(Email email, Password password, UserId userId) {
    private ValidRequest {}  // Private constructor

    static Result<ValidRequest> validRequest(Request raw) {
        return Result.all(
            Email.email(raw.email()),
            Password.password(raw.password()),
            UserId.userId(raw.userId())
        ).map(ValidRequest::new);
    }
}
```

**Critical**: Use `Valid` prefix (not `Validated`).

### 5. Step Interfaces

Single-method interfaces for each step:

```java
interface CheckEmail { Promise<ValidRequest> apply(ValidRequest valid); }
interface SaveUser { Promise<User> apply(ValidRequest valid); }
interface SendEmail { Promise<Token> apply(User user); }
```

**Nested** inside use case interface.

### 6. Factory Method

Returns lambda directly (NOT nested record):

```java
// ✅ CORRECT - Returns lambda
static RegisterUser registerUser(CheckEmail check, SaveUser save, SendEmail send) {
    return request -> ValidRequest.validRequest(request)
        .async()
        .flatMap(check::apply)
        .flatMap(save::apply)
        .flatMap(send::apply)
        .map(token -> new Response(user.id(), token));
}

// ❌ WRONG - Nested record implementation
static RegisterUser registerUser(CheckEmail check, SaveUser save) {
    record registerUser(CheckEmail check, SaveUser save) implements RegisterUser {
        @Override
        public Promise<Response> execute(Request request) { ... }
    }
    return new registerUser(check, save);
}
```

**Rule**: Records are for data, lambdas are for behavior.

## Complete Example

```java
public interface RegisterUser extends UseCase.WithPromise<Response, Request> {
    record Request(String email, String password) {}

    record Response(UserId userId, ConfirmationToken token) {}

    record ValidRequest(Email email, Password password) {
        private ValidRequest {}

        static Result<ValidRequest> validRequest(Request raw) {
            return Result.all(Email.email(raw.email()),
                             Password.password(raw.password()))
                         .map(ValidRequest::new);
        }
    }

    interface CheckEmailAvailability {
        Promise<ValidRequest> apply(ValidRequest valid);
    }

    interface HashPassword {
        Promise<HashedPassword> apply(ValidRequest valid);
    }

    interface SaveUser {
        Promise<User> apply(ValidRequest valid, HashedPassword hashed);
    }

    interface SendConfirmation {
        Promise<ConfirmationToken> apply(User user);
    }

    static RegisterUser registerUser(
        CheckEmailAvailability checkEmail,
        HashPassword hashPassword,
        SaveUser saveUser,
        SendConfirmation sendConfirmation
    ) {
        return request -> ValidRequest.validRequest(request)
            .async()
            .flatMap(checkEmail::apply)
            .flatMap(valid ->
                hashPassword.apply(valid)
                    .flatMap(hashed -> saveUser.apply(valid, hashed))
            )
            .flatMap(sendConfirmation::apply)
            .map(token -> new Response(user.id(), token));
    }
}
```

## Naming Conventions

### Factory Method Name

Lowercase-first, same as type:

```java
RegisterUser.registerUser(...)
GetUserProfile.getUserProfile(...)
PlaceOrder.placeOrder(...)
```

### ValidRequest

Use `Valid` prefix:
- `ValidRequest` ✅
- `ValidCredentials` ✅
- `ValidOrder` ✅
- ~~`ValidatedRequest`~~ ❌

### Step Interfaces

Descriptive action names:
- `CheckEmail`
- `SaveUser`
- `FetchProfile`
- `SendNotification`

## Variations

### Sync Use Case

```java
public interface ValidateOrder extends UseCase.WithResult<Response, Request> {
    record Request(String orderId) {}
    record Response(boolean isValid) {}

    // Factory returns lambda with Result
    static ValidateOrder validateOrder(OrderRepository repo) {
        return request -> OrderId.orderId(request.orderId())
            .flatMap(repo::findOrder)
            .map(order -> new Response(order.isValid()));
    }
}
```

### No Validation Needed

```java
public interface GetUserById extends UseCase.WithPromise<Response, Request> {
    record Request(String userId) {}
    record Response(User user) {}

    // No ValidRequest - direct parsing
    static GetUserById getUserById(FetchUser fetchUser) {
        return request -> UserId.userId(request.userId())
            .async()
            .flatMap(fetchUser::apply)
            .map(Response::new);
    }
}
```

### Multiple Valid Records

```java
public interface TransferMoney {
    record Request(String fromAccount, String toAccount, String amount) {}

    record ValidAccounts(AccountId from, AccountId to) {
        static Result<ValidAccounts> validAccounts(String from, String to) {
            return Result.all(AccountId.accountId(from),
                             AccountId.accountId(to))
                         .map(ValidAccounts::new);
        }
    }

    record ValidAmount(Money amount) {
        static Result<ValidAmount> validAmount(String raw) {
            return Money.money(raw).map(ValidAmount::new);
        }
    }

    // Combine both
    record ValidRequest(ValidAccounts accounts, ValidAmount amount) {
        static Result<ValidRequest> validRequest(Request raw) {
            return Result.all(
                ValidAccounts.validAccounts(raw.fromAccount(), raw.toAccount()),
                ValidAmount.validAmount(raw.amount())
            ).map(ValidRequest::new);
        }
    }
}
```

## Assembly

### At Application Startup

```java
@Configuration
public class UseCaseConfig {
    @Bean
    public RegisterUser registerUser(
        UserRepository userRepo,
        EmailService emailService,
        PasswordHasher hasher
    ) {
        // Create adapters
        var checkEmail = CheckEmailAdapter.checkEmail(userRepo);
        var hashPassword = HashPasswordAdapter.hashPassword(hasher);
        var saveUser = SaveUserAdapter.saveUser(userRepo);
        var sendEmail = SendEmailAdapter.sendEmail(emailService);

        // Assemble use case
        return RegisterUser.registerUser(
            checkEmail,
            hashPassword,
            saveUser,
            sendEmail
        );
    }
}
```

### Controller Usage

```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    private final RegisterUser registerUser;

    public UserController(RegisterUser registerUser) {
        this.registerUser = registerUser;
    }

    @PostMapping("/register")
    public Promise<ResponseEntity<RegisterUser.Response>> register(
        @RequestBody RegisterUser.Request request
    ) {
        return registerUser.execute(request)
            .map(ResponseEntity::ok)
            .recover(cause -> ResponseEntity.badRequest().build());
    }
}
```

## Anti-Patterns

### ❌ Nested Record in Factory

```java
// DON'T
static RegisterUser registerUser(CheckEmail check, SaveUser save) {
    record registerUser(CheckEmail check, SaveUser save) implements RegisterUser {
        @Override
        public Promise<Response> execute(Request request) { ... }
    }
    return new registerUser(check, save);
}
```

### ❌ Public ValidRequest Constructor

```java
// DON'T
record ValidRequest(Email email, Password password) {
    // Public constructor allows bypassing validation!
}
```

### ❌ Framework Dependencies in Use Case

```java
// DON'T
public interface RegisterUser {
    @Autowired
    private UserRepository userRepository;  // Framework coupling!
}
```

### ❌ Business Logic in Factory

```java
// DON'T
static RegisterUser registerUser(UserRepository repo) {
    return request -> {
        // Complex business logic here - extract to steps!
        if (request.email().contains("@")) {
            ...
        }
    };
}
```

## Related

- [complete-example.md](complete-example.md) - Full RegisterUser walkthrough
- [../patterns/sequencer.md](../patterns/sequencer.md) - Composition pattern
- [../fundamentals/parse-dont-validate.md](../fundamentals/parse-dont-validate.md) - ValidRequest pattern
