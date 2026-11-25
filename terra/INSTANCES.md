# Running more than one Terra instance

Terra instances share nothing. Two instances of one application, started from the same blueprint, are two independent JVMs: whatever a slice keeps in memory exists once per instance, and whatever must be common lives in external infrastructure that both instances reach. This page states which is which, what an operator sees when two instances disagree, and how to size the database connections the instances add up to.

It records behaviour at release-1.0.0-rc6 (`e95e953175`). Cache and pub-sub backend selection is being reworked in #3658; where that changes what is written here, this page names it.

## What is per instance

| State | Where it lives | Reaches the other instance? |
| --- | --- | --- |
| `LOCAL` cache (the default mode) | The instance's heap, one bounded map per `cache_name` | No |
| Ephemeral pub-sub (`Publisher<T>`, single-payload subscribers) | The instance's `TerraTopics`, a subscriber list in memory | No |
| Database connection pools | The instance, one pool per datasource and connector type | No, but they add up on the server (see below) |
| Application data in PostgreSQL | The database | Yes |

### The `LOCAL` cache

`mode = "LOCAL"` is the default of a cache section (`CacheConfig.java:17-21`). It builds an `InMemoryCache` (`CacheInterceptorFactory.java:85,95`): a map bounded by `max_entries` (default 10 000) with least-recently-used eviction, whose entries expire `ttl_seconds` (default 300) after they were written (`InMemoryCache.java:19-46`, `:55`). It makes no network call and holds no reference to any other instance. It has the same meaning when the same slice runs on Aether, where `LOCAL` is likewise the node's own heap.

What an operator sees when two instances disagree:

- **A write or invalidation on one instance does not reach the other.** A `WRITE_AROUND` method (`CacheStrategy.java:11`) removes the entry from the cache of the instance that served the call and from no other. The other instance keeps answering from its entry until the entry expires, up to `ttl_seconds` after that entry was written there. Two clients reading one key through two instances can get different answers during that window, and nothing is logged, because nothing failed.
- **Hit rates and latencies differ per instance.** Each instance starts cold and warms from its own traffic; a restart empties it.
- **A cache is only as shared as its `cache_name` inside one process.** Sections with the same `cache_name` share one backend in that process (`CacheInterceptorFactory.java:71`); that sharing stops at the process boundary.

What to do when the answer must be the same on every instance: do not cache that value with `LOCAL`; read it from the database, keep `ttl_seconds` at the staleness you can accept, or use a shared backend.

`DISTRIBUTED` and `TIERED` are the Aether DHT modes. They need a `CacheDhtClient` from the cluster runtime (`CacheInterceptorFactory.java:86-87,99-100`); Terra supplies none, so a section using either fails at provisioning with `Context does not contain CacheDhtClient` (`ProvisioningContext.java:26,45-46`). That refusal is a missing-extension error today, not a typed one naming the runtime; #3658 and #3654 replace it.

### Ephemeral pub-sub

A publication in Terra calls the handlers in the publishing instance's own subscriber list and nothing else (`TerraTopics.java:20,31,54`). The list is filled during startup and cleared at close. There is no persistence, replay, retry, deduplication or cross-process delivery; the same is stated for the single-process contract in the [README](SPEC.md#pub-sub-contract).

What an operator sees when two instances disagree:

- **A message published on instance A is handled by A's subscribers only.** B's subscribers never see it and no error is raised on either side; a publication with no local subscribers succeeds (`TerraTopics.java:54`: `getOrDefault(topic, List.of())`). A subscriber that is expected to see every message of the application therefore sees only the messages published on its own instance, roughly half of them behind a balanced pair.
- **Counters and side effects of a subscriber differ per instance**, in proportion to where the traffic landed.
- **A crash loses what was in flight on that instance**, and a restart finds no backlog, because nothing is stored.

What to do when every instance must see every message, or when a message must survive a restart: pub-sub is the wrong tool in Terra today. Pass the fact through a store both instances read (a row in PostgreSQL), or wait for a shared backend.

### The shared alternatives

Terra ships its resource clients as SPI resources that work from both runtimes, so the runtime and each resource's backend are chosen independently. The shared backends are tickets, not code, at the revision above:

