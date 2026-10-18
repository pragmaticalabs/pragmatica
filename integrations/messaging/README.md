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
sealed interface DomainEvent extends Message.Local {
    record UserCreated(String id) implements DomainEvent {}
    record UserDeleted(String id) implements DomainEvent {}
}

// import static org.pragmatica.messaging.MessageRouter.Entry.SealedBuilder.from;
// import static org.pragmatica.messaging.MessageRouter.Entry.route;
var routes = from(DomainEvent.class)
    .route(
        route(DomainEvent.UserCreated.class, this::onUserCreated),
        route(DomainEvent.UserDeleted.class, this::onUserDeleted)
    );

// asRouter() validates that every permitted subtype has a route and returns Result<MessageRouter>
MessageRouter router = routes.asRouter().expect("all message types routed");

router.route(new DomainEvent.UserCreated("user-123"));

// A Message.Wired family (serializable for network transport) must also implement streamType().
```

### Annotations

`@MessageReceiver` - Documentation annotation for marking message handler methods.

## Dependencies

```xml
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>messaging</artifactId>
    <version>1.0.0-rc5</version>
</dependency>
```

Compile-scope dependencies (from `pom.xml`): `org.pragmatica-lite:core`, `org.slf4j:slf4j-api`.
