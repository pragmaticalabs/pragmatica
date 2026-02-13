# Messaging Module

Type-safe message routing with sealed interface validation.

## Overview

Provides type-safe message routing based on class hierarchy. Supports mutable and immutable router implementations, compile-time validation for sealed interface coverage, and async message routing.

Message types: `Message.Local` (within a single process) and `Message.Wired` (serializable for network transport).

## Usage

### Mutable Router

```java
import org.pragmatica.messaging.MessageRouter;

var router = MessageRouter.mutable();
router.addRoute(UserCreated.class, msg -> handleUserCreated(msg));
router.addRoute(OrderPlaced.class, msg -> handleOrderPlaced(msg));

router.route(new UserCreated("user-123"));
```

### Immutable Router with Sealed Interface Validation

```java
sealed interface DomainEvent extends Message.Wired {
    record UserCreated(String id) implements DomainEvent {}
    record UserDeleted(String id) implements DomainEvent {}
}

var routes = from(DomainEvent.class)
    .route(
        route(DomainEvent.UserCreated.class, this::onUserCreated),
        route(DomainEvent.UserDeleted.class, this::onUserDeleted)
    );

if (routes.validate().isEmpty()) {
    MessageRouter router = routes.asRouter().unsafe();
}
```

### Annotations

`@MessageReceiver` - Documentation annotation for marking message handler methods.

## Dependencies

- `pragmatica-lite-core`
