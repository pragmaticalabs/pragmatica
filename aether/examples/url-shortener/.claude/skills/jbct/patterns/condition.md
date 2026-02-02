# Condition Pattern

**Purpose**: Route execution to different paths based on a condition, expressed as values (ternary or switch).

## Definition

Condition pattern performs routing only - no transformation, no side effects, just path selection.

```java
return condition
    ? pathA.apply(input)
    : pathB.apply(input);
```

## Critical Rule: Routing Only

**The condition pattern does ROUTING, not transformation.**

```java
// ✅ CORRECT - Pure routing
return user.isPremium()
    ? processPremium.apply(request)
    : processStandard.apply(request);

// ❌ WRONG - Transformation mixed with routing
return user.isPremium()
    ? request.withDiscount(0.20)
    : request.withDiscount(0.05);

// ✅ CORRECT - Extract transformation to leaf
private Request applyDiscount(User user, Request request) {
    double discount = user.isPremium() ? 0.20 : 0.05;
    return request.withDiscount(discount);
}

return applyDiscount(user, request)
    .async()
    .flatMap(processRequest::apply);
```

## Ternary Expressions

### Simple Routing

```java
public Promise<Response> execute(Request request) {
    return ValidRequest.validRequest(request)
        .async()
        .flatMap(valid ->
            valid.userType().equals("PREMIUM")
                ? processPremium.apply(valid)
                : processStandard.apply(valid)
        );
}
```

### With Validation

```java
public Promise<Order> placeOrder(User user, Cart cart) {
    return cart.isEmpty()
        ? OrderError.EmptyCart.INSTANCE.promise()
        : validateAndProcess(user, cart);
}
```

## Switch Expressions

### Type-Based Routing

```java
public Promise<Response> handleRequest(Request request) {
    return switch (request.type()) {
        case CREATE -> createHandler.apply(request);
        case UPDATE -> updateHandler.apply(request);
        case DELETE -> deleteHandler.apply(request);
    };
}
```

### Sealed Type Pattern Matching

```java
public Promise<Response> processPayment(PaymentMethod method) {
    return switch (method) {
        case CreditCard cc -> processCreditCard.apply(cc);
        case PayPal pp -> processPayPal.apply(pp);
        case BankTransfer bt -> processBankTransfer.apply(bt);
    };
}
```

## With Result/Option

### Early Return on Validation

```java
public Result<ProcessedOrder> process(Order order) {
    return order.items().isEmpty()
        ? OrderError.EmptyCart.INSTANCE.result()
        : validateAndProcess(order);
}
```

### Optional Value Handling

```java
public Promise<Theme> getTheme(User user) {
    return findUserTheme(user.id())
        .map(maybeTheme ->
            maybeTheme.orElse(Theme.DEFAULT)
        );
}
```

## Nesting Conditions

Keep nesting minimal (max 2 levels):

```java
// ✅ ACCEPTABLE - 2 levels
return user.isActive()
    ? (user.isPremium()
        ? processPremiumActive.apply(request)
        : processStandardActive.apply(request))
    : processInactive.apply(request);

// ❌ TOO DEEP - 3+ levels
return user.isActive()
    ? (user.isPremium()
        ? (user.hasSubscription()
            ? processPremiumWithSub.apply(request)
            : processPremiumNoSub.apply(request))
        : processStandard.apply(request))
    : processInactive.apply(request);

// ✅ BETTER - Extract to method
private Promise<Response> routeByUserStatus(User user, Request request) {
    if (!user.isActive()) {
        return processInactive.apply(request);
    }
    if (!user.isPremium()) {
        return processStandard.apply(request);
    }
    return user.hasSubscription()
        ? processPremiumWithSub.apply(request)
        : processPremiumNoSub.apply(request);
}
```

## Guard Clauses

Use for early exits:

```java
public Result<Order> validateOrder(Order order) {
    if (order.items().isEmpty()) {
        return OrderError.EmptyCart.INSTANCE.result();
    }
    if (order.total().isNegative()) {
        return OrderError.NegativeTotal.INSTANCE.result();
    }
    return Result.success(order);
}
```

## With Composition

### Condition in Sequencer

```java
return ValidRequest.validRequest(request)
    .async()
    .flatMap(loadUser::apply)
    .flatMap(user ->
        user.isPremium()
            ? processPremium.apply(user)
            : processStandard.apply(user)
    )
    .map(toResponse::apply);
```

### Multiple Conditions

```java
return loadUser(id)
    .flatMap(user -> {
        if (!user.isActive()) {
            return UserError.Inactive.INSTANCE.promise();
        }
        if (user.isLocked()) {
            return UserError.Locked.INSTANCE.promise();
        }
        return processActiveUser.apply(user);
    });
```

## Testing Conditions

```java
@Test
void execute_routesToPremium_whenUserIsPremium() {
    var request = premiumRequest();
    var premiumHandler = spy(new PremiumProcessor());
    var standardHandler = spy(new StandardProcessor());

    useCase.execute(request).await();

    verify(premiumHandler).apply(any());
    verify(standardHandler, never()).apply(any());
}

@Test
void execute_routesToStandard_whenUserIsStandard() {
    var request = standardRequest();
    var premiumHandler = spy(new PremiumProcessor());
    var standardHandler = spy(new StandardProcessor());

    useCase.execute(request).await();

    verify(standardHandler).apply(any());
    verify(premiumHandler, never()).apply(any());
}
```

## Anti-Patterns

### ❌ Transformation in Condition

```java
// DON'T - Calculating in ternary
return isPremium
    ? price * 0.8  // Transformation!
    : price * 0.95;

// DO - Extract to separate function
private double applyDiscount(boolean isPremium, double price) {
    return isPremium ? price * 0.8 : price * 0.95;
}
```

### ❌ Side Effects in Branches

```java
// DON'T - Side effects in condition
return user.isActive()
    ? (log.info("Active user"), processActive(user))
    : (log.info("Inactive user"), processInactive(user));

// DO - Side effects in separate steps
log.info(user.isActive() ? "Active user" : "Inactive user");
return user.isActive()
    ? processActive.apply(user)
    : processInactive.apply(user);
```

### ❌ Complex Logic in Condition

```java
// DON'T - Complex calculation in predicate
return (user.points() > 1000 && user.purchases() > 10 && !user.hasWarnings())
    ? processVIP(user)
    : processRegular(user);

// DO - Extract predicate to method
private boolean isVIP(User user) {
    return user.points() > 1000
        && user.purchases() > 10
        && !user.hasWarnings();
}

return isVIP(user)
    ? processVIP.apply(user)
    : processRegular.apply(user);
```

## When to Use

- Routing between handlers based on type/status
- Early returns for validation failures
- Optional value handling (orElse)
- Permission-based routing
- Feature flag routing

## Related

- [sequencer.md](sequencer.md) - Linear flow after routing
- [leaf.md](leaf.md) - Individual route handlers
- [iteration.md](iteration.md) - Conditional filtering
