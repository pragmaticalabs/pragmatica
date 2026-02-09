# Aether Slice Annotations

Compile-time annotations for Aether slice development.

## Overview

Provides the `@Slice` annotation for declarative slice development. When used with the slice-processor annotation processor, it generates API interfaces for typed inter-slice calls, proxy implementations, factory methods, and JAR manifest entries.

This module has no runtime dependencies -- it is purely compile-time metadata.

## Usage

```java
@Slice
public interface OrderService {
    Promise<OrderResponse> placeOrder(PlaceOrderRequest request);
    Promise<OrderStatus> getStatus(OrderId orderId);
}
```

### Generated Output

1. **API Interface** - `OrderServiceApi.java` for typed client access
2. **Proxy Class** - Handles serialization and remote invocation
3. **Factory Method** - For obtaining service instances
4. **Manifest Entries** - For runtime discovery

### Build Configuration

```xml
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>slice-processor</artifactId>
    <version>${jbct.version}</version>
    <scope>provided</scope>
</dependency>
```

## Dependencies

None (compile-time only).
