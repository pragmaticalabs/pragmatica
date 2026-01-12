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
public static Promise<Slice> createSlice(Aspect<OrderService> aspect,
                                          SliceInvokerFacade invoker) {
    record orderServiceSlice(OrderService delegate) implements Slice {
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
    }

    return create(aspect, invoker).map(orderServiceSlice::new);
}
```

### D6: Proxy Method Parameter Handling

**Decision**: Proxy methods handle 0, 1, or multiple parameters differently:

| Params | Request Argument |
|--------|------------------|
| 0 | `Unit.unit()` |
| 1 | Parameter directly |
| 2+ | `new Object[]{param1, param2, ...}` |

```java
// Zero params
public Promise<String> healthCheck() {
    return invoker.invoke(ARTIFACT, "healthCheck", Unit.unit(), String.class);
}

// One param
public Promise<Stock> checkStock(StockRequest request) {
    return invoker.invoke(ARTIFACT, "checkStock", request, Stock.class);
}

// Multiple params
public Promise<Boolean> transfer(String from, String to, int amount) {
    return invoker.invoke(ARTIFACT, "transfer", new Object[]{from, to, amount}, Boolean.class);
}
```

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

    public static Promise<Slice> createSlice(Aspect<OrderProcessor> aspect,
                                              SliceInvokerFacade invoker) {
        record orderProcessorSlice(OrderProcessor delegate) implements Slice {
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
        }

        return create(aspect, invoker)
                   .map(orderProcessorSlice::new);
    }
}
```

## Slice Method Requirements

Slice API methods must:
- Return `Promise<T>` where T is the response type
- Have exactly one parameter (the request type)
- Use simple types or records as request/response

```java
// CORRECT
Promise<OrderResult> processOrder(ProcessOrderRequest request);

// WRONG - multiple parameters
Promise<OrderResult> processOrder(String orderId, int quantity);

// WRONG - no parameters
Promise<HealthStatus> healthCheck();

// WRONG - void return
void processOrder(ProcessOrderRequest request);
```

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
