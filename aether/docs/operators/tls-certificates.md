# TLS Certificate Management

Aether secures all inter-node communication with TLS 1.3. QUIC transport mandates TLS -- there
is no plaintext mode. This guide covers certificate provisioning, rotation, monitoring, and
troubleshooting.

## Overview

Aether uses TLS in three contexts:

| Context | Transport | Certificate Source |
|---------|-----------|-------------------|
| Cluster transport (consensus, KV-Store, DHT, HTTP forwarding) | QUIC (UDP) | Auto-generated or manual |
| Management API | TCP (HTTP/1.1 or HTTP/3) | Same as cluster |
| Application HTTP (slice endpoints) | TCP (HTTP/1.1 or HTTP/3) | Same as cluster |

Additionally, SWIM gossip messages (UDP port 8091) are encrypted with AES-256-GCM using a key
derived from the same cluster secret.

## Auto-Generated Certificates (Development and Simple Deployments)

By default, Aether auto-generates self-signed certificates at startup. Set a shared
`cluster_secret` and all nodes derive the same CA -- no external PKI required.

### How It Works

1. All nodes share a `cluster_secret` (configured in `aether.toml` or via environment variable)
2. Each node independently derives the same CA key pair using HKDF (HMAC-based Key Derivation Function)
3. Each node generates its own certificate signed by this derived CA
4. Since all nodes derive the same CA, they mutually trust each other
5. No certificate distribution, no external PKI, no manual trust configuration

### Cryptographic Properties

| Property | Value |
|----------|-------|
| EC curve | P-256 |
| Signature algorithm | SHA256withECDSA |
| HKDF salt | `"aether-ca-seed"` (UTF-8) |
| HKDF info label | `"aether-ca-key-v1"` (UTF-8) |
| CA validity | 365 days |
| CA subject | `CN=Aether Cluster CA` |
| Node certificate validity | 7 days |
| Trust model | All nodes sharing the same secret trust each other |

### Configuration

Minimal `aether.toml` for auto-generated certificates:

```toml
[tls]
auto_generate = true
cluster_secret = "your-shared-secret"
```

The cluster secret can also be provided via environment variable:

```toml
[tls]
auto_generate = true
cluster_secret = "${env:AETHER_CLUSTER_SECRET}"
```

If no `cluster_secret` is configured and no `AETHER_CLUSTER_SECRET` environment variable is set,
Aether falls back to a built-in development secret (`aether-dev-cluster-secret`). This is
convenient for local development but must not be used in production.

### Secret Resolution Order

1. `cluster_secret` value from `[tls]` section in `aether.toml`
2. `AETHER_CLUSTER_SECRET` environment variable
3. Built-in development secret (development only)

## Manual Certificate Files (Production)

For production deployments with an existing PKI or certificate management system, provide
certificate files directly:

```toml
[tls]
auto_generate = false
cert_path = "/etc/aether/node.crt"
key_path = "/etc/aether/node.key"
ca_path = "/etc/aether/ca.crt"
```

| Field | Description |
|-------|-------------|
| `cert_path` | Path to the PEM-encoded node certificate |
| `key_path` | Path to the PEM-encoded private key |
| `ca_path` | Path to the PEM-encoded CA certificate (used to verify peers) |

Requirements for manual certificates:
- All nodes must trust the same CA (or CA chain)
- Node certificates should include the node's hostname or IP as a Subject Alternative Name (SAN)
- Certificates must be valid PEM format

## Certificate Rotation Lifecycle

The `CertificateRenewalScheduler` handles automatic renewal for auto-generated certificates.

### Renewal Timing

Renewal triggers at **40% of remaining validity** (equivalently, when 60% of the certificate's
lifetime has elapsed). For the default 7-day node certificates:

| Event | Day |
|-------|-----|
| Certificate issued | 0 |
| Renewal triggers (60% elapsed) | ~2.8 |
| Certificate expires | 7 |

This gives a comfortable window for retries if renewal fails.

### Renewal Process

1. Scheduler fires at the calculated renewal time
2. A new node certificate is issued by the `CertificateProvider` (same derived CA)
3. QUIC server, management HTTP server, and app HTTP server receive the new certificate
4. New connections use the updated certificate
5. Existing connections continue with the old certificate until they close naturally
6. Scheduler reschedules the next renewal based on the new certificate's expiry

### Retry Strategy on Failure

If renewal fails, the scheduler retries with exponential backoff:

| Attempt | Delay |
|---------|-------|
| 1 | 5 minutes |
| 2 | 15 minutes |
| 3 | 45 minutes |
| 4 | 2 hours 15 minutes |
| 5+ | 4 hours (cap) |

The multiplier is 3x per attempt, capped at 240 minutes (4 hours).

### Impact on Connections

During certificate rotation:
- New connections immediately use the new certificate
- Existing connections are unaffected (they keep their negotiated TLS session)
- Peers reconnect automatically; any disruption is sub-second

## Gossip Encryption

SWIM protocol messages (health detection, membership updates) are transmitted over UDP and
encrypted separately from the QUIC transport.

### Encryption Details

| Property | Value |
|----------|-------|
| Algorithm | AES-256-GCM |
| Key size | 32 bytes (256 bits) |
| Nonce size | 12 bytes (random per message) |
| GCM authentication tag | 128 bits |
| Key derivation | HKDF from `cluster_secret` with info label `"aether-gossip-key-v1"` |

### Wire Format

```
[4-byte keyId (big-endian)] [12-byte nonce (random)] [ciphertext + 16-byte GCM auth tag]
```

