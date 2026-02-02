# Slice Factory Generation Design

## Overview

This document describes the automatic generation of slice factory classes by the `slice-processor` annotation processor. The factories serve as adapters between typed API and binary transport, handling dependency wiring and Aether runtime integration.

## Generated Artifacts

From a `@Slice`-annotated interface, the processor generates:

| Artifact | Location | Purpose |
|----------|----------|---------|
| API Interface | `{package}.api.{SliceName}` | Public interface for consumers |
| Factory Class | `{package}.{SliceName}Factory` | Creates slice with dependency wiring |
| Manifest | `META-INF/slice-api.properties` | Maps artifact to interface |

## Design Decisions

### D1: Factory Return Type

**Decision**: Factory methods return `Promise<SliceType>`, never `Result<Promise<...>>` or `Promise<Result<...>>`.

**Rationale**: Promise is the asynchronous version of Result by design. Wrapping one in the other is redundant.

```java
// CORRECT
public static Promise<MySlice> create(Aspect<MySlice> aspect, SliceInvokerFacade invoker)

// WRONG - never wrap Result in Promise
public static Promise<Result<MySlice>> create(...)
```

### D2: Aspect Parameter

**Decision**: First parameter is always `Aspect<SliceType> aspect`. Use `Aspect.identity()` when no decoration needed.

**Rationale**:
- Eliminates conditional logic in factory
- Single vtable indirect call (JVM optimizes this well)
- Identity aspect costs nothing at runtime

```java
// No aspect needed
var slice = SliceFactory.create(Aspect.identity(), invoker);

// With logging aspect
var slice = SliceFactory.create(LoggingAspect.create(), invoker);
```

### D3: Dependency Classification

**Decision**: Dependencies are classified as internal or external based on package:

| Type | Definition | Handling |
|------|------------|----------|
| Internal | Same base package or subpackage | Call factory method directly |
| External | Different base package | Generate local proxy record |

**Example**:
```
Slice package: org.example.order

org.example.order.validation.OrderValidator  → Internal (subpackage)
org.example.inventory.InventoryService       → External (different base)
```

### D4: Local Proxy Records

**Decision**: External dependency proxies are generated as local records INSIDE the `create()` method, not as separate class files.

**Rationale**:
- No separate proxy class files to manage
- Encapsulation - proxy is implementation detail
- Follows JBCT convention of preferring local records over classes

```java
public static Promise<OrderService> create(Aspect<OrderService> aspect,
                                            SliceInvokerFacade invoker) {
    // Local proxy record - not a separate file
    record inventoryService(SliceInvokerFacade invoker) implements InventoryService {
        private static final String ARTIFACT = "org.example:inventory:1.0.0";

        @Override
        public Promise<Stock> checkStock(StockRequest request) {
            return invoker.invoke(ARTIFACT, "checkStock", request, Stock.class);
        }
    }

    var inventory = new inventoryService(invoker);
    var instance = OrderService.orderService(inventory);
    return Promise.success(aspect.apply(instance));
}
```

### D5: Slice Adapter for Aether Runtime

**Decision**: Generate `createSlice()` method that returns `Slice` interface for Aether runtime registration.

**Rationale**: Aether runtime needs a generic `Slice` interface to invoke methods by name with type tokens.

```java
public static Promise<Slice> orderServiceSlice(Aspect<OrderService> aspect,
                                               SliceInvokerFacade invoker) {
    record orderServiceSlice(OrderService delegate) implements Slice, OrderService {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of(
                new SliceMethod<>(
                    MethodName.methodName("placeOrder").unwrap(),
                    delegate::placeOrder,
                    new TypeToken<OrderResult>() {},
                    new TypeToken<PlaceOrderRequest>() {}
                )
            );
        }

        @Override
        public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
            return delegate.placeOrder(request);
        }
    }

    return orderService(aspect, invoker).map(orderServiceSlice::new);
}
```

