# Aether Infrastructure: State Machine

Generic state machine with type-safe states and transitions for the Aether distributed runtime.

## Overview

Provides a type-safe state machine implementation for managing entity lifecycles. Features configurable transitions with guards and actions, multiple machine instances with unique IDs, user context data per instance, and querying available events from the current state.

## Usage

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

// Create and use
var sm = StateMachine.stateMachine(definition);
sm.create("order-123", new OrderContext("order-123", "user-456")).await();
sm.send("order-123", OrderEvent.PAY).await();
sm.send("order-123", OrderEvent.SHIP).await();

// Query state
var state = sm.getState("order-123").await();     // Option<StateInfo<OrderState>>
var events = sm.getAvailableEvents("order-123").await(); // Set<OrderEvent>
var complete = sm.isComplete("order-123").await();        // boolean
```

## Dependencies

- `pragmatica-lite-core`
