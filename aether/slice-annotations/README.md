# Aether Slice Annotations

Compile-time annotations for [Aether](https://github.com/siy/aether) slice development.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>slice-annotations</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

This module provides the `@Slice` annotation for declarative slice development. When used with the slice-processor (from [jbct-cli](https://github.com/siy/jbct-cli)), it generates:

- API interfaces for typed inter-slice calls
- Proxy implementations
- Factory methods
- JAR manifest entries

### Annotation

```java
@Slice
public interface OrderService {
    Promise<OrderResponse> placeOrder(PlaceOrderRequest request);
    Promise<OrderStatus> getStatus(OrderId orderId);
}
```

### Generated Output

The annotation processor generates:

1. **API Interface** - `OrderServiceApi.java` for typed client access
2. **Proxy Class** - Handles serialization and remote invocation
3. **Factory Method** - For obtaining service instances
4. **Manifest Entries** - For runtime discovery

## Usage with slice-processor

Add the annotation processor to your build:

```xml
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>slice-processor</artifactId>
    <version>${jbct.version}</version>
    <scope>provided</scope>
</dependency>
```

## Zero Dependencies

This module has no runtime dependencies - it's purely compile-time metadata.

## License

Apache License 2.0