The slice wrapper record implements both `Slice` (for Aether runtime) and the slice interface (e.g., `OrderService`) to enable type-safe routing integration.

### D6: Proxy Method Parameter Handling

**Decision**: All slice methods require at least one parameter. Multi-parameter methods use synthetic request records (see D8).

| Params | Request Argument |
|--------|------------------|
| 0 | **Not supported** - methods must have at least one parameter |
| 1 (record) | Parameter directly |
| 1 (primitive) | Synthetic record wrapping the value |
| 2+ | Synthetic record with all parameters |

```java
// Single record param - uses directly
public Promise<Stock> checkStock(StockRequest request) {
    return invoker.invoke(ARTIFACT, "checkStock", request, Stock.class);
}

// Single primitive - synthetic record
public Promise<Stock> getStock(String sku) {
    // Uses GetStock_0_Request(String sku)
    return invoker.invoke(ARTIFACT, "getStock", new GetStock_0_Request(sku), Stock.class);
}

// Multiple params - synthetic record
public Promise<Boolean> transfer(String from, String to, int amount) {
    // Uses Transfer_0_Request(String from, String to, int amount)
    return invoker.invoke(ARTIFACT, "transfer", new Transfer_0_Request(from, to, amount), Boolean.class);
}
```

See **D8: Synthetic Request Records** for naming conventions and deterministic ordering.

### D7: Artifact Resolution

**Decision**: External dependency artifacts are resolved from `slice-deps.properties` generated by `jbct:collect-slice-deps`.

**Format**:
```properties
# interface.qualified.name=groupId:artifactId:version
org.example.inventory.InventoryService=org.example:inventory-service:1.0.0
```

**Unresolved Dependencies**: When version cannot be resolved, artifact string shows `UNRESOLVED`:
```java
private static final String ARTIFACT = "org.example:inventory:UNRESOLVED";
```

## Generated Code Structure

### Complete Factory Example

**Input**: `@Slice` interface with mixed dependencies

```java
package org.example.order;

@Slice
public interface OrderProcessor {
    Promise<OrderResult> processOrder(ProcessOrderRequest request);

    static OrderProcessor orderProcessor(OrderValidator validator,    // internal
                                          PricingEngine pricing,      // internal
                                          InventoryService inventory) // external
    {
        return new OrderProcessorImpl(validator, pricing, inventory);
    }
}
```

**Output**: Generated factory

```java
package org.example.order;

import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import java.util.List;
import org.example.order.core.OrderValidator;
import org.example.order.core.PricingEngine;
import inventory.InventoryService;

/**
 * Factory for OrderProcessor slice.
 * Generated by slice-processor - do not edit manually.
 */
public final class OrderProcessorFactory {
    private OrderProcessorFactory() {}

    public static Promise<OrderProcessor> create(Aspect<OrderProcessor> aspect,
                                                  SliceInvokerFacade invoker) {
        // Local proxy for external dependency
        record inventoryService(SliceInvokerFacade invoker) implements InventoryService {
            private static final String ARTIFACT = "org.example:inventory-service:1.0.0";

            @Override
            public Promise<Stock> getStock(String sku) {
                return invoker.invoke(ARTIFACT, "getStock", sku, Stock.class);
            }
        }

        // Internal dependencies - call their factories directly
        var validator = OrderValidator.orderValidator();
        var pricing = PricingEngine.pricingEngine();

        // External dependency - instantiate proxy
        var inventory = new inventoryService(invoker);

        // Create slice via developer's factory
        var instance = OrderProcessor.orderProcessor(validator, pricing, inventory);
        return Promise.success(aspect.apply(instance));
    }

    public static Promise<Slice> orderProcessorSlice(Aspect<OrderProcessor> aspect,
                                                      SliceInvokerFacade invoker) {
        record orderProcessorSlice(OrderProcessor delegate) implements Slice, OrderProcessor {
            @Override
            public List<SliceMethod<?, ?>> methods() {
                return List.of(
                    new SliceMethod<>(
                        MethodName.methodName("processOrder").unwrap(),
                        delegate::processOrder,
                        new TypeToken<OrderResult>() {},
                        new TypeToken<ProcessOrderRequest>() {}
                    )
                );
            }

            @Override
            public Promise<OrderResult> processOrder(ProcessOrderRequest request) {
                return delegate.processOrder(request);
            }
        }

        return orderProcessor(aspect, invoker)
                   .map(orderProcessorSlice::new);
    }
}
```

