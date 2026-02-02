# Project Organization

**Purpose**: Structure projects using vertical slicing for maximum cohesion and minimum coupling.

## Vertical Slicing Philosophy

Each use case is self-contained. Shared value objects live in `domain/shared`, everything else stays in the use case package.

## Package Structure

```
com.example.app/
├── usecase/
│   ├── registeruser/          # Self-contained vertical slice
│   │   ├── RegisterUser.java  # Interface + Request + Response + ValidRequest + Steps
│   │   └── RegistrationError.java (if errors are use-case specific)
│   ├── loginuser/
│   │   └── LoginUser.java
│   └── placeorder/
│       ├── PlaceOrder.java
│       └── OrderError.java
├── domain/
│   └── shared/                # ONLY reusable value objects
│       ├── Email.java
│       ├── Password.java
│       ├── UserId.java
│       └── Money.java
└── adapter/
    ├── rest/                  # Inbound (HTTP)
    │   ├── UserController.java
    │   └── OrderController.java
    ├── persistence/           # Outbound (Database)
    │   ├── UserRepositoryAdapter.java
    │   └── OrderRepositoryAdapter.java
    └── messaging/             # Outbound (Queues/Events)
        └── EmailServiceAdapter.java
```

## Placement Rules

### Value Objects

**Used by 1 use case** → Inside use case package
```
com.example.usecase.registeruser/
└── ConfirmationToken.java
```

**Used by 2+ use cases** → `domain/shared`
```
com.example.domain.shared/
├── Email.java      # Used by RegisterUser, LoginUser, UpdateProfile
├── Password.java   # Used by RegisterUser, LoginUser, ChangePassword
└── UserId.java     # Used by most use cases
```

### Steps (Interfaces)

**Always** inside use case:
```
com.example.usecase.registeruser/
└── RegisterUser.java
    ├── interface CheckEmail { ... }
    ├── interface SaveUser { ... }
    └── interface SendEmail { ... }
```

### Error Types

**Use case specific** → Inside use case package
```
com.example.usecase.registeruser/
└── RegistrationError.java
```

**Shared errors** → `domain/shared`
```
com.example.domain.shared/
├── ValidationError.java
└── DatabaseError.java
```

### Adapters

**By direction and technology**:
- `adapter/rest/` - HTTP inbound
- `adapter/persistence/` - Database outbound
- `adapter/messaging/` - Queue/event outbound
- `adapter/external/` - Third-party API outbound

```
com.example.adapter.persistence/
├── UserRepositoryAdapter.java
├── OrderRepositoryAdapter.java
└── ProductRepositoryAdapter.java
```

## Layer Responsibilities

### Use Case Layer

- Define business operations
- Validate inputs
- Compose steps
- Return domain types

**No dependencies**: Framework-free, pure business logic.

### Domain Layer

- Value objects
- Validation rules
- Domain calculations
- Shared error types

**No dependencies**: Only Pragmatica Lite Core.

### Adapter Layer

- Framework integration
- I/O operations
- Exception lifting
- External service calls

**Dependencies allowed**: Spring, JOOQ, HTTP clients, etc.

## Example: Complete Use Case Package

```
com.example.usecase.registeruser/
└── RegisterUser.java

// Single file contains:
public interface RegisterUser extends UseCase.WithPromise<Response, Request> {
    record Request(String email, String password) {}

    record Response(UserId userId, ConfirmationToken token) {}

    record ValidRequest(Email email, Password password) {
        private ValidRequest {}
        static Result<ValidRequest> validRequest(Request raw) { ... }
    }

    interface CheckEmail { Promise<ValidRequest> apply(ValidRequest valid); }
    interface HashPassword { Promise<HashedPassword> apply(Password password); }
    interface SaveUser { Promise<User> apply(ValidRequest valid, HashedPassword hashed); }
    interface SendEmail { Promise<ConfirmationToken> apply(User user); }

    static RegisterUser registerUser(
        CheckEmail checkEmail,
        HashPassword hashPassword,
        SaveUser saveUser,
        SendEmail sendEmail
    ) {
        return request -> ValidRequest.validRequest(request)
            .async()
            .flatMap(checkEmail::apply)
            .flatMap(valid ->
                hashPassword.apply(valid.password())
                    .flatMap(hashed -> saveUser.apply(valid, hashed))
            )
            .flatMap(sendEmail::apply)
            .map(token -> new Response(user.id(), token));
    }
}
```

## Adapter Implementation

### Separate File per Step

```
com.example.adapter.persistence/
├── CheckEmailAvailabilityAdapter.java
├── SaveUserAdapter.java
└── LoadUserAdapter.java
```

### Example Adapter

