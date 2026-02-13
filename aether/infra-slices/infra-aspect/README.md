# Aether Infrastructure: Aspect

Cross-cutting concern aspects for the Aether distributed runtime.

## Overview

Provides aspect factories for common cross-cutting concerns: logging, retry, circuit breaker, metrics, and transactions. All configuration factories return `Result<Config>` -- validation happens at creation time. Aspects compose naturally by nesting `apply` calls.

| Aspect | Description |
|--------|-------------|
| Logging | Entry/exit logging with duration, args, and result |
| Retry | Automatic retry with configurable backoff strategies |
| Circuit Breaker | Prevents cascading failures with open/closed/half-open states |
| Metrics | Execution timing via Micrometer |
| Transaction | Transaction boundary management with propagation |

## Usage

```java
// Logging
var logging = LoggingAspectFactory.loggingAspectFactory()
    .<OrderService>create(LogConfig.logConfig("OrderService").unwrap());

// Retry (exponential backoff)
var retry = RetryAspectFactory.retryAspectFactory()
    .<PaymentGateway>create(RetryConfig.retryConfig(3).unwrap());

// Circuit Breaker (5 failures, 30s reset)
var circuitBreaker = CircuitBreakerFactory.circuitBreakerFactory()
    .<ExternalService>create(CircuitBreakerConfig.circuitBreakerConfig().unwrap());

// Metrics
var metrics = MetricsAspectFactory.metricsAspectFactory(meterRegistry)
    .<OrderService>create(MetricsConfig.metricsConfig("order_service").unwrap());

// Transaction
var tx = TransactionAspectFactory.transactionAspectFactory()
    .<UserRepository>create(TransactionConfig.transactionConfig().unwrap());

// Compose: logging -> retry -> circuit breaker -> target
var wrapped = logging.apply(retry.apply(circuitBreaker.apply(service)));
```

## Dependencies

- `pragmatica-lite-core`
- Micrometer Core (for metrics aspect)