## Slice Method Requirements

Slice API methods must:
- Return `Promise<T>` where T is the response type
- Have at least one parameter
- Use simple types or records as request/response

```java
// CORRECT - single record parameter
Promise<OrderResult> processOrder(ProcessOrderRequest request);

// CORRECT - multiple parameters (synthetic record generated)
Promise<OrderResult> processOrder(String orderId, int quantity);

// CORRECT - single primitive
Promise<Stock> getStock(String sku);

// WRONG - no parameters
Promise<HealthStatus> healthCheck();

// WRONG - void return
void processOrder(ProcessOrderRequest request);
```

## Multi-Parameter Method Support

### D8: Synthetic Request Records

**Decision**: For slice methods with multiple parameters or single primitive parameters, the processor generates synthetic request records inside the factory interface.

**Naming Convention**: `{MethodName}_{N}_Request` where N is a sequence number.

**Sequence Number Rules**:
1. Sort all method signatures as strings alphabetically
2. Assign sequence numbers starting from 0
3. Always include `_0_` suffix even for non-overloaded methods (consistency)

**Rationale**:
- Provides deterministic naming across caller and callee
- Adding method overloads is a breaking change (minor version bump)
- Same generated record can be used on both sides of the transport

### Examples

**Single Primitive Parameter**:
```java
// Slice interface
Promise<Stock> getStock(String sku);

// Generated synthetic record
public record GetStock_0_Request(String sku) {}

// Generated SliceMethod uses the synthetic record
new SliceMethod<>(
    MethodName.methodName("getStock").unwrap(),
    request -> delegate.getStock(request.sku()),
    new TypeToken<Stock>() {},
    new TypeToken<GetStock_0_Request>() {}
)
```

**Multiple Parameters**:
```java
// Slice interface
Promise<TransferResult> transfer(String from, String to, BigDecimal amount);

// Generated synthetic record
public record Transfer_0_Request(String from, String to, BigDecimal amount) {}

// Generated SliceMethod
new SliceMethod<>(
    MethodName.methodName("transfer").unwrap(),
    request -> delegate.transfer(request.from(), request.to(), request.amount()),
    new TypeToken<TransferResult>() {},
    new TypeToken<Transfer_0_Request>() {}
)
```

**Single Record Parameter (no synthetic)**:
```java
// Slice interface with existing record
Promise<OrderResult> processOrder(ProcessOrderRequest request);

// Uses existing record directly - no synthetic generated
new SliceMethod<>(
    MethodName.methodName("processOrder").unwrap(),
    delegate::processOrder,
    new TypeToken<OrderResult>() {},
    new TypeToken<ProcessOrderRequest>() {}
)
```

**Method Overloads**:
```java
// Slice interface with overloaded methods
Promise<User> getUser(Long id);
Promise<User> getUser(String email);

// Signatures sorted: "getUser(Long)" < "getUser(String)"
// Generated synthetic records
public record GetUser_0_Request(Long id) {}      // for getUser(Long)
public record GetUser_1_Request(String email) {} // for getUser(String)
```

### External Dependency Proxies

For external dependencies with multi-parameter methods, the generator creates stub interfaces with synthetic records:

```java
// External dependency interface (in another slice)
public interface PaymentService {
    Promise<PaymentResult> processPayment(String orderId, BigDecimal amount);
}

// Generated proxy record inside factory
record paymentService(SliceInvokerFacade invoker) implements PaymentService {
    private static final String ARTIFACT = "org.example:payment:1.0.0";

    // Synthetic record for the proxy
    public record ProcessPayment_0_Request(String orderId, BigDecimal amount) {}

    @Override
    public Promise<PaymentResult> processPayment(String orderId, BigDecimal amount) {
        return invoker.invoke(ARTIFACT, "processPayment",
                              new ProcessPayment_0_Request(orderId, amount),
                              PaymentResult.class);
    }
}
```

### Breaking Change Warning

Adding method overloads changes sequence numbers and is a **breaking change**:

```java
// Version 1.0.0
Promise<User> getUser(Long id);  // GetUser_0_Request

// Version 1.1.0 - BREAKING CHANGE
Promise<User> getUser(Long id);      // Still GetUser_0_Request
Promise<User> getUser(String email); // NEW: GetUser_1_Request
// Old clients sending GetUser_0_Request for getUser(String) will fail
```

**Migration**: Increment minor version when adding overloads; document in changelog.

## File Structure

```
slice-processor/
├── src/main/java/org/pragmatica/jbct/slice/
│   ├── SliceProcessor.java              # Annotation processor entry point
│   ├── model/
│   │   ├── SliceModel.java              # Slice metadata
│   │   ├── MethodModel.java             # Method info (name, types)
│   │   └── DependencyModel.java         # Dependency with classification
│   └── generator/
│       ├── ApiInterfaceGenerator.java   # Generates api/{Slice}.java
│       ├── FactoryClassGenerator.java   # Generates {Slice}Factory.java
│       ├── ManifestGenerator.java       # Generates META-INF/slice-api.properties
│       └── DependencyVersionResolver.java
└── docs/
    └── SLICE-FACTORY-GENERATION.md      # This document
```

## Aether Runtime Types

The following types are provided by `slice-api` module:

### Aspect<T>
```java
@FunctionalInterface
public interface Aspect<T> {
    T apply(T instance);

    static <T> Aspect<T> identity() {
        return instance -> instance;
    }
}
```

### Slice
```java
public interface Slice {
    List<SliceMethod<?, ?>> methods();
}
```

### SliceMethod
```java
public record SliceMethod<I, O>(
    MethodName name,
    Function<I, Promise<O>> handler,
    TypeToken<O> responseType,
    TypeToken<I> requestType
) {}
```

### SliceInvokerFacade
```java
public interface SliceInvokerFacade {
    <T> Promise<T> invoke(String artifact, String method, Object request, Class<T> responseType);
}
```

## Test Coverage

| Test | Coverage |
|------|----------|
| `should_fail_on_non_interface` | @Slice on class rejected |
| `should_fail_on_missing_factory_method` | Missing factory detected |
| `should_process_simple_slice_without_dependencies` | No-dependency slice |
| `should_generate_proxy_for_external_dependency` | External proxy record |
| `should_call_internal_dependency_factory_directly` | Internal factory call |
| `should_handle_mixed_internal_and_external_dependencies` | Both patterns |
| `should_generate_proxy_methods_with_zero_params` | Unit.unit() for no-arg |
| `should_generate_proxy_methods_with_multiple_params` | Object[] for multi-arg |
| `should_handle_deeply_nested_slice_dependencies` | Complex dependency graph |
| `should_generate_createSlice_method_with_all_business_methods` | Slice adapter |
| `should_generate_correct_type_tokens_for_slice_methods` | Custom DTO types |

## Revision History

| Date | Author | Changes |
|------|--------|---------|
| 2026-01-11 | Claude | Initial design |
| 2026-01-11 | Claude | Implemented: local proxy records, createSlice(), internal/external deps |
| 2026-01-11 | Claude | Added comprehensive test coverage (11 tests) |
| 2026-01-11 | Claude | Fixed: import internal deps from subpackages |
| 2026-01-14 | Claude | Added D8: Multi-parameter method support with synthetic records |
