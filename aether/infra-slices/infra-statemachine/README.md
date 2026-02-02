# Aether Infrastructure: State Machine

Generic state machine with type-safe states and transitions for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-statemachine</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides a type-safe state machine implementation for managing entity lifecycles with defined states, events, and transitions.

### Key Features

- Type-safe states and events (typically enums)
- Configurable transitions with guards and actions
- Multiple machine instances with unique IDs
- User context data per instance
- Query available events from current state

### Quick Start

```java
// Define states and events
enum OrderState { PENDING, PAID, SHIPPED, DELIVERED, CANCELLED }
enum OrderEvent { PAY, SHIP, DELIVER, CANCEL }

// Define transitions
var definition = StateMachineDefinition.<OrderState, OrderEvent, OrderContext>builder()
    .initialState(OrderState.PENDING)
    .transition(PENDING, PAY, PAID)
    .transition(PAID, SHIP, SHIPPED)
    .transition(SHIPPED, DELIVER, DELIVERED)
    .transition(PENDING, CANCEL, CANCELLED)
    .transition(PAID, CANCEL, CANCELLED)
    .finalStates(DELIVERED, CANCELLED)
    .build();

// Create state machine
var sm = StateMachine.stateMachine(definition);

// Create instance
var context = new OrderContext("order-123", "user-456");
sm.create("order-123", context).await();

// Send events
sm.send("order-123", OrderEvent.PAY).await();
sm.send("order-123", OrderEvent.SHIP).await();

// Query state
var state = sm.getState("order-123").await(); // Option<StateInfo<OrderState>>
var events = sm.getAvailableEvents("order-123").await(); // Set<OrderEvent>
var complete = sm.isComplete("order-123").await(); // boolean
```

### API Summary

| Category | Methods |
|----------|---------|
| Lifecycle | `create`, `delete`, `reset` |
| Events | `send`, `getAvailableEvents` |
| Query | `getState`, `exists`, `isComplete` |
| Admin | `listInstances`, `getDefinition` |

### Data Types

```java
record StateInfo<S>(S state, Instant enteredAt, int transitionCount) {}

record Transition<S, E>(S from, E event, S to,
                        Predicate<TransitionContext<S, E, C>> guard,
                        Consumer<TransitionContext<S, E, C>> action) {}
```

## License

Apache License 2.0
