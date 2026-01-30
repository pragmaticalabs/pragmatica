# Complete Use Case Example: RegisterUser

Complete walkthrough of implementing a user registration use case from start to finish.

## Requirements

- Accept email and password
- Validate email format and password strength
- Check email not already registered
- Hash password
- Save user to database
- Send confirmation email
- Return user ID and confirmation token

## Step 1: Define Use Case Interface

```java
package com.example.usecase.registeruser;

import org.pragmatica.lite.result.Result;
import org.pragmatica.lite.promise.Promise;

public interface RegisterUser extends UseCase.WithPromise<Response, Request> {
    record Request(String email, String password) {}

    record Response(String userId, String confirmationToken) {}

    @Override
    Promise<Response> execute(Request request);
}
```

## Step 2: Create Value Objects

### Email

```java
package com.example.domain.shared;

public record Email(String value) {
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    private static final Fn1<Cause, String> INVALID_EMAIL =
        Causes.forValue("Invalid email: {}");

    private Email {}

    public static Result<Email> email(String raw) {
        return Verify.ensure(raw, Verify.Is::notNull)
            .map(String::trim)
            .filter(INVALID_EMAIL, EMAIL_PATTERN.asMatchPredicate())
            .map(Email::new);
    }
}
```

### Password

```java
package com.example.domain.shared;

public record Password(String value) {
    private static final int MIN_LENGTH = 8;
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");
    private static final Pattern HAS_UPPER = Pattern.compile(".*[A-Z].*");

    private static final Fn1<Cause, String> TOO_SHORT =
        Causes.forValue("Password too short (min " + MIN_LENGTH + ")");
    private static final Fn1<Cause, String> NO_DIGIT =
        Causes.forValue("Password must contain digit");
    private static final Fn1<Cause, String> NO_UPPER =
        Causes.forValue("Password must contain uppercase letter");

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

### UserId

```java
package com.example.domain.shared;

import org.pragmatica.lang.parse.Network;

public record UserId(UUID value) {
    private UserId {}

    public static Result<UserId> userId(String raw) {
        return Network.parseUUID(raw)
            .map(UserId::new);
    }

    public static UserId random() {
        return new UserId(UUID.randomUUID());
    }
}
```

## Step 3: Create ValidRequest

```java
public interface RegisterUser extends UseCase.WithPromise<Response, Request> {
    // ... Request, Response ...

    record ValidRequest(Email email, Password password) {
        private ValidRequest {}

        static Result<ValidRequest> validRequest(Request raw) {
            return Result.all(Email.email(raw.email()),
                             Password.password(raw.password()))
                         .map(ValidRequest::new);
        }
    }
}
```

## Step 4: Define Steps

```java
public interface RegisterUser extends UseCase.WithPromise<Response, Request> {
    // ... records ...

    interface CheckEmailAvailability {
        Promise<ValidRequest> apply(ValidRequest valid);
    }

    interface HashPassword {
        Promise<HashedPassword> apply(Password password);
    }

    interface SaveUser {
        Promise<User> apply(Email email, HashedPassword hashed);
    }

    interface SendConfirmationEmail {
        Promise<ConfirmationToken> apply(User user);
    }
}
```

## Step 5: Implement Factory Method

```java
public interface RegisterUser extends UseCase.WithPromise<Response, Request> {
    // ... all records and interfaces ...

    static RegisterUser registerUser(
        CheckEmailAvailability checkEmail,
        HashPassword hashPassword,
        SaveUser saveUser,
        SendConfirmationEmail sendEmail
    ) {
        return request -> ValidRequest.validRequest(request)
            .async()
            .flatMap(checkEmail::apply)
            .flatMap(valid ->
                hashPassword.apply(valid.password())
                    .flatMap(hashed -> saveUser.apply(valid.email(), hashed))
            )
            .flatMap(sendEmail::apply)
            .map(token -> new Response(
                user.id().value().toString(),
                token.value()
            ));
    }
}
```

## Step 6: Implement Adapters

### CheckEmailAvailability

```java
package com.example.adapter.persistence;

public class CheckEmailAvailabilityAdapter implements RegisterUser.CheckEmailAvailability {
    private final UserRepository userRepository;

    public CheckEmailAvailabilityAdapter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Promise<ValidRequest> apply(ValidRequest valid) {
        return Promise.lift(
            DatabaseError::cause,
            () -> {
                if (userRepository.existsByEmail(valid.email().value())) {
                    throw new EmailAlreadyExistsException();
                }
                return valid;
            }
        );
    }

    public static CheckEmailAvailability checkEmail(UserRepository repo) {
        return new CheckEmailAvailabilityAdapter(repo);
    }
}
```

### HashPassword

```java
package com.example.adapter.security;

public class HashPasswordAdapter implements RegisterUser.HashPassword {
    private final PasswordEncoder passwordEncoder;