| Need | Resource | State |
| --- | --- | --- |
| Cache, KV and pub-sub reaching every instance | Redis-protocol resource, tested on Valkey too (#2785) | Open; no Redis client exists in the tree |
| Streams (durable, partitioned) | Kafka-backed stream provider (#2099) | Open; Terra's compiler still refuses stream dependencies |
| Durable entities | Database-backed provider (#2100) | Open; Terra's compiler still refuses durable entities |
| Backend selection for cache and pub-sub as one configuration seam, with a typed refusal where a runtime cannot honour a backend | #3658 | Open |

Until they ship, state that two Terra instances must agree on belongs in PostgreSQL, the one shared resource Terra provisions today.

## PostgreSQL connections across instances

Each instance holds its own pools, so the server sees the sum.

```text
peak demand on the server  =  sum over instances, over pools:  max_connections of the pool
available on the server    =  max_connections - superuser_reserved_connections - reserved_connections
```

- **One pool per datasource per connector type per instance.** `pool_scope = "node"` is the default, so slices of one instance whose datasource section resolves to the same connection settings share one pool; `SqlConnector`, `PgSqlConnector` and jOOQ over one datasource are separate pools ([resource reference, "Shared pools"](../aether/docs/slice-developers/resource-reference.md#shared-pools-2973)). A slice with `pool_scope = "slice"` adds a pool of its own.
- **The size of a pool** is `pool_config.max_connections`, default 20, with `min_connections` (default 4) opened at provisioning (`PoolConfig.java:66-67`). The JDBC transport hands it to HikariCP as the maximum pool size (`JdbcSqlConnectorFactory.java:97`), the async transport to the driver pool (`PgSqlConnectorFactory.java:128`).
- **Not counted in a pool, but real:** a pinned session (an advisory lock held through `pinned`) opens one extra dedicated connection while it runs (`SqlConnector.java:22-33`), and a notification listener holds one (`NotificationListenerFactory.java:92`, `maxConnections(1)`). Startup migrations use their own connections and close them before slices are built ([SPEC, "Database startup"](SPEC.md#database-startup)).
- **What `available` is** is the query the node's guard runs: `max_connections` minus `superuser_reserved_connections` minus `reserved_connections` where the server has it (`SharedPoolSupport.java:27-30`).

### What the multiplexer does and does not change

The async PostgreSQL driver has a transaction-mode pool that multiplexes N logical connections over M physical ones, borrowing a physical connection for one query or transaction (`PgTransactionPool.java:21-24`; design in the [pooling spec](../integrations/db/postgres-async/docs/transaction-mode-pooling-spec.md)). It removes the need for an external pooler to serve many concurrent callers from one process: the process opens at most M sessions however many logical connections it hands out (`PgTransactionPool.java:36`, `maxConnections` bounds `size`).

It does not remove the sum across instances. Each instance opens up to its own M, so the server still sees `instances x M`, and `M` is the number to size, not the number of concurrent callers. An external pooler in front of the database is the only thing that makes the server's count independent of the number of instances, and it changes the comparison: the limit to respect is the pooler's.

**Not selected by the resource configuration today.** The driver defaults to session mode (`ConnectibleBuilder.java:156`), and no main-source caller sets `PoolMode.TRANSACTION`: the SPI factory configures only the size, the IO threads and the validation query (`PgSqlConnectorFactory.java:128-130`). A Terra or Aether datasource therefore opens a session-mode pool, where each in-use connection is one physical session, and the formula above holds with `M = max_connections` of that pool. Reporting the multiplexer's own statistics is #3655.

Transaction-mode poolers in front of a datasource that runs migrations are unsupported: the runner refuses such a session and the schema goes `FAILED` ([resource reference](../aether/docs/slice-developers/resource-reference.md), "Connection poolers").

### What warns you, and what does not

After a pool opens, the instance compares its own pools with what the server allows and writes a WARN if the sums of warm-ups or of `max_connections` exceed it, or the server refuses with SQLSTATE `53300`. It never refuses to start (`PoolDemandGuard.java`, class comment and `TEMPLATE`). Two limits matter for a pair of instances:

- **Each instance sees only its own pools.** Two instances that each ask for 60 of 97 available connections raise no warning, though together they ask for 120. The check is per instance; the sum across instances is yours to do.
- **In Terra the warning goes to the log.** Terra supplies no operator-warning sink, so the guard falls back to a log-only sink (`SpiResourceProvider.java:171-174`).

When the server is full, the symptom is a failed or slow connection acquire (the pool waits up to `connection_timeout`, default 30 s) and `53300` in the log of the instance that was refused, usually the one that started last, so a rolling restart that briefly runs both old and new instances is the usual trigger.

### Sizing example: two instances and a rolling replacement

One datasource, one connector type, a PostgreSQL server at its default `max_connections = 100` and `superuser_reserved_connections = 3`, so 97 available.

| Item | Connections |
| --- | --- |
| 2 instances x pool `max_connections` 20 (the default) | 40 |
| A third instance alive during a rolling replacement (two instances may start against one database; see below): +20 | 60 |
| Operators, monitoring, a migration run: reserve | 10 |
| **Peak** | **70 of 97** |

That fits with 27 to spare. To grow the pool, solve for it with the instances you run at the peak of a deploy:

```text
pool max_connections  <=  floor( (available - other clients) / (peak instances x pools per instance) )
                       =  floor( (97 - 10) / (3 x 1) )  =  29
```

Setting `max_connections = 50` with two instances asks for 100 against 97 available: the second instance's pool cannot fill, and the instance that starts last gets connection refusals (SQLSTATE `53300`) when its pool tries to fill; no instance refuses to start for it. Keep `min_connections` at or below what you want held idle, because warm-up is demand too: 2 x 4 = 8 here.

Two instances may start against one database. Their startup migrations are serialised by the migration engine's database fence, not by Terra: a second instance that reaches the fence while another holds it does not wait, its migration fails with `MigrationFencedInDatabase` and its startup fails before any slice is built, so a rolling replacement that overlaps two migrations needs the refused instance started again. The fence covers the migration run only. The guarantee per engine is in [SPEC, "Database startup"](SPEC.md#database-startup).

If one instance needs a pool larger than the server can give to all of them, lower the pool and queue in the application, or put a pooler in front and size against its limits.
