# Micrometer Integration

Aspect decorators for adding Micrometer metrics to Promise-returning functions.

## Overview

Wraps `Promise`-returning functions with automatic metrics recording. Supports timer, counter, and combined metric types, each tracking success and failure separately. Also provides `ResultMetrics` and `OptionMetrics` for synchronous operations.

## Usage

```java
import org.pragmatica.metrics.PromiseMetrics;

// Timer metrics (duration + count, separate success/failure)
var metrics = PromiseMetrics.timer("order.process")
    .registry(meterRegistry)
    .tags("service", "orders")
    .build();

// Wrap a function
Fn1<Promise<Order>, OrderRequest> wrapped = metrics.around(orderService::process);

// Counter metrics (count only, no duration)
var counter = PromiseMetrics.counter("events.processed")
    .registry(meterRegistry)
    .build();

// Combined metrics (single timer + separate success/failure counters)
var combined = PromiseMetrics.combined("db.query")
    .registry(meterRegistry)
    .build();
```

### Synchronous Metrics

```java
// For Result<T> returning functions
var resultMetrics = ResultMetrics.counter("validation")
    .registry(registry)
    .build();

// For Option<T> returning functions
var optionMetrics = OptionMetrics.counter("cache.lookup")
    .registry(registry)
    .build();
```

## Dependencies

- Micrometer Core 1.12+
- `pragmatica-lite-core`
