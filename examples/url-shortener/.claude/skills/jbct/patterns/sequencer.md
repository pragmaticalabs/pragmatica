# Sequencer Pattern

**Purpose**: Chain 2-5 dependent steps where each step's output feeds the next step's input.

## Definition

The Sequencer pattern is the most common use case pattern. It chains operations using `flatMap` where each step depends on the previous result.

```java
return step1(input)
    .flatMap(step2::apply)
    .flatMap(step3::apply)
    .map(step4::apply);
```

## Structure

```java
public interface RegisterUser extends UseCase.WithPromise<Response, Request> {
    record Request(String email, String password) {}
    record Response(UserId userId, String confirmationToken) {}

    // Steps as single-method interfaces
    interface CheckEmail { Promise<ValidRequest> apply(ValidRequest valid); }
    interface HashPassword { Promise<HashedPassword> apply(ValidRequest valid); }
    interface SaveUser { Promise<User> apply(HashedPassword hashed); }
    interface SendConfirmation { Promise<String> apply(User user); }

    // Factory returns lambda with sequencer composition
    static RegisterUser registerUser(
        CheckEmail checkEmail,
        HashPassword hashPassword,
        SaveUser saveUser,
        SendConfirmation sendConfirmation
    ) {
        return request -> ValidRequest.validRequest(request)
            .async()
            .flatMap(checkEmail::apply)
            .flatMap(hashPassword::apply)
            .flatMap(saveUser::apply)
            .flatMap(sendConfirmation::apply)
            .map(token -> new Response(user.id(), token));
    }
}
```

## Thread Safety

**Sequencer is thread-safe through sequential execution.** Each step completes before the next begins. Mutable local state within a step is safe (thread-confined).

```java
// OK - Mutable accumulator within single step
private List<Item> processItems(List<Item> items) {
    var processed = new ArrayList<Item>();  // Thread-confined
    for (var item : items) {
        processed.add(transform(item));
    }
    return List.copyOf(processed);  // Return immutable
}
```

**Rule:** Mutable state is safe within a single sequential step, but inputs/outputs between steps must be immutable.

## Critical Rules

### 1. Use flatMap for Dependent Steps

```java
// ✅ CORRECT - Each step depends on previous
return loadUser(id)
    .flatMap(validateUser::apply)   // Needs User
    .flatMap(enrichProfile::apply)  // Needs ValidUser
    .map(toResponse::apply);        // Needs EnrichedProfile
```

### 2. Chain Length: 2-5 Steps

```java
// ✅ GOOD - 3 steps
return validate(request)
    .async()
    .flatMap(processPayment::apply)
    .map(toResponse::apply);

// ❌ TOO LONG - 8 steps, extract substeps
return validate(request)
    .async()
    .flatMap(step1::apply)
    .flatMap(step2::apply)
    .flatMap(step3::apply)
    .flatMap(step4::apply)
    .flatMap(step5::apply)
    .flatMap(step6::apply)
    .flatMap(step7::apply)
    .map(step8::apply);

// ✅ BETTER - Group related steps
return validate(request)
    .async()
    .flatMap(prepareOrder::apply)   // Groups step1-3
    .flatMap(processPayment::apply) // Groups step4-5
    .flatMap(finalizeOrder::apply); // Groups step6-8
```

### 3. No Branching Logic

Sequencer is purely linear:

```java
// ❌ WRONG - Branching in sequencer
return validate(request)
    .async()
    .flatMap(valid -> {
        if (valid.isPremium()) {
            return processPremium.apply(valid);
        } else {
            return processBasic.apply(valid);
        }
    });

// ✅ CORRECT - Extract to Condition pattern
return validate(request)
    .async()
    .flatMap(routeByPremiumStatus::apply)  // Leaf that does routing
    .flatMap(process::apply);
```

## Sync to Async Conversion

### Using .async()

```java
// Lift Result<T> to Promise<T>
return ValidRequest.validRequest(request)  // Returns Result<ValidRequest>
    .async()                                // Converts to Promise<ValidRequest>
    .flatMap(loadUser::apply)               // Promise operations
    .flatMap(processUser::apply);
```

## Error Handling

### Short-Circuit on First Failure

```java
return step1(input)      // If fails, skip remaining steps
    .flatMap(step2::apply)
    .flatMap(step3::apply);
```

### Recovery

```java
return loadUser(id)
    .flatMap(validateUser::apply)
    .recover(cause -> {
        if (cause instanceof UserError.NotFound) {
            return createDefaultUser();
        }
        return cause.promise();
    })
    .map(toResponse::apply);
```

## Testing Sequencers

```java
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
void execute_fails_whenEmailExists() {
    var checkEmail = failureStub(EmailError.AlreadyExists.INSTANCE);
    var useCase = RegisterUser.registerUser(checkEmail, successStub());

    useCase.execute(validRequest())
           .await()
           .onSuccess(Assertions::fail);
}
```

## Complete Example

```java
public interface GetUserProfile extends UseCase.WithPromise<Response, Request> {
    record Request(String userId) {}
    record Response(String userId, String email, String displayName, List<String> orders) {}

    interface FetchUser { Promise<User> apply(UserId id); }
    interface FetchOrders { Promise<List<Order>> apply(UserId id); }

    static GetUserProfile getUserProfile(FetchUser fetchUser, FetchOrders fetchOrders) {
        return request -> UserId.userId(request.userId())
            .async()
            .flatMap(fetchUser::apply)
            .flatMap(user ->
                fetchOrders.apply(user.id())
                    .map(orders -> new Response(
                        user.id().value().toString(),
                        user.email().value(),
                        user.displayName(),
                        orders.stream().map(Order::id).toList()
                    ))
            );
    }
}
```

## Related

- [leaf.md](leaf.md) - Building blocks for steps
- [fork-join.md](fork-join.md) - Parallel independent steps
- [condition.md](condition.md) - Branching between paths
- [../use-cases/structure.md](../use-cases/structure.md) - Use case anatomy
