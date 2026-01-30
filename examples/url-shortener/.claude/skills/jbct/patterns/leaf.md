# Leaf Pattern

**Purpose**: Atomic operations that cannot be decomposed further. Single responsibility, no composition.

## Definition

A Leaf performs exactly one of:
1. **Business logic computation** - Pure domain logic
2. **I/O adapter operation** - External system interaction

Leaves are the building blocks. All other patterns compose leaves.

## Business Logic Leaves

### Simple Computation

```java
private User mapToUser(ResultSet rs) throws SQLException {
    return new User(
        UserId.unsafe(UUID.fromString(rs.getString("id"))),
        Email.unsafe(rs.getString("email")),
        rs.getString("display_name")
    );
}
```

### Business Rule Validation

```java
private Result<ValidOrder> validateOrderLimits(Order order) {
    if (order.total().isGreaterThan(Money.of(10000, USD))) {
        return OrderError.ExceedsLimit.INSTANCE.result();
    }
    if (order.items().size() > MAX_ITEMS_PER_ORDER) {
        return OrderError.TooManyItems.INSTANCE.result();
    }
    return Result.success(new ValidOrder(order));
}
```

### Domain Calculation

```java
private Money calculateTax(Money subtotal, TaxRate rate) {
    return subtotal.multiply(rate.value());
}

private int calculateLoyaltyPoints(Order order) {
    return (int) (order.total().amount() / 10);
}
```

## I/O Adapter Leaves

### Database Query

```java
public Promise<Option<User>> findUser(UserId id) {
    return Promise.lift(
        DatabaseError::cause,
        () -> {
            User user = jdbcTemplate.queryForObject(
                "SELECT * FROM users WHERE id = ?",
                new Object[]{id.value()},
                this::mapToUser
            );
            return Option.option(user);
        }
    );
}
```

### Database Insert

```java
public Promise<UserId> saveUser(User user) {
    return Promise.lift(
        DatabaseError::cause,
        () -> {
            jdbcTemplate.update(
                "INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                user.id().value(),
                user.email().value(),
                user.displayName()
            );
            return user.id();
        }
    );
}
```

### HTTP Request

```java
public Promise<ExchangeRate> fetchExchangeRate(Currency from, Currency to) {
    return Promise.lift(
        ApiError::cause,
        () -> restClient.get()
            .uri("/rates?from={from}&to={to}", from.code(), to.code())
            .retrieve()
            .body(ExchangeRate.class)
    );
}
```

### Message Queue

```java
public Promise<Unit> publishEvent(OrderPlaced event) {
    return Promise.lift(
        QueueError::cause,
        () -> {
            rabbitTemplate.convertAndSend(
                EXCHANGE_NAME,
                ROUTING_KEY,
                event
            );
            return Unit.unit();
        }
    );
}
```

## Thread Safety

**Leaf operations are thread-safe through confinement.** Each leaf operation executes independently without shared mutable state.

- **Business logic leaves**: Pure functions or thread-confined mutable state
- **I/O adapter leaves**: Framework handles thread safety (connection pools, etc.)

```java
// OK - Mutable local state (thread-confined)
private User mapToUser(ResultSet rs) throws SQLException {
    var attributes = new HashMap<String, String>();  // Thread-confined
    // Build user from result set
    return new User(...);  // Immutable result
}
```

**Rule:** Leaf operations can use mutable local state internally, but must return immutable results.

## Critical Rules

### 1. Single Responsibility

Each leaf does ONE thing:

```java
// ❌ WRONG - Multiple responsibilities
public Promise<User> loadAndValidateUser(UserId id) {
    return Promise.lift(() -> {
        User user = repository.findById(id);
        if (user.isLocked()) {
            throw new UserLockedException();
        }
        return user;
    });
}

// ✅ CORRECT - Separate leaves
public Promise<User> loadUser(UserId id) {
    return Promise.lift(
        DatabaseError::cause,
        () -> repository.findById(id)
    );
}

public Result<User> validateNotLocked(User user) {
    if (user.isLocked()) {
        return UserError.Locked.INSTANCE.result();
    }
    return Result.success(user);
}
```

### 2. No Composition in Leaves

Don't call other leaves or compose operations:

```java
// ❌ WRONG - Composition in leaf
public Promise<User> loadUserWithProfile(UserId id) {
    return loadUser(id)  // Calling another operation
        .flatMap(this::loadProfile)
        .map(this::combineUserProfile);
}

// ✅ CORRECT - Leaf does only I/O
public Promise<User> loadUser(UserId id) {
    return Promise.lift(
        DatabaseError::cause,
        () -> repository.findById(id)
    );
}

// ✅ CORRECT - Composition in Sequencer pattern
public Promise<UserProfile> loadUserWithProfile(UserId id) {
    return loadUser(id)
        .flatMap(this::loadProfile)
        .map(this::combineUserProfile);
}
```

