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
public static Promise<MySlice> mySlice(Aspect<MySlice> aspect, SliceCreationContext ctx)

// WRONG - never wrap Result in Promise
public static Promise<Result<MySlice>> mySlice(...)
```

### D2: Aspect Parameter

**Decision**: First parameter is always `Aspect<SliceType> aspect`. Use `Aspect.identity()` when no decoration needed.

**Rationale**:
- Eliminates conditional logic in factory
- Single vtable indirect call (JVM optimizes this well)
- Identity aspect costs nothing at runtime

```java
// No aspect needed
var slice = SliceFactory.sliceName(Aspect.identity(), ctx);

// With logging aspect
var slice = SliceFactory.sliceName(LoggingAspect.create(), ctx);
```

### D3: Dependency Classification

**Decision**: Dependencies are classified into three types based on their annotations and structure:

| Type | Definition | Handling |
|------|------------|----------|
| Resource | Parameter has `@ResourceQualifier` meta-annotation | `ctx.resources().provide(Type.class, "config")` |
| Slice | External `@Slice` interface or interface without factory method | Proxy record with `MethodHandle` fields via `ctx.invoker()` |
| Plain interface | Non-`@Slice` interface with static factory method | Call factory directly; if factory has `@ResourceQualifier` params, provision transitively |

**Example**:
```java
static OrderProcessor orderProcessor(
    @PrimaryDb DatabaseConnector db,        // Resource dependency
    InventoryService inventory,              // Slice dependency (external @Slice)
    OrderValidator validator                 // Plain interface dependency
) { ... }
```

### D4: Local Proxy Records

**Decision**: Slice dependency proxies are generated as local records INSIDE the factory method, not as separate class files. Proxy records use `MethodHandle<R, I>` fields instead of `SliceInvokerFacade`.

**Rationale**:
- No separate proxy class files to manage
- Encapsulation - proxy is implementation detail
- Follows JBCT convention of preferring local records over classes
- `MethodHandle` fields provide type-safe invocation

```java
public static Promise<OrderService> orderService(Aspect<OrderService> aspect,
                                                   SliceCreationContext ctx) {
    // Local proxy record with MethodHandle fields
    record inventoryService(MethodHandle<Integer, String> checkStockHandle) implements InventoryService {
        @Override
        public Promise<Integer> checkStock(String productId) {
            return checkStockHandle.invoke(productId);
        }
    }

    return Promise.all(
            ctx.invoker().methodHandle("org.example:inventory:1.0.0", "checkStock",
                                        new TypeToken<String>() {},
                                        new TypeToken<Integer>() {}).async())
        .map((inventory_checkStock) -> {
            var inventory = new inventoryService(inventory_checkStock);
            var instance = OrderService.orderService(inventory);
            return aspect.apply(instance);
        });
}
```

### D5: Slice Adapter for Aether Runtime

**Decision**: Generate `createSlice()` method that returns `Slice` interface for Aether runtime registration.

**Rationale**: Aether runtime needs a generic `Slice` interface to invoke methods by name with type tokens.

```java
public static Promise<Slice> orderServiceSlice(Aspect<OrderService> aspect,
                                               SliceCreationContext ctx) {
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

    return orderService(aspect, ctx).map(orderServiceSlice::new);
}
```

The slice wrapper record implements both `Slice` (for Aether runtime) and the slice interface (e.g., `OrderService`) to enable type-safe routing integration.

### D6: Proxy Method Parameter Handling

**Decision**: Slice methods support any number of parameters (0, 1, or more). Multi-parameter methods and single primitive parameters use synthetic request records (see D8). Zero-parameter methods accept `Unit` at the transport layer; the handler ignores it.

| Params | Request Argument |
|--------|------------------|
| 0 | `Unit` at transport layer; handler ignores it |
| 1 (record) | Parameter directly |
| 1 (primitive) | Synthetic record wrapping the value |
| 2+ | Synthetic record with all parameters |

```java
// Zero params - SliceMethod accepts Unit, handler ignores it
record healthService(MethodHandle<HealthStatus, Unit> healthCheckHandle) implements HealthService {
    @Override
    public Promise<HealthStatus> healthCheck() {
        return healthCheckHandle.invoke(Unit.unit());
    }
}

// Single record param - MethodHandle invoked directly
record stockService(MethodHandle<Stock, StockRequest> checkStockHandle) implements StockService {
    @Override
    public Promise<Stock> checkStock(StockRequest request) {
        return checkStockHandle.invoke(request);
    }
}

// Single primitive - MethodHandle with synthetic record
record stockService(MethodHandle<Stock, GetStockRequest> getStockHandle) implements StockService {
    public record GetStockRequest(String sku) {}

    @Override
    public Promise<Stock> getStock(String sku) {
        return getStockHandle.invoke(new GetStockRequest(sku));
    }
}