    public HashPasswordAdapter(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Promise<HashedPassword> apply(Password password) {
        return Promise.lift(
            SecurityError::cause,
            () -> {
                String hashed = passwordEncoder.encode(password.value());
                return new HashedPassword(hashed);
            }
        );
    }

    public static HashPassword hashPassword(PasswordEncoder encoder) {
        return new HashPasswordAdapter(encoder);
    }
}
```

### SaveUser

```java
package com.example.adapter.persistence;

public class SaveUserAdapter implements RegisterUser.SaveUser {
    private final UserRepository userRepository;

    public SaveUserAdapter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Promise<User> apply(Email email, HashedPassword hashed) {
        return Promise.lift(
            DatabaseError::cause,
            () -> {
                User user = new User(
                    UserId.random(),
                    email,
                    hashed,
                    Instant.now()
                );
                userRepository.save(user);
                return user;
            }
        );
    }

    public static SaveUser saveUser(UserRepository repo) {
        return new SaveUserAdapter(repo);
    }
}
```

### SendConfirmationEmail

```java
package com.example.adapter.messaging;

public class SendConfirmationEmailAdapter implements RegisterUser.SendConfirmationEmail {
    private final EmailService emailService;

    public SendConfirmationEmailAdapter(EmailService emailService) {
        this.emailService = emailService;
    }

    @Override
    public Promise<ConfirmationToken> apply(User user) {
        return Promise.lift(
            EmailError::cause,
            () -> {
                ConfirmationToken token = ConfirmationToken.generate();
                emailService.send(
                    user.email().value(),
                    "Confirm your registration",
                    "Token: " + token.value()
                );
                return token;
            }
        );
    }

    public static SendConfirmationEmail sendEmail(EmailService service) {
        return new SendConfirmationEmailAdapter(service);
    }
}
```

## Step 7: Assemble Use Case

```java
@Configuration
public class RegisterUserConfig {
    @Bean
    public RegisterUser registerUser(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        EmailService emailService
    ) {
        var checkEmail = CheckEmailAvailabilityAdapter.checkEmail(userRepository);
        var hashPassword = HashPasswordAdapter.hashPassword(passwordEncoder);
        var saveUser = SaveUserAdapter.saveUser(userRepository);
        var sendEmail = SendConfirmationEmailAdapter.sendEmail(emailService);

        return RegisterUser.registerUser(
            checkEmail,
            hashPassword,
            saveUser,
            sendEmail
        );
    }
}
```

## Step 8: Create Controller

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
            .recover(this::handleError);
    }

    private Result<ResponseEntity<RegisterUser.Response>> handleError(Cause cause) {
        if (cause instanceof CompositeCause composite) {
            return Result.success(ResponseEntity.badRequest().build());
        }
        if (cause instanceof EmailAlreadyExistsError) {
            return Result.success(ResponseEntity.status(409).build());
        }
        return Result.success(ResponseEntity.internalServerError().build());
    }
}
```

## Step 9: Write Tests

```java
class RegisterUserTest {
    @Test
    void execute_succeeds_withValidInput() {
        var checkEmail = stub(valid -> Promise.success(valid));
        var hashPassword = stub(pwd -> Promise.success(new HashedPassword("hashed")));
        var saveUser = stub((email, hash) -> Promise.success(testUser()));
        var sendEmail = stub(user -> Promise.success(testToken()));

        var useCase = RegisterUser.registerUser(
            checkEmail, hashPassword, saveUser, sendEmail
        );

        useCase.execute(validRequest())
               .await()
               .onFailure(Assertions::fail)
               .onSuccess(response -> {
                   assertNotNull(response.userId());
                   assertNotNull(response.confirmationToken());
               });
    }

    @Test
    void execute_fails_whenEmailInvalid() {
        var request = new RegisterUser.Request("invalid", "ValidPass123");

        useCase.execute(request)
               .await()
               .onSuccess(Assertions::fail);
    }

    @Test
    void execute_fails_whenPasswordWeak() {
        var request = new RegisterUser.Request("test@example.com", "weak");

        useCase.execute(request)
               .await()
               .onSuccess(Assertions::fail);
    }

    @Test
    void execute_fails_whenEmailExists() {
        var checkEmail = stub(valid ->
            EmailError.AlreadyExists.INSTANCE.promise()
        );

        var useCase = RegisterUser.registerUser(checkEmail, ...);

        useCase.execute(validRequest())
               .await()
               .onSuccess(Assertions::fail);
    }
}
```

## Project Structure

```
com.example/
├── usecase/
│   └── registeruser/
│       └── RegisterUser.java (interface + all nested types)
├── domain/
│   └── shared/
│       ├── Email.java
│       ├── Password.java
│       └── UserId.java
└── adapter/
    ├── persistence/
    │   ├── CheckEmailAvailabilityAdapter.java
    │   └── SaveUserAdapter.java
    ├── security/
    │   └── HashPasswordAdapter.java
    └── messaging/
        └── SendConfirmationEmailAdapter.java
```

## Related

- [structure.md](structure.md) - Use case anatomy
- [../patterns/sequencer.md](../patterns/sequencer.md) - Composition pattern
- [../testing/patterns.md](../testing/patterns.md) - Testing strategies