Total overhead per gossip message: 32 bytes (4 keyId + 12 nonce + 16 auth tag).

### Dual-Key Rotation

Gossip keys support zero-downtime rotation via a dual-key scheme:

1. A new key (Key B) is generated and distributed via the KV-Store
2. Both Key A (previous) and Key B (current) are accepted for decryption
3. All nodes switch to sending with Key B
4. After a grace period, Key A is retired

The `keyId` field in the wire format identifies which key encrypted the message. Receivers try
the current key first, then fall back to the previous key.

## Monitoring Certificate Expiry

### Management API

```
GET /api/certificate
```

Response:

```json
{
  "expiresAt": "2026-04-02T14:30:00Z",
  "secondsUntilExpiry": 518400,
  "lastRenewalAt": "2026-03-26T14:30:00Z",
  "renewalStatus": "HEALTHY"
}
```

| Field | Description |
|-------|-------------|
| `expiresAt` | ISO-8601 timestamp when the current node certificate expires |
| `secondsUntilExpiry` | Seconds remaining until expiry |
| `lastRenewalAt` | ISO-8601 timestamp of the last successful renewal |
| `renewalStatus` | One of `HEALTHY`, `RENEWING`, or `FAILED` |

When no certificate provider is configured, the response returns:

```json
{
  "expiresAt": "N/A",
  "secondsUntilExpiry": 0,
  "lastRenewalAt": "N/A",
  "renewalStatus": "NOT_CONFIGURED"
}
```

### CLI

```bash
aether cert status
```

This fetches and displays the `/api/certificate` endpoint output for the connected node.

### Cluster-Level Status

The `GET /api/cluster/status` endpoint includes certificate information aggregated across the
cluster:

```json
{
  "certificateExpiresAt": "2026-04-02T14:30:00Z",
  "certificateDaysRemaining": 7
}
```

## Failure Modes and Troubleshooting

### Certificate Expired

**Symptoms:** Nodes cannot establish new QUIC connections to each other. Existing connections
continue to work until they are closed or time out.

**Resolution:**
1. Check renewal scheduler status: `GET /api/certificate` -- look for `renewalStatus: "FAILED"`
2. Check logs for `CertificateRenewalScheduler` errors
3. If auto-generating, verify `cluster_secret` is set and consistent across all nodes
4. Restart the node -- a fresh certificate will be generated on startup

### Wrong Cluster Secret

**Symptoms:** Nodes reject each other's certificates during TLS handshake. Logs show TLS
handshake failures. Nodes appear isolated (each sees only itself in the cluster).

**Resolution:**
1. Verify all nodes use the same `cluster_secret` in `aether.toml`
2. Check if `AETHER_CLUSTER_SECRET` environment variable differs between nodes
3. After correcting the secret, restart the affected nodes

### Renewal Failed

**Symptoms:** `renewalStatus` shows `FAILED` in the certificate status endpoint. Logs contain
`Certificate renewal failed (attempt N)` messages from `CertificateRenewalScheduler`.

**Resolution:**
1. The scheduler automatically retries with exponential backoff
2. Check logs for the specific failure cause
3. For `CaGenerationFailed`: verify the BouncyCastle security provider is available
4. For `CertificateIssueFailed`: check file system permissions if using file-based certificates
5. If retries are exhausted and the certificate expires, restart the node

### TLS Setup Failed at Startup

**Symptoms:** Log message `Failed to setup TLS: <cause>`. The node starts without TLS (falls
back to ephemeral self-signed certificates without cluster trust).

**Resolution:**
1. Check the specific error in the log message
2. Verify `[tls]` configuration in `aether.toml` is correct
3. For manual certificates: verify file paths exist and are readable
4. For auto-generated: ensure `cluster_secret` is valid

### Gossip Decryption Failed

**Symptoms:** `UnknownKeyId` errors in logs. SWIM health detection may become unreliable.

**Resolution:**
1. Verify all nodes share the same `cluster_secret`
2. This can occur briefly during key rotation (expected)
3. If persistent, check that nodes are running the same Aether version

## Configuration Reference

Complete `[tls]` section options:

```toml
[tls]
# Generate self-signed certificates automatically (default: true)
auto_generate = true

# Shared secret for deterministic CA derivation
# All nodes with the same secret trust each other
cluster_secret = "your-production-secret"

# OR provide certificate files directly (requires auto_generate = false)
# cert_path = "/etc/aether/node.crt"
# key_path = "/etc/aether/node.key"
# ca_path = "/etc/aether/ca.crt"
```

The `cluster.tls` flag in the `[cluster]` section controls whether TLS is enabled at all:

```toml
[cluster]
environment = "docker"
nodes = 5
tls = true   # Enable TLS for cluster communication
```

When `tls = false`, no TLS configuration is applied and QUIC uses ephemeral self-signed
certificates (no shared trust, suitable only for single-node development).

## Cloud Provider Certificates

Currently, Aether supports self-signed certificates via `SelfSignedCertificateProvider`. The
`CertificateProvider` interface is designed for extensibility -- cloud CA adapters (e.g., AWS
Private CA, GCP Certificate Authority Service, HashiCorp Vault PKI) are planned for a future
release. See [GitHub Issue #88](https://github.com/pragmatica-lite/pragmatica/issues/88) for
tracking.

## Related Documents

- [Networking Requirements](networking.md) -- Ports, firewall rules, QUIC transport details
- [Security Architecture](../architecture/10-security.md) -- Full security model including RBAC and API keys
- [Management API Reference](../reference/management-api.md) -- Complete API documentation