// Multiple params - MethodHandle with synthetic record
record transferService(MethodHandle<Boolean, TransferRequest> transferHandle) implements TransferService {
    public record TransferRequest(String from, String to, int amount) {}

    @Override
    public Promise<Boolean> transfer(String from, String to, int amount) {
        return transferHandle.invoke(new TransferRequest(from, to, amount));
    }
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

    static OrderProcessor orderProcessor(OrderValidator validator,    // plain interface
                                          PricingEngine pricing,      // plain interface
                                          InventoryService inventory) // slice dependency
    {
        return new OrderProcessorImpl(validator, pricing, inventory);
    }
}
```

**Output**: Generated factory

```java
package org.example.order;

import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceCreationContext;
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

    public static Promise<OrderProcessor> orderProcessor(Aspect<OrderProcessor> aspect,
                                                          SliceCreationContext ctx) {
        // Local proxy for slice dependency with MethodHandle fields
        record inventoryService(MethodHandle<Stock, StockRequest> checkStockHandle)
                implements InventoryService {
            @Override
            public Promise<Stock> checkStock(StockRequest request) {
                return checkStockHandle.invoke(request);
            }
        }

        return Promise.all(
                ctx.invoker().methodHandle("org.example:inventory-service:1.0.0", "checkStock",
                                            new TypeToken<StockRequest>() {},
                                            new TypeToken<Stock>() {}).async())
            .map((inventory_checkStock) -> {
                // Plain interface deps - call factory directly
                var validator = OrderValidator.orderValidator();
                var pricing = PricingEngine.pricingEngine();
                // Slice deps - instantiate proxy
                var inventory = new inventoryService(inventory_checkStock);
                return aspect.apply(
                    OrderProcessor.orderProcessor(validator, pricing, inventory));
            });
    }

    public static Promise<Slice> orderProcessorSlice(Aspect<OrderProcessor> aspect,
                                                      SliceCreationContext ctx) {
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

        return orderProcessor(aspect, ctx)
                   .map(orderProcessorSlice::new);
    }
}
```

## Slice Method Requirements

Slice API methods must:
- Return `Promise<T>` where T is the response type
- Have any number of parameters (0, 1, or more)
- Use simple types or records as request/response
- Not be overloaded (overloads are rejected at compile time)

```java
// CORRECT - zero parameters
Promise<HealthStatus> healthCheck();

// CORRECT - single record parameter
Promise<OrderResult> processOrder(ProcessOrderRequest request);

// CORRECT - multiple parameters (synthetic record generated)
Promise<OrderResult> processOrder(String orderId, int quantity);

// CORRECT - single primitive
Promise<Stock> getStock(String sku);

// WRONG - void return
void processOrder(ProcessOrderRequest request);
```

## Multi-Parameter Method Support

### D8: Synthetic Request Records

**Decision**: For slice methods with multiple parameters or single primitive parameters, the processor generates synthetic request records inside the factory interface. Method overloads are rejected at compile time.

**Naming Convention**: `{MethodName}Request` (e.g., `GetStockRequest`, `TransferRequest`).

**Rationale**:
- Provides deterministic naming across caller and callee
- Same generated record can be used on both sides of the transport
- Overloads are rejected at compile time, so sequence numbering is unnecessary

### Examples

**Zero Parameters**:
```java
// Slice interface
Promise<HealthStatus> healthCheck();

// Generated SliceMethod uses Unit as request type
new SliceMethod<>(
    MethodName.methodName("healthCheck").unwrap(),
    _ -> delegate.healthCheck(),
    new TypeToken<HealthStatus>() {},
    new TypeToken<Unit>() {}
)
```

**Single Primitive Parameter**:
```java
// Slice interface
Promise<Stock> getStock(String sku);

// Generated synthetic record
public record GetStockRequest(String sku) {}

// Generated SliceMethod uses the synthetic record
new SliceMethod<>(
    MethodName.methodName("getStock").unwrap(),
    request -> delegate.getStock(request.sku()),
    new TypeToken<Stock>() {},
    new TypeToken<GetStockRequest>() {}
)
```

**Multiple Parameters**:
```java
// Slice interface
Promise<TransferResult> transfer(String from, String to, BigDecimal amount);

// Generated synthetic record
public record TransferRequest(String from, String to, BigDecimal amount) {}

