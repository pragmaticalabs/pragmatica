# Aether Infrastructure: Feature Flags

Runtime feature toggles for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-feature</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides runtime feature toggles with targeting support for A/B testing and gradual rollouts.

### Key Features

- Boolean flags (enabled/disabled)
- Variant flags (A/B testing)
- Context-based targeting (user ID, region, etc.)
- Dynamic flag management

### Quick Start

```java
var flags = FeatureFlags.inMemory();

// Set up flags
flags.setFlag("new-checkout", FlagConfig.enabled()).await();
flags.setFlag("dark-mode", FlagConfig.disabled()).await();
flags.setFlag("pricing-experiment", FlagConfig.variants("control", "variant-a", "variant-b")).await();

// Check boolean flag
if (flags.isEnabled("new-checkout").await()) {
    showNewCheckout();
} else {
    showOldCheckout();
}

// Check with context (for targeting)
var context = Context.context()
    .userId("user-123")
    .region("us-west")
    .build();

if (flags.isEnabled("new-checkout", context).await()) {
    // User-specific evaluation
}

// Get variant for A/B testing
var variant = flags.getVariant("pricing-experiment", context).await();
variant.onPresent(v -> {
    switch (v) {
        case "control" -> showControlPricing();
        case "variant-a" -> showDiscountedPricing();
        case "variant-b" -> showPremiumPricing();
    }
});

// List all flags
var allFlags = flags.listFlags().await(); // Map<String, FlagConfig>
```

### API Summary

| Category | Methods |
|----------|---------|
| Boolean | `isEnabled(flag)`, `isEnabled(flag, context)` |
| Variants | `getVariant(flag)`, `getVariant(flag, context)` |
| Management | `setFlag`, `deleteFlag`, `listFlags` |

### Data Types

```java
record FlagConfig(boolean enabled, List<String> variants,
                  Map<String, Object> targeting) {
    static FlagConfig enabled();
    static FlagConfig disabled();
    static FlagConfig variants(String... variants);
}

record Context(String userId, String region, Map<String, String> attributes) {
    static ContextBuilder context();
}
```

## License

Apache License 2.0
