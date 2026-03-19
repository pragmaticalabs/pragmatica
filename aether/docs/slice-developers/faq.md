# Frequently Asked Questions

## How are slices different from actors?

Slices are services, not actors. The programming model is request/response via typed Java interfaces, not message passing.

| Dimension | Actors (Akka/Erlang) | Slices |
|-----------|---------------------|--------|
| Communication | Untyped messages, fire-and-forget | Typed method calls, `Promise<T>` response |
| Interface | Receive handler with pattern matching | Standard Java interface |
| Dependencies | Actor references, message routing | Factory method parameters (dependency injection) |
| State | Mutable private state, behavior switching | Stateless (state in DB/KV-Store) |
| HTTP | Requires adapter layer | Native `routes.toml` declaration |
| Learning curve | Steep (supervision, mailboxes, dead letters) | Minimal (it's a Java interface) |

## How are slices different from microservices?

Slices use the same service model as microservices -- typed interfaces, request/response, independent deployment. The difference is operational: the Aether runtime handles service discovery, load balancing, retry, failover, scaling, and rolling updates. There is no service mesh, no sidecar proxy, no Kubernetes manifests, and no separate infrastructure for each concern.

Think of it as: same architecture, less infrastructure.

## Do I need to learn a new programming paradigm?

No. If you can write a Java interface, you can write a slice. The factory method pattern replaces `@Autowired` / constructor injection. `Promise<T>` replaces `CompletableFuture` or synchronous returns. Everything else -- domain modeling, service boundaries, API contracts, error handling -- is the same.

## How do slices compare to Spring @Service?

| Spring | Aether Slice |
|--------|-------------|
| `@Service` class | `@Slice` interface |
| `@Autowired` constructor | Factory method parameters |
| `@RestController` + `@GetMapping` | `routes.toml` (declarative) |
| `application.yml` | `aether.toml` |
| Return `T` or `ResponseEntity<T>` | Return `Promise<T>` |
| Throw exceptions for errors | Return sealed `Cause` types via `Result<T>` |

## Can I use my existing database / message broker / cache?

Yes. Aether provides resource qualifiers (`@Sql`, `@Http`, etc.) that inject connectors configured in `aether.toml`. You point them at your existing infrastructure. Slices don't impose a specific database, message broker, or cache -- they integrate with what you already have.

## Can I migrate incrementally?

Yes. The [Migration Guide](migration-guide.md) describes three approaches:
- **Wrap existing code** -- run your legacy service inside a slice via `Promise.lift()`
- **Rewrite with JBCT** -- full functional rewrite for new code
- **Peeling pattern** -- start wrapped, refactor layer by layer, working code at every step

Your existing application keeps running throughout. No big bang migration required.

## What happens when a node fails?

Requests to slices on the failed node are automatically retried on other nodes hosting the same slice. The runtime detects node failure, re-elects leader if needed (~2ms), and reconciles the desired state (deploying replacement instances on healthy nodes). No operator intervention required for failures within quorum.

## How does scaling work?

Two-tier system:
1. **Reactive** (1-second interval) -- responds to current CPU, latency, queue depth, error rate
2. **Predictive** (60-second interval) -- ONNX ML model forecasts load from 2-hour history, pre-scales before spikes

Configure per-blueprint via CLI: `aether scale <artifact> --min 2 --max 10 --target-cpu 60`

## What serialization format is used?

- **Inter-node (slice-to-slice)**: Fury binary serialization (high performance, schema evolution)
- **HTTP (external clients)**: JSON via Jackson
- **No serialization code required** -- the annotation processor generates everything from your request/response records
