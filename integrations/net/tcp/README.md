# TCP Server

Netty TCP server wrapper with comprehensive TLS support.

## Overview

Provides a Promise-based API for Netty TCP server setup, supporting server TLS, client TLS, and mutual TLS (mTLS) configurations. Includes configurable socket options, client connection capability (reusing the server's event loop), and graceful shutdown with intermediate operation support.

All TLS operations return `Result<T>` with typed `TlsError` causes.

## Usage

### Basic Server

```java
import org.pragmatica.net.tcp.Server;

Server.server("echo-server", 8080, () -> List.of(new EchoHandler()))
    .onSuccess(server -> System.out.println("Started on port " + server.port()));
```

### Server with TLS

```java
var config = ServerConfig.serverConfig("secure-server", 8443)
    .withTls(TlsConfig.server(Path.of("cert.pem"), Path.of("key.pem")));

Server.server(config, () -> List.of(new MyHandler()));
```

### Mutual TLS

```java
var mtls = TlsConfig.mutual(
    Path.of("node.crt"), Path.of("node.key"), Path.of("cluster-ca.crt"));

var config = ServerConfig.serverConfig("ClusterNode", 9000)
    .withTls(mtls)
    .withClientTls(mtls);
```

### Client Connections

```java
server.connectTo(NodeAddress.nodeAddress("peer.example.com", 8080))
    .onSuccess(channel -> channel.writeAndFlush(message));
```

### TLS Factory Methods

| Method | Description |
|--------|-------------|
| `TlsConfig.selfSignedServer()` | Self-signed server certificate |
| `TlsConfig.server(cert, key)` | Server from PEM files |
| `TlsConfig.serverWithClientAuth(cert, key, ca)` | Server requiring client certs |
| `TlsConfig.client()` | Client using system CAs |
| `TlsConfig.clientWithCa(ca)` | Client with custom CA |
| `TlsConfig.insecureClient()` | Client trusting all (dev only) |
| `TlsConfig.mutual(cert, key, ca)` | Mutual TLS configuration |
| `TlsConfig.selfSignedMutual()` | Dev mTLS configuration |

## Dependencies

- Netty 4.2+
- `pragmatica-lite-core`