// Generated SliceMethod
new SliceMethod<>(
    MethodName.methodName("transfer").unwrap(),
    request -> delegate.transfer(request.from(), request.to(), request.amount()),
    new TypeToken<TransferResult>() {},
    new TypeToken<TransferRequest>() {}
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

**Method Overloads (rejected)**:
```java
// COMPILE ERROR - overloaded methods are not allowed
Promise<User> getUser(Long id);
Promise<User> getUser(String email);
// error: Overloaded slice method 'getUser' is not supported. Use distinct method names.
```

### Slice Dependency Proxies

For slice dependencies with multi-parameter methods, the generator creates proxy records with `MethodHandle` fields and synthetic request records:

```java
// Slice dependency interface (in another slice)
public interface PaymentService {
    Promise<PaymentResult> processPayment(String orderId, BigDecimal amount);
}

// Generated proxy record inside factory
record paymentService(MethodHandle<PaymentResult, ProcessPaymentRequest> processPaymentHandle)
        implements PaymentService {
    // Synthetic record for multi-param method
    public record ProcessPaymentRequest(String orderId, BigDecimal amount) {}

    @Override
    public Promise<PaymentResult> processPayment(String orderId, BigDecimal amount) {
        return processPaymentHandle.invoke(new ProcessPaymentRequest(orderId, amount));
    }
}

// MethodHandle obtained via:
ctx.invoker().methodHandle("org.example:payment:1.0.0", "processPayment",
                            new TypeToken<ProcessPaymentRequest>() {},
                            new TypeToken<PaymentResult>() {}).async()
```

### D9: Resource Dependencies

**Decision**: Parameters annotated with `@ResourceQualifier` meta-annotations are provisioned via `ctx.resources().provide()`.

**Rationale**: Resources (database connections, HTTP clients, etc.) are configured externally and provisioned at runtime by the Aether resource provider SPI.

```java
@Slice
public interface OrderRepository {
    Promise<Order> save(SaveOrderRequest request);

    static OrderRepository orderRepository(@PrimaryDb DatabaseConnector db) {
        return new orderRepository(db);
    }
}

// Generated factory:
public static Promise<OrderRepository> orderRepository(Aspect<OrderRepository> aspect,
                                                         SliceCreationContext ctx) {
    return Promise.all(
            ctx.resources().provide(DatabaseConnector.class, "database.primary"))
        .map((db) -> {
            return aspect.apply(OrderRepository.orderRepository(db));
        });
}
```

See [RFC-0006: Resource Provisioning](../../../docs/rfc/RFC-0006-resource-provisioning.md) for the full resource provisioning architecture.

### D10: Plain Interface Dependencies

**Decision**: Non-`@Slice` interfaces with static factory methods are treated as plain interface dependencies. They are constructed by calling their factory method directly.

**Transitive resource provisioning**: If the plain interface's factory method has `@ResourceQualifier`-annotated parameters, those resources are provisioned transitively and passed as arguments.

```java
// Plain interface - not a @Slice
public interface KycStep {
    Promise<Boolean> verify(String customerId);

    static KycStep kycStep(@KycProvider HttpClient httpClient) {
        return new kycStep(httpClient);
    }
}

@Slice
public interface LoanService {
    Promise<LoanResult> processLoan(LoanRequest request);
    static LoanService loanService(KycStep kycStep) { return new loanService(kycStep); }
}

// Generated factory:
public static Promise<LoanService> loanService(Aspect<LoanService> aspect,
                                                 SliceCreationContext ctx) {
    return Promise.all(
            ctx.resources().provide(HttpClient.class, "http.kyc"))
        .map((kycStep_httpClient) -> {
            var kycStep = KycStep.kycStep(kycStep_httpClient);
            return aspect.apply(LoanService.loanService(kycStep));
        });
}
```

**Zero-arg plain interfaces** (factory has no parameters) are constructed synchronously:
```java
var validator = OrderValidator.orderValidator();
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

### SliceCreationContext
```java
public interface SliceCreationContext {
    SliceInvokerFacade invoker();
    ResourceProviderFacade resources();
}
```

### SliceInvokerFacade
```java
public interface SliceInvokerFacade {
    <T> Promise<T> invoke(String artifact, String method, Object request, Class<T> responseType);
    <R, I> MethodHandle<R, I> methodHandle(String artifact, String method,
                                            TypeToken<I> requestType, TypeToken<R> responseType);
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
| `should_generate_resource_provide_call_for_qualified_parameter` | @ResourceQualifier resource dep |
| `should_generate_resource_provide_for_plain_interface_factory_params` | Plain interface with @ResourceQualifier factory params |
| `should_generate_resources_for_multiple_plain_interfaces_with_params` | Multiple plain interfaces with resource params |

## Revision History

| Date | Author | Changes |
|------|--------|---------|
| 2026-01-11 | Claude | Initial design |
| 2026-01-11 | Claude | Implemented: local proxy records, createSlice(), internal/external deps |
| 2026-01-11 | Claude | Added comprehensive test coverage (11 tests) |
| 2026-01-11 | Claude | Fixed: import internal deps from subpackages |
| 2026-01-14 | Claude | Added D8: Multi-parameter method support with synthetic records |
| 2026-02-16 | Claude | Updated for SliceCreationContext, resource deps, plain interface deps with transitive provisioning |
