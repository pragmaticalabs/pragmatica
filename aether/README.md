# Aether — Unified Application Runtime

Unified Application Runtime for Java -- scale horizontally without microservices complexity.

> **Release status: `1.0.0-rc4` (release candidate).** This RC validates the distributed
> foundation — consensus, leader election, failure detection, membership, and topology
> management — under sustained cloud load. Scope for this RC:
> - **Single trust domain.** Aether assumes all cluster nodes and management clients are
>   operated by one trusted party. It is **not** hardened for multi-tenant or hostile-network
>   deployment.
> - **Security is on by default in this RC.** The management API supports API-key auth with
>   role-based access (viewer / operator / admin), and inter-node transport runs over TLS
>   (self-signed by default, or operator-supplied certificates); the default `SecurityMode` is
>   `API_KEY`, not `NONE` — a fresh cluster with no configured key mints a one-time ADMIN key and
>   prints it once in the startup log (look for the `AETHER BOOTSTRAP ADMIN API KEY` banner), or
>   you can preset one via `AETHER_API_KEYS=<key>` or an `[app-http.api-keys.<name>]` table in
>   `aether.toml`. For local experiments, set `security_mode = "none"` under `[app-http]` in
>   `aether.toml` to disable auth entirely. Separately, `AETHER_INSECURE_DEV_MODE` is an explicit opt-in that
>   enables test-injection endpoints; it is **refused at boot** when operator TLS certificates
>   are configured, and logs a loud startup warning whenever it is active.
> - **Not yet production-hardened.** Some background reconcilers are tuned for settled clusters
>   and can be transiently slower to converge under heavy churn; these are tracked for the next
>   milestones. Use this release candidate for evaluation and non-critical workloads.
> - **Known limitations are listed in
>   [docs/reference/known-limitations.md](docs/reference/known-limitations.md) — read before
>   evaluating.** Among them: a network cut that leaves no core majority halts every core,
>   node-binary version skew has no join-time check, and the dashboard does not work against a
>   real node.

## Overview

Aether lets you deploy Java services as **slices** and handles distribution, scaling, and resilience automatically. A slice is a typed Java interface with dependency injection via factory method parameters -- the same service model as Spring `@Service`, without the infrastructure overhead. Write services, and Aether manages deployment across nodes, automatic scaling based on CPU/latency/request rate, failure recovery, and load balancing. No actors, no message passing, no new paradigm to learn.

```java
@Slice
public interface PlaceOrder {
    Promise<OrderResult> execute(PlaceOrderRequest request);

    static PlaceOrder placeOrder(InventoryService inventory, PricingService pricing) {
        return request ->
            inventory.checkStock(request.items())
                .flatMap(stock -> pricing.calculateTotal(request.items()))
                .flatMap(total -> createOrder(request, stock, total));
    }
}
```

## Usage

### Quick Start

```bash
# Check cluster status
aether status

# Push the artifacts of a blueprint, then deploy the blueprint (coordinates are the blueprint's)
aether artifacts push org.example:my-blueprint:1.0.0
aether blueprints deploy org.example:my-blueprint:1.0.0 --wait

# Scale a slice
aether scale org.example:my-slice:1.0.0 -n 3
```

### Local Development with Forge

```bash
aether-forge
# Open http://localhost:8888
```

To run an example application on Forge, follow the [Quick Start](../README.md#quick-start): install, clone, start
PostgreSQL, `./run-forge.sh`, then `curl`. The example scripts use the installed Forge; nothing is built from source but
the example.

Forge provides a dashboard, cluster operations, chaos testing, and per-node management API access.
**rc4 limitation:** on Forge only `/ws/status` has a publisher (`/ws/dashboard` and `/ws/events` send nothing), and
Forge serves only a subset of the dashboard's unversioned `/api/...` REST calls, so several panels (governors,
strategies, streams, schema, storage, desired topology, among others) are empty; against a real node, whose router
serves `/api/v1` only, every REST-fed panel is empty. See
[Known Limitations](docs/reference/known-limitations.md#dashboard-against-a-real-node--rest-fed-panels-are-empty);
use the CLI or the `/api/v1` REST API for the data behind the empty panels.

### Installation

Installs `aether`, `aether-node` and `aether-forge` from the GitHub release into `~/.aether` (self-contained archives
bundle a JRE; the jar-only fallback needs **Java 25**):

```bash
curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/aether/install.sh | sh
```

`--version VERSION` pins a release (`curl ... | sh -s -- --version VERSION`). To install the JBCT CLI as well, use the
unified installer, `https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/install.sh`.

### Build from Source

Aether lives in the [pragmaticalabs/pragmatica](https://github.com/pragmaticalabs/pragmatica) monorepo:

```bash
git clone https://github.com/pragmaticalabs/pragmatica.git
cd pragmatica && ./build.sh
```

## Documentation

| Category | Documents |
|----------|-----------|
| **Start Here** | [What is Aether?](docs/aether-overview.md), [Introduction](docs/articles/aether-introduction.md), [Getting Started](docs/slice-developers/getting-started.md), [Migration Guide](docs/slice-developers/migration-guide.md), [FAQ](docs/slice-developers/faq.md) |
| **Core Concepts** | [Scaling](docs/operators/scaling.md), [Slice Lifecycle](docs/contributors/slice-lifecycle.md), [Architecture](docs/architecture/00-overview.md) |
| **Reference** | [CLI Reference](docs/reference/cli.md), [Forge Guide](docs/slice-developers/forge-guide.md), [Configuration](docs/reference/configuration.md), [Management API](docs/reference/management-api.md) |
| **Operations** | [Rolling Updates](docs/guides/rolling-upgrade.md), [Monitoring](docs/operators/monitoring.md), [Docker](docs/operators/docker-deployment.md) |
| **Design** | [Vision & Goals](docs/archive/vision-and-goals.md), [Metrics & Control](docs/contributors/metrics-control.md), [Slice API](docs/reference/slice-api.md) |

## Project Structure

Aether is the `aether/` tree of the [pragmaticalabs/pragmatica](https://github.com/pragmaticalabs/pragmatica) monorepo
(alongside `core/` — Result/Option/Promise, `integrations/` — consensus, HTTP, DB, serialization, and `jbct/` — the build/lint toolchain):

```
aether/
├── slice-api/           # Slice interface (the app-facing API)
├── slice/               # Slice management
├── node/                # Runtime (AetherNode) + management API routes
├── aether-stream/       # Event streams: replication, failover, consumers
├── aether-deployment/   # Membership, topology, cluster deployment
├── forge/               # Local cluster simulator & dashboard
├── cli/                 # Command-line tools (`aether`)
└── docs/                # Reference, operator, and contributor docs
```

## Dependencies

- Java 25
- Maven 3.9+
- `org.pragmatica-lite:core`
- `org.pragmatica-lite:consensus`
