# Aether Infrastructure: Secrets

Secure secrets management service for the Aether distributed runtime.

## Overview

Provides secure storage for sensitive data with versioning, rotation, and tagging support. Features automatic version tracking, secret rotation with timestamp tracking, tag-based organization, pattern-based listing, and metadata-only queries.

## Usage

```java
var secrets = SecretsManager.secretsManager();

// Create secret
secrets.createSecret("db/password", SecretValue.of("super-secret-123")).await();

// Create with tags
secrets.createSecret("api/stripe-key", SecretValue.of("sk_live_xxx"),
    Map.of("env", "production", "service", "payments")).await();

// Get current value
var password = secrets.getSecret("db/password").await();

// Get specific version
var oldValue = secrets.getSecretVersion("db/password", 1).await();

// Rotate secret
secrets.rotateSecret("db/password", SecretValue.of("new-password-456")).await();

// List by tag or pattern
var prodSecrets = secrets.listSecretsByTag("env", "production").await();
var dbSecrets = secrets.listSecrets("db/*").await();
```

## Dependencies

- `pragmatica-lite-core`
