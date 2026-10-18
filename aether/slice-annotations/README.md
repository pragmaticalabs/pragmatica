# Aether Slice Annotations

Compile-time annotations for Aether slice development.

## Overview

Provides the `@Slice` annotation for declarative slice development. When used with the slice-processor annotation processor, it generates a factory class (with proxies for slice dependencies), a routes class (when a `routes.toml` is present), and a slice manifest.

This module has no dependencies. `@Slice` is retained at runtime (`RetentionPolicy.RUNTIME`).

## Usage

```java
@Slice
public interface OrderService {
    Promise<OrderResponse> placeOrder(PlaceOrderRequest request);
    Promise<OrderStatus> getStatus(OrderId orderId);
}
```

### Generated Output

1. **Factory** - `OrderServiceFactory`, which creates the slice and proxies for the slices it depends on. The `@Slice` interface itself is the API; no separate `*Api` file is generated.
2. **Routes** - `OrderServiceRoutes`, generated when a `routes.toml` is present for the package.
3. **Manifest** - `META-INF/slice/OrderService.manifest`, for runtime discovery.

### Build Configuration

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>slice-annotations</artifactId>
    <version>1.0.0-rc5</version>
</dependency>
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>slice-processor</artifactId>
    <version>1.0.0-rc5</version>
    <scope>provided</scope>
</dependency>
```

## Dependencies

None (compile-time only).