### 3. Framework Independence for Business Logic

Business logic leaves must not depend on frameworks:

```java
// ❌ WRONG - Framework dependency in business logic
import org.springframework.stereotype.Component;

@Component
public class TaxCalculator {
    public Money calculateTax(Money amount) { ... }
}

// ✅ CORRECT - Pure business logic
public class TaxCalculator {
    public Money calculateTax(Money amount, TaxRate rate) {
        return amount.multiply(rate.value());
    }
}
```

### 4. Lift Exceptions at Adapter Boundaries

Always wrap throwing code in adapters:

```java
// ✅ CORRECT
public Promise<User> loadUser(UserId id) {
    return Promise.lift(
        DatabaseError::cause,  // Exception → Cause mapping
        () -> jdbcTemplate.queryForObject(...)
    );
}
```

## Testing Leaves

### Testing Business Logic Leaves

```java
@Test
void calculateTax_applies_correctRate() {
    Money subtotal = Money.of(100, USD);
    TaxRate rate = TaxRate.of(0.15);

    Money tax = taxCalculator.calculateTax(subtotal, rate);

    assertEquals(Money.of(15, USD), tax);
}
```

### Testing Adapter Leaves with Stubs

```java
@Test
void loadUser_succeeds_whenUserExists() {
    UserId id = UserId.random();
    User expected = TestData.user(id);

    // Stub the repository
    when(repository.findById(id.value()))
        .thenReturn(expected);

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
           .onSuccess(Assertions::fail);  // Should fail
}
```

## Common Leaf Patterns

### Option Return for Lookups

```java
public Promise<Option<Theme>> findTheme(UserId id) {
    return Promise.lift(
        DatabaseError::cause,
        () -> Option.option(repository.findThemeByUserId(id.value()))
    );
}
```

### Result Return for Validation

```java
public Result<ValidOrder> checkInventory(Order order) {
    for (Item item : order.items()) {
        int available = inventory.getStock(item.productId());
        if (available < item.quantity()) {
            return OrderError.InsufficientStock.of(
                item.productId(),
                available
            ).result();
        }
    }
    return Result.success(new ValidOrder(order));
}
```

### Promise<Unit> for Side Effects

```java
public Promise<Unit> sendEmail(Email to, String subject, String body) {
    return Promise.lift(
        EmailError::cause,
        () -> {
            emailService.send(to.value(), subject, body);
            return Unit.unit();
        }
    );
}
```

### Batch Operations

```java
public Promise<List<User>> loadUsers(List<UserId> ids) {
    return Promise.lift(
        DatabaseError::cause,
        () -> jdbcTemplate.query(
            "SELECT * FROM users WHERE id IN (?)",
            new Object[]{ids.stream().map(UserId::value).toList()},
            this::mapToUser
        )
    );
}
```

## Anti-Patterns

### ❌ Mixed Responsibilities

```java
// DON'T - Business logic + I/O in same leaf
public Promise<Order> validateAndSaveOrder(Order order) {
    return Promise.lift(() -> {
        if (order.items().isEmpty()) {
            throw new InvalidOrderException();
        }
        return repository.save(order);
    });
}
```

### ❌ Composition in Leaf

```java
// DON'T - Composing other operations
public Promise<User> loadUserWithDefaults(UserId id) {
    return loadUser(id)
        .recover(e -> loadDefaultUser());
}
```

### ❌ Framework Coupling in Business Logic

```java
// DON'T - @Autowired in business logic
public class OrderValidator {
    @Autowired
    private InventoryService inventory;

    public Result<Order> validate(Order order) { ... }
}
```

### ❌ Uncaught Exceptions in Adapters

```java
// DON'T - Let exceptions escape
public Promise<User> loadUser(UserId id) {
    return Promise.promise(() ->
        // SQLException not wrapped - will cause runtime error
        Result.success(jdbcTemplate.queryForObject(...))
    );
}
```

## When to Use Leaf Pattern

- Implementing repository methods (database access)
- Creating REST client calls
- Writing pure business logic functions
- Implementing validation rules
- Calculating derived values
- Mapping between types

## Composition with Other Patterns

Leaves are composed by higher-level patterns:

- **Sequencer** - Chains multiple leaves
- **Fork-Join** - Runs leaves in parallel
- **Condition** - Routes between leaves
- **Iteration** - Maps leaf over collection
- **Aspects** - Wraps leaf with cross-cutting concerns

## Related

- [sequencer.md](sequencer.md) - Chaining leaves sequentially
- [fork-join.md](fork-join.md) - Running leaves in parallel
- [../fundamentals/four-return-kinds.md](../fundamentals/four-return-kinds.md) - Return type selection
- [../fundamentals/no-business-exceptions.md](../fundamentals/no-business-exceptions.md) - Exception lifting
