# Aether Infrastructure: Aspect

Cross-cutting concern aspects for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-aspect</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides aspect factories for common cross-cutting concerns: logging, retry, circuit breaker, metrics, and transactions.

All configuration factories return `Result<Config>` following JBCT patterns - validation happens at creation time.

### Available Aspects

| Aspect | Description |
|--------|-------------|
| Logging | Entry/exit logging with duration, args, and result |
| Retry | Automatic retry with configurable backoff strategies |
| Circuit Breaker | Prevents cascading failures with open/closed/half-open states |
| Metrics | Execution timing via Micrometer |
| Transaction | Transaction boundary management with propagation |

## Logging

Logs method entry (`->`), exit (`<-`), and errors (`x`) with configurable detail level.

```java
var factory = LoggingAspectFactory.loggingAspectFactory();

// Default: INFO level, logs args, result, and duration
var config = LogConfig.logConfig("OrderService").unwrap();

// Custom level
var debugConfig = LogConfig.logConfig("OrderService", LogLevel.DEBUG).unwrap();

// Fine-tune what to log
var minimalConfig = LogConfig.logConfig("OrderService").unwrap()
    .withLogArgs(false)
    .withLogResult(false)
    .withLogDuration(true);

var aspect = factory.<OrderService>create(config);
var wrapped = aspect.apply(orderService);

// Output:
// -> OrderService.placeOrder args=[Order{id=123}]
// <- OrderService.placeOrder result=Success (42.15ms)
```

## Retry

Automatic retry with exponential or fixed backoff using pragmatica-lite `Retry`.

```java
var factory = RetryAspectFactory.retryAspectFactory();

// Exponential backoff (default): 100ms initial, 2x factor, 10s max
var config = RetryConfig.retryConfig(3).unwrap();

// Fixed interval
var fixedConfig = RetryConfig.retryConfig(3, timeSpan(500).millis()).unwrap();

// Custom backoff strategy
var customConfig = RetryConfig.retryConfig(
    5,
    BackoffStrategy.exponential()
        .initialDelay(timeSpan(200).millis())
        .maxDelay(timeSpan(30).seconds())
        .factor(1.5)
        .withJitter(0.1)
).unwrap();

var aspect = factory.<PaymentGateway>create(config);
var wrapped = aspect.apply(paymentGateway);
```

## Circuit Breaker

Prevents cascading failures using pragmatica-lite `CircuitBreaker`.

```java
var factory = CircuitBreakerFactory.circuitBreakerFactory();

// Default: 5 failures, 30s reset, 3 test attempts
var config = CircuitBreakerConfig.circuitBreakerConfig().unwrap();

// Custom failure threshold
var sensitiveConfig = CircuitBreakerConfig.circuitBreakerConfig(3).unwrap();

// Fully customized
var customConfig = CircuitBreakerConfig.circuitBreakerConfig(
    5,                        // failures before opening
    timeSpan(60).seconds(),   // reset timeout
    2                         // test attempts in half-open
).unwrap();

var aspect = factory.<ExternalService>create(config);
var wrapped = aspect.apply(externalService);
```

**States:**
- **Closed**: Normal operation, counting failures
- **Open**: Failing fast, rejecting calls
- **Half-Open**: Testing with limited requests

## Metrics

Records timing and success/failure counts via Micrometer.

```java
var factory = MetricsAspectFactory.metricsAspectFactory(meterRegistry);

// Default: records timing and counts
var config = MetricsConfig.metricsConfig("order_service").unwrap();

// With tags
var taggedConfig = MetricsConfig.metricsConfig("order_service").unwrap()
    .withTags("env", "prod", "region", "us-east");

// Selective recording
var timingOnly = MetricsConfig.metricsConfig("order_service", true, false).unwrap();

var aspect = factory.<OrderService>create(config);
var wrapped = aspect.apply(orderService);

// Metrics recorded:
// order_service.placeOrder.success (timer)
// order_service.placeOrder.failure (timer)
```

## Transaction

Manages transaction boundaries with Spring-like propagation semantics.

```java
var factory = TransactionAspectFactory.transactionAspectFactory();

// Default: REQUIRED propagation, DEFAULT isolation
var config = TransactionConfig.transactionConfig().unwrap();

// Custom propagation
var newTxConfig = TransactionConfig.transactionConfig(TransactionPropagation.REQUIRES_NEW).unwrap();

// Full configuration
var fullConfig = TransactionConfig.transactionConfig(
    TransactionPropagation.REQUIRED,
    IsolationLevel.READ_COMMITTED
).unwrap()
    .withTimeout(timeSpan(30).seconds())
    .asReadOnly();

var aspect = factory.<UserRepository>create(config);
var wrapped = aspect.apply(userRepository);
```

**Propagation options:**
- `REQUIRED` - Join existing or create new (default)
- `REQUIRES_NEW` - Always create new, suspend existing
- `SUPPORTS` - Use existing if present, otherwise non-transactional
- `MANDATORY` - Require existing, fail if none
- `NOT_SUPPORTED` - Suspend existing, run non-transactional
- `NEVER` - Fail if transaction exists
- `NESTED` - Create savepoint within existing

## Composing Aspects

Aspects compose naturally - apply in desired order:

```java
var logging = LoggingAspectFactory.loggingAspectFactory()
    .<PaymentService>create(LogConfig.logConfig("PaymentService").unwrap());

var retry = RetryAspectFactory.retryAspectFactory()
    .<PaymentService>create(RetryConfig.retryConfig(3).unwrap());

var circuitBreaker = CircuitBreakerFactory.circuitBreakerFactory()
    .<PaymentService>create(CircuitBreakerConfig.circuitBreakerConfig().unwrap());

// Order: logging -> retry -> circuit breaker -> target
// Logging sees all attempts, retry wraps circuit breaker
var wrapped = logging.apply(
    retry.apply(
        circuitBreaker.apply(paymentService)
    )
);
```

## Global Enable/Disable

Each factory supports runtime enable/disable:

```java
var factory = LoggingAspectFactory.loggingAspectFactory();

factory.setEnabled(false);  // Disable all logging aspects
factory.isEnabled();        // Check status
factory.setEnabled(true);   // Re-enable
```

## License

Apache License 2.0
