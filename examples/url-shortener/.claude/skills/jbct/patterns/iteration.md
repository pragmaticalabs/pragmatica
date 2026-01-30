# Iteration Pattern

**Purpose**: Process collections using functional combinators (map, filter, flatMap, reduce).

## Definition

Iteration pattern applies operations over collections without explicit loops or mutable state.

```java
List<Result<Item>> results = items.stream()
    .map(Item::validate)
    .toList();

return Result.allOf(results);
```

## Stream Operations

### map - Transform Elements

```java
List<OrderId> orderIds = orders.stream()
    .map(Order::id)
    .toList();

List<Money> totals = orders.stream()
    .map(Order::calculateTotal)
    .toList();
```

### filter - Select Elements

```java
List<Order> activeOrders = orders.stream()
    .filter(Order::isActive)
    .toList();

List<User> premiumUsers = users.stream()
    .filter(User::isPremium)
    .filter(User::isActive)
    .toList();
```

### flatMap - Flatten Nested Collections

```java
List<Item> allItems = orders.stream()
    .flatMap(order -> order.items().stream())
    .toList();
```

### reduce - Aggregate

```java
Money total = prices.stream()
    .reduce(Money.ZERO, Money::add);

int itemCount = orders.stream()
    .map(order -> order.items().size())
    .reduce(0, Integer::sum);
```

## With Result

### Validate Each Element

```java
public Result<List<Email>> validateEmails(List<String> rawEmails) {
    List<Result<Email>> results = rawEmails.stream()
        .map(Email::email)
        .toList();

    return Result.allOf(results);
}
```

### Filter Then Validate

```java
public Result<List<User>> getActiveUsers(List<User> users) {
    List<User> active = users.stream()
        .filter(User::isActive)
        .toList();

    return active.isEmpty()
        ? UserError.NoActiveUsers.INSTANCE.result()
        : Result.success(active);
}
```

### Early Termination on Validation

```java
public Result<List<ValidItem>> validateItems(List<Item> items) {
    for (Item item : items) {
        Result<ValidItem> result = ValidItem.validItem(item);
        if (result.isFailure()) {
            return result.mapError(f -> f);  // Short-circuit on first failure
        }
    }
    return Result.success(validatedItems);
}
```

## With Promise

### Parallel Processing

```java
public Promise<List<User>> loadUsers(List<UserId> ids) {
    List<Promise<User>> promises = ids.stream()
        .map(loadUser::apply)
        .toList();

    return Promise.allOf(promises)
        .map(results -> results.stream()
            .filter(Result::isSuccess)
            .map(Result::value)
            .toList()
        );
}
```

### Sequential Processing

```java
public Promise<List<Result<Order>>> processOrders(List<Order> orders) {
    Promise<List<Result<Order>>> result = Promise.success(List.of());

    for (Order order : orders) {
        result = result.flatMap(processed ->
            processOrder.apply(order)
                .map(r -> {
                    List<Result<Order>> updated = new ArrayList<>(processed);
                    updated.add(r);
                    return updated;
                })
        );
    }

    return result;
}
```

## Common Patterns

### Group By

```java
Map<Currency, List<Money>> byCurrency = amounts.stream()
    .collect(Collectors.groupingBy(Money::currency));
```

### Partition

```java
Map<Boolean, List<Order>> partitioned = orders.stream()
    .collect(Collectors.partitioningBy(Order::isPaid));

List<Order> paid = partitioned.get(true);
List<Order> unpaid = partitioned.get(false);
```

### Find First

```java
Option<User> admin = users.stream()
    .filter(User::isAdmin)
    .findFirst()
    .map(Option::some)
    .orElse(Option.none());
```

### Exists/All

```java
boolean hasAdmin = users.stream()
    .anyMatch(User::isAdmin);

boolean allActive = users.stream()
    .allMatch(User::isActive);
```

## Nested Collections

### Flatten and Process

```java
List<Item> allItems = orders.stream()
    .flatMap(order -> order.items().stream())
    .filter(item -> item.quantity() > 0)
    .distinct()
    .toList();
```

### Validate Nested

```java
public Result<List<ValidOrder>> validateOrders(List<Order> orders) {
    List<Result<ValidOrder>> results = orders.stream()
        .map(order -> {
            Result<List<ValidItem>> itemsResult =
                Result.allOf(
                    order.items().stream()
                        .map(ValidItem::validItem)
                        .toList()
                );
            return itemsResult.map(items -> new ValidOrder(order, items));
        })
        .toList();

    return Result.allOf(results);
}
```

## Collectors

### ToList (Most Common)

```java
List<String> names = users.stream()
    .map(User::displayName)
    .toList();
```

### Joining Strings

```java
String csv = users.stream()
    .map(User::email)
    .map(Email::value)
    .collect(Collectors.joining(", "));
```

### ToMap

```java
Map<UserId, User> userMap = users.stream()
    .collect(Collectors.toMap(User::id, Function.identity()));
```

## Thread Safety

**Sequential iteration is thread-safe through single-threaded execution.** Stream operations execute on the calling thread unless explicitly parallelized.

```java
// OK - Mutable accumulator in sequential loop (thread-confined)
var totals = new ArrayList<Money>();  // Thread-confined
for (Order order : orders) {
    totals.add(order.total());
}
return List.copyOf(totals);  // Immutable result
```

**Rule:** Sequential iteration allows mutable accumulators (thread-confined), but prefer functional combinators (map, filter, reduce) for clarity.

## Anti-Patterns

### ❌ Mutable State in Stream

```java
// DON'T - Mutating external variable
List<User> result = new ArrayList<>();
users.stream().forEach(user -> {
    if (user.isActive()) {
        result.add(user);  // Side effect!
    }
});

// DO - Use filter and collect
List<User> result = users.stream()
    .filter(User::isActive)
    .toList();
```

### ❌ Imperative Loop for Simple Transformation

```java
// DON'T - Imperative loop
List<Email> emails = new ArrayList<>();
for (User user : users) {
    emails.add(user.email());
}

// DO - Use map
List<Email> emails = users.stream()
    .map(User::email)
    .toList();
```

### ❌ Multiple Passes

```java
// DON'T - Multiple passes
List<User> active = users.stream().filter(User::isActive).toList();
List<Email> emails = active.stream().map(User::email).toList();

// DO - Single pass
List<Email> emails = users.stream()
    .filter(User::isActive)
    .map(User::email)
    .toList();
```

## When Explicit Loops Are OK

### Complex State Accumulation

```java
// OK - Complex state that doesn't fit reduce
Map<Category, Stats> stats = new HashMap<>();
for (Product product : products) {
    stats.computeIfAbsent(product.category(), k -> new Stats())
         .update(product);
}
```

### Early Termination with Complex Logic

```java
// OK - Early exit with complex condition
for (Order order : orders) {
    if (shouldStop(order, context)) {
        break;
    }
    process(order);
}
```

## Testing

```java
@Test
void validateEmails_succeedsForAllValid() {
    List<String> raw = List.of(
        "user1@example.com",
        "user2@example.com"
    );

    validator.validateEmails(raw)
             .onFailure(Assertions::fail)
             .onSuccess(emails -> {
                 assertEquals(2, emails.size());
             });
}

@Test
void validateEmails_failsIfAnyInvalid() {
    List<String> raw = List.of(
        "valid@example.com",
        "invalid"
    );

    validator.validateEmails(raw)
             .onSuccess(Assertions::fail);
}
```

## Related

- [leaf.md](leaf.md) - Operations applied to each element
- [sequencer.md](sequencer.md) - Sequential processing
- [fork-join.md](fork-join.md) - Parallel collection processing
