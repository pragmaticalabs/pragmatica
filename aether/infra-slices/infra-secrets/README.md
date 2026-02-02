# Aether Infrastructure: Secrets

Secure secrets management service for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-secrets</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides secure storage for sensitive data with versioning, rotation, and tagging support.

### Key Features

- Secret versioning (automatic version tracking)
- Secret rotation with timestamp tracking
- Tag-based organization
- Pattern-based listing
- Metadata-only queries (no value exposure)

### Quick Start

```java
var secrets = SecretsManager.secretsManager();

// Create secret
secrets.createSecret("db/password", SecretValue.of("super-secret-123")).await();

// Create with tags
secrets.createSecret(
    "api/stripe-key",
    SecretValue.of("sk_live_xxx"),
    Map.of("env", "production", "service", "payments")
).await();

// Get current value
var password = secrets.getSecret("db/password").await();

// Get specific version
var oldValue = secrets.getSecretVersion("db/password", 1).await();

// Rotate secret
secrets.rotateSecret("db/password", SecretValue.of("new-password-456")).await();

// List by tag
var prodSecrets = secrets.listSecretsByTag("env", "production").await();

// List by pattern
var dbSecrets = secrets.listSecrets("db/*").await();
```

### API Summary

| Category | Methods |
|----------|---------|
| CRUD | `createSecret`, `getSecret`, `updateSecret`, `deleteSecret`, `secretExists` |
| Versions | `getSecretVersion`, `listVersions`, `deleteVersion` |
| Rotation | `rotateSecret` |
| Metadata | `getMetadata`, `updateTags` |
| Listing | `listSecrets`, `listSecretsByTag` |

### Data Types

```java
record SecretValue(String value) {
    static SecretValue of(String value);
}

record SecretMetadata(String name, int currentVersion, Instant createdAt,
                      Instant lastRotatedAt, Map<String, String> tags) {}
```

## License

Apache License 2.0