```java
package com.example.adapter.persistence;

public class CheckEmailAvailabilityAdapter implements RegisterUser.CheckEmail {
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

    public static RegisterUser.CheckEmail checkEmail(UserRepository repo) {
        return new CheckEmailAvailabilityAdapter(repo);
    }
}
```

## Assembly Configuration

### Spring Boot Configuration

```java
@Configuration
public class UseCaseConfiguration {
    @Bean
    public RegisterUser registerUser(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        EmailService emailService
    ) {
        var checkEmail = CheckEmailAvailabilityAdapter.checkEmail(userRepository);
        var hashPassword = HashPasswordAdapter.hashPassword(passwordEncoder);
        var saveUser = SaveUserAdapter.saveUser(userRepository);
        var sendEmail = SendEmailAdapter.sendEmail(emailService);

        return RegisterUser.registerUser(
            checkEmail,
            hashPassword,
            saveUser,
            sendEmail
        );
    }

    @Bean
    public LoginUser loginUser(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        TokenService tokenService
    ) {
        var loadUser = LoadUserAdapter.loadUser(userRepository);
        var verifyPassword = VerifyPasswordAdapter.verifyPassword(passwordEncoder);
        var generateToken = GenerateTokenAdapter.generateToken(tokenService);

        return LoginUser.loginUser(loadUser, verifyPassword, generateToken);
    }
}
```

## Controller Layer

```
com.example.adapter.rest/
├── UserController.java
├── OrderController.java
└── dto/
    ├── RegisterUserRequest.java (optional DTO mapping)
    └── RegisterUserResponse.java
```

### Controller Example

```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    private final RegisterUser registerUser;
    private final LoginUser loginUser;

    public UserController(RegisterUser registerUser, LoginUser loginUser) {
        this.registerUser = registerUser;
        this.loginUser = loginUser;
    }

    @PostMapping("/register")
    public Promise<ResponseEntity<RegisterUser.Response>> register(
        @RequestBody RegisterUser.Request request
    ) {
        return registerUser.execute(request)
            .map(ResponseEntity::ok)
            .recover(cause -> handleError(cause));
    }

    private Result<ResponseEntity<RegisterUser.Response>> handleError(Cause cause) {
        if (cause instanceof CompositeCause composite) {
            return Result.success(ResponseEntity.badRequest().build());
        }
        return Result.success(ResponseEntity.internalServerError().build());
    }
}
```

## Migration Patterns

### Adding New Use Case

1. Create `usecase/newfeature/NewFeature.java`
2. Define Request, Response, ValidRequest
3. Define step interfaces
4. Implement factory method
5. Create adapters in `adapter/` layer
6. Wire in configuration
7. Add controller endpoint

### Extracting Shared Value Object

When a value object is needed by 2+ use cases:

1. Move from `usecase/feature/ValueObject.java`
2. To `domain/shared/ValueObject.java`
3. Update imports in use cases

### Splitting Large Use Case

If use case has 6+ steps:

1. Identify logical substeps
2. Create new intermediate use cases
3. Compose in parent use case

## Testing Structure

```
src/test/java/
├── usecase/
│   └── registeruser/
│       └── RegisterUserTest.java
├── domain/
│   └── shared/
│       ├── EmailTest.java
│       ├── PasswordTest.java
│       └── UserIdTest.java
└── adapter/
    └── persistence/
        ├── CheckEmailAvailabilityAdapterTest.java
        └── SaveUserAdapterTest.java
```

## Anti-Patterns

### ❌ Centralized Domain Logic

```
// DON'T
com.example.domain/
└── user/
    ├── UserService.java      # Centralized logic
    ├── UserValidator.java
    └── UserRepository.java
```

### ❌ Shared Business Logic

```
// DON'T
com.example.usecase.shared/
└── EmailValidator.java       # Use value objects instead
```

### ❌ Deep Package Nesting

```
// DON'T
com.example.usecase.user.registration.validation/
└── EmailValidator.java
```

### ❌ Framework in Domain

```
// DON'T
package com.example.domain.shared;

import org.springframework.stereotype.Component;

@Component  // Framework dependency!
public record Email(String value) { ... }
```

## Benefits of Vertical Slicing

1. **High cohesion** - Related code stays together
2. **Low coupling** - Use cases don't depend on each other
3. **Easy navigation** - Find everything for a feature in one place
4. **Safe refactoring** - Changes isolated to single use case
5. **Clear boundaries** - Easy to see what's shared vs specific

## Related

- [../use-cases/structure.md](../use-cases/structure.md) - Use case anatomy
- [../use-cases/complete-example.md](../use-cases/complete-example.md) - Full example with structure
- [../fundamentals/parse-dont-validate.md](../fundamentals/parse-dont-validate.md) - Value object placement
