# Transaction-Mode Connection Pooling Design Specification

**Version:** 1.0
**Target Release:** TBD
**Date:** 2026-03-15
**Status:** DRAFT

---

## Table of Contents

1. [Overview](#1-overview)
2. [Existing Architecture Inventory](#2-existing-architecture-inventory)
3. [Transaction-Mode Multiplexing Design](#3-transaction-mode-multiplexing-design)
4. [Logical Connection](#4-logical-connection)
5. [Physical Connection Pool](#5-physical-connection-pool)
6. [Prepared Statement Migration](#6-prepared-statement-migration)
7. [LISTEN/NOTIFY Pinned Connections](#7-listennotify-pinned-connections)
8. [Transaction Boundary Detection](#8-transaction-boundary-detection)
9. [Configuration](#9-configuration)
10. [Thread Safety Model](#10-thread-safety-model)
11. [Error Handling](#11-error-handling)
12. [Scalability Advantages over PgBouncer](#12-scalability-advantages-over-pgbouncer)
13. [Migration Path](#13-migration-path)
14. [Testing Strategy](#14-testing-strategy)
15. [Open Questions](#15-open-questions)
16. [References](#16-references)

---

## 1. Overview

### 1.1 Problem Statement

The current `PgConnectionPool` operates in **session mode**: when a caller obtains a `Connection` via `getConnection()`, a physical PostgreSQL backend connection is reserved for the entire lifetime of that logical session -- from acquisition through `close()`. The connection is unavailable to other callers even during idle periods between queries.

This creates a direct 1:1 coupling between concurrent application-level operations and backend connections:

| Scenario | Session Mode | Transaction Mode |
|----------|-------------|-----------------|
| 1000 concurrent HTTP requests, each issuing 1 query | 1000 backend connections needed | 20 backend connections sufficient |
| 500 concurrent requests, 10ms query, 200ms total request | 500 connections, each idle 95% of the time | ~25 connections at full utilization |
| Connection limit on PostgreSQL (default: 100) | Hard ceiling at 100 concurrent operations | 100 connections serve thousands of operations |

PostgreSQL forks a backend process per connection. Each backend process consumes ~5-10 MB of RAM. At 1000 connections, that is 5-10 GB of PostgreSQL server memory consumed purely by connection overhead, regardless of actual query load.

**Transaction-mode pooling** solves this by multiplexing many logical operations over few physical connections. A physical connection is borrowed only for the duration of a single query or transaction, then immediately returned to the pool.

### 1.2 Goals

- **REQ-001:** Implement transaction-mode connection pooling where physical connections are borrowed per-query or per-transaction and returned on completion.
- **REQ-002:** Maintain full backward compatibility -- session mode remains the default behavior.
- **REQ-003:** Handle prepared statement lifecycle across physical connection switches transparently.
- **REQ-004:** Support LISTEN/NOTIFY via pinned connections that bypass transaction-mode multiplexing.
- **REQ-005:** Provide configuration-driven pool mode selection (session vs. transaction).
- **REQ-006:** Preserve the existing `Connection` and `QueryExecutor` interfaces -- no breaking API changes.

### 1.3 Non-Goals

- Statement-level pooling (connection returned after every individual statement, even mid-transaction). This is too aggressive and breaks transactional guarantees.
- Global prepared statement deduplication across physical connections (over-engineering for the first iteration).
- Connection routing or read-replica support.
- Wire-protocol-level proxying (we are a driver, not a proxy).

---

## 2. Existing Architecture Inventory

All types referenced below are in `integrations/db/postgres-async/src/main/java/org/pragmatica/postgres/`.

### 2.1 Connection Hierarchy

| Type | File | Role |
|------|------|------|
| `QueryExecutor` | `net/QueryExecutor.java` | Base interface. `script()`, `query()`, `completeQuery()`, `completeScript()`. |
| `Connection` | `net/Connection.java` | Extends `QueryExecutor`. Adds `prepareStatement()`, `subscribe()`, `begin()`, `close()`, `isConnected()`. |
| `Transaction` | `net/Transaction.java` | Extends `QueryExecutor`. Adds `commit()`, `rollback()`, `close()`, `getConnection()`, `begin()` (savepoints). |
| `Connectible` | `net/Connectible.java` | Extends `QueryExecutor`. Adds `getConnection()`, `close()`. Connection source abstraction. |
| `PgConnectible` | `PgConnectible.java` | Abstract base implementing `Connectible`. Implements `script()` and `query()` by acquiring a connection, executing, then closing. |
| `PgConnectionPool` | `PgConnectionPool.java` | Session-mode pool. Manages a `Queue<PgConnection>` of idle connections and a `Queue<Promise<Connection>>` of pending requestors. Uses `ReentrantLock`. |
| `PgConnection` | `PgConnection.java` | Physical connection to a PostgreSQL backend. Owns a `ProtocolStream`, a `DataConverter`, and a `LinkedHashMap` LRU statement cache. Contains inner class `PgPreparedStatement` and `PgConnectionTransaction`. |
| `PgDatabase` | `PgDatabase.java` | Non-pooled `Connectible`. Creates a new `PgConnection` per `getConnection()` call. |

### 2.2 Protocol Layer

| Type | File | Role |
|------|------|------|
| `ProtocolStream` | `ProtocolStream.java` | Interface for PG wire protocol v3 message exchange. `connect()`, `authenticate()`, `send()` (multiple overloads), `subscribe()`, `close()`. |
| `PgProtocolStream` | `PgProtocolStream.java` | Abstract implementation. Manages a `ConcurrentLinkedDeque<PendingQuery>` pipeline. Handles `ReadyForQuery`, `ErrorResponse`, `DataRow`, `RowDescription`, `CommandComplete` message dispatch. Contains subscription map. |
| `NettyPgProtocolStream` | `net/netty/NettyPgProtocolStream.java` | Netty `ChannelHandler`-based implementation. Owns a `ChannelHandlerContext`. SSL support. |

### 2.3 Prepared Statement Cache

`PgConnection` maintains a per-connection LRU cache:

```
LinkedHashMap<String/*sql*/, PgPreparedStatement> statementCache
```

- Capacity: `maxStatements` (configurable, default 20).
- Eviction: LRU via `removeEldestEntry()`. Evicted statements are closed on the server (`Close` message).
- Lookup: `prepareStatement(sql, types)` checks cache first; on miss, sends `Parse` to backend.
- Stale detection: errors `42P05` (duplicate prepared statement) and `26000` (invalid statement name) trigger cache eviction + retry.

### 2.4 Connection Release Mechanism

`PgConnection.close()` triggers `onRelease` callback (set by `PgConnectionPool`). The pool's `release()` method either hands the connection to a pending requestor or returns it to the idle queue. The connection itself is NOT closed -- it remains alive for reuse.

### 2.5 Transaction Lifecycle

`PgConnection.begin()` sends `BEGIN` via simple query protocol and returns a `PgConnectionTransaction`. The transaction delegates all queries to the owning `PgConnection`. `commit()` sends `COMMIT`; `rollback()` sends `ROLLBACK`. Nested transactions use `SAVEPOINT`.

---

## 3. Transaction-Mode Multiplexing Design

### 3.1 Core Concept

Split the current `PgConnection` role into two distinct types:

1. **`LogicalConnection`** -- user-facing, lightweight, no physical backend. Implements `Connection`. Created on demand, cheap to allocate. Holds the user's query queue and prepared statement registry.

2. **`PhysicalConnection`** -- wraps `PgConnection` (the existing class). Represents an actual PostgreSQL backend connection. Managed exclusively by the pool.

The pool manages a set of physical connections. Logical connections borrow a physical connection when they need to execute work and return it when the work completes.

### 3.2 Borrowing Rules

| Scenario | Borrow Point | Return Point |
|----------|-------------|-------------|
| Single query (`query()`, `completeQuery()`) | Before sending Parse/Bind/Execute | After `ReadyForQuery(Idle)` received |
| Script (`script()`, `completeScript()`) | Before sending Query message | After final `ReadyForQuery(Idle)` received |
| Transaction (`begin()` ... `commit()`/`rollback()`) | On `begin()` | On `commit()` or `rollback()` completion (when `ReadyForQuery(Idle)` received) |
| Subscription (`subscribe()`) | On `subscribe()` | Never (pinned -- see Section 7) |

### 3.3 State Machine

```
LogicalConnection States:
    IDLE        -- no physical connection, ready for new work
    BORROWING   -- requesting physical connection from pool
    ACTIVE      -- has physical connection, executing query/transaction
    IN_TX       -- has physical connection, inside explicit transaction
    PINNED      -- has physical connection, pinned (LISTEN/NOTIFY)
    CLOSED      -- terminal state

Transitions:
    IDLE       --[query/script]--> BORROWING
    IDLE       --[begin()]------> BORROWING
    IDLE       --[subscribe()]---> BORROWING
    IDLE       --[close()]------> CLOSED
    BORROWING  --[got physical]-> ACTIVE (for query/script)
    BORROWING  --[got physical]-> IN_TX  (for begin)
    BORROWING  --[got physical]-> PINNED (for subscribe)
    BORROWING  --[pool failure]-> IDLE   (propagate error to caller)
    ACTIVE     --[ReadyForQuery(Idle)]--> IDLE (return physical to pool)
    ACTIVE     --[error]---------> IDLE  (return physical to pool, propagate error)
    IN_TX      --[query/script]--> IN_TX (reuse same physical)
    IN_TX      --[begin()]-------> IN_TX (savepoint, reuse same physical)
    IN_TX      --[commit/rollback + ReadyForQuery(Idle)]--> IDLE (return physical)
    IN_TX      --[error]---------> IN_TX (rollback still pending)
    PINNED     --[unlisten()]----> IDLE  (return physical to pool)
    PINNED     --[close()]-------> CLOSED (close physical)
```

### 3.4 Class Diagram (Textual)

```
    Connectible
        |
    PgConnectible (abstract)
        |
    +---+---+
    |       |
PgConnectionPool          PgTransactionPool (NEW)
(session-mode)            (transaction-mode)
    |                         |
    +--- manages -->          +--- manages -->
    Queue<PgConnection>       Queue<PgConnection>  (physical connections)
                              |
                              +--- creates -->
                              LogicalConnection (NEW, implements Connection)
                                  |
                                  +--- borrows/returns -->
                                  PgConnection (physical)
```

---

## 4. Logical Connection

### 4.1 Class: `LogicalConnection`

Implements `Connection`. This is what callers receive from `PgTransactionPool.getConnection()`.

**Fields:**

| Field | Type | Purpose |
|-------|------|---------|
| `pool` | `PgTransactionPool` | Back-reference for borrowing/returning physical connections |
| `state` | `LogicalState` (enum) | Current state machine state |
| `physical` | `PgConnection` | Currently borrowed physical connection, or null |
| `statementRegistry` | `Map<String, StatementEntry>` | SQL-to-statement-name mapping with metadata (see Section 6) |
| `dataConverter` | `DataConverter` | Shared data converter instance |
| `lastPhysicalId` | `int` | Identity hash of last borrowed physical connection, for detecting backend switches |

### 4.2 Query Execution Flow

For a single query (`query()` or `completeQuery()`):

```
1. Caller invokes logicalConnection.query(sql, params)
2. LogicalConnection requests physical connection from pool
   -> pool.borrowConnection() returns Promise<PgConnection>
3. On success: physical connection assigned
   a. Check if prepared statement exists on this physical connection
      - If SQL is in statementRegistry AND lastPhysicalId matches: reuse
      - If SQL is in statementRegistry AND lastPhysicalId differs: re-prepare (Section 6)
      - If SQL not in registry: prepare fresh
   b. Execute query via physical.query(...)
4. On ReadyForQuery(Idle): return physical connection to pool
   -> pool.returnConnection(physical)
   -> Set physical = null, state = IDLE
5. Complete caller's Promise with result
```

### 4.3 Transaction Execution Flow

```
1. Caller invokes logicalConnection.begin()
2. LogicalConnection requests physical connection from pool
3. On success: send BEGIN via physical connection
4. Return LogicalTransaction (wraps this LogicalConnection)
5. All subsequent queries on the LogicalTransaction reuse the SAME physical
6. On commit()/rollback(): send COMMIT/ROLLBACK
7. On ReadyForQuery(Idle) after COMMIT/ROLLBACK: return physical to pool
```

### 4.4 Interface Compliance

`LogicalConnection` implements `Connection` exactly:

```java
public sealed interface LogicalState {
    record Idle() implements LogicalState {}
    record Borrowing(Promise<PgConnection> pending) implements LogicalState {}
    record Active(PgConnection physical) implements LogicalState {}
    record InTransaction(PgConnection physical) implements LogicalState {}
    record Pinned(PgConnection physical) implements LogicalState {}
    record Closed() implements LogicalState {}
}
```

The `close()` method on `LogicalConnection`:
- If `IDLE`: transition to `CLOSED`. No physical connection to return.
- If `ACTIVE` or `IN_TX`: [ASSUMPTION] wait for current operation to complete, then return physical and transition to `CLOSED`.
- If `PINNED`: send `UNLISTEN`, return physical, transition to `CLOSED`.

---

## 5. Physical Connection Pool

### 5.1 Class: `PgTransactionPool`

Extends `PgConnectible`. Replaces `PgConnectionPool` when transaction mode is selected.

**Differences from `PgConnectionPool`:**

| Aspect | `PgConnectionPool` (session mode) | `PgTransactionPool` (transaction mode) |
|--------|----------------------------------|---------------------------------------|
| `getConnection()` returns | `PgConnection` directly | `LogicalConnection` (lightweight wrapper) |
| Physical connection lifetime | Held by caller until `close()` | Borrowed per-query or per-transaction |
| Connection release trigger | `Connection.close()` callback | `ReadyForQuery(Idle)` after query/tx |
| Concurrent logical connections | Limited by `maxConnections` | Unlimited (physical connections are the bottleneck) |
| Physical connection reuse | Sequential (one user at a time) | Rapid multiplexing across logical connections |

### 5.2 Internal Pool Operations

```java
// Borrow a physical connection for a logical connection's use
Promise<PgConnection> borrowConnection()

// Return a physical connection after use
void returnConnection(PgConnection physical)
```

**`borrowConnection()` logic:**

```
1. Lock guard
2. If pool is closing: return failure
3. Poll idle queue for first alive connection
   - If found: return immediately via Promise.success()
4. If size < maxConnections:
   - Increment size
   - Create new physical connection (obtainStream -> connect -> authenticate -> validate)
   - Return via Promise
5. Else: enqueue a Promise<PgConnection> in pending queue, return it
   - Will be fulfilled when a connection is returned
6. Unlock guard
```

**`returnConnection()` logic:**

```
1. Lock guard
2. If pending queue is non-empty:
   - Poll next pending Promise
   - Fulfill it with the returned connection (outside lock)
3. Else: add connection to idle queue
4. Check if pool is closing
5. Unlock guard
```

This is structurally identical to the existing `PgConnectionPool.release()` mechanism but operates at the physical connection level rather than being triggered by `Connection.close()`.

### 5.3 Idle Connection Management

Physical connections returned to the pool sit idle until borrowed again. Idle timeout eviction (future enhancement):

- [TBD] Configurable idle timeout (e.g., 30 seconds).
- [TBD] Background timer to evict and close connections idle beyond threshold.
- For initial implementation: no idle timeout. Connections remain in pool until pool closure.

### 5.4 `getConnection()` Implementation

```java
@Override
public Promise<Connection> getConnection() {
    // In transaction mode, getConnection() returns a LogicalConnection
    // The logical connection borrows physical connections on demand
    var logical = new LogicalConnection(this, dataConverter, maxStatements);
    return Promise.success(logical);
}
```

Note: `getConnection()` does NOT borrow a physical connection. The `LogicalConnection` is returned immediately. Physical connections are borrowed lazily on first query or `begin()`.

### 5.5 Direct Query Execution

`PgConnectible` already implements `script()` and `query()` by calling `getConnection()`, executing, then `close()`. In transaction mode, this naturally becomes:

```
getConnection() -> LogicalConnection (instant, no physical)
    -> query() -> borrow physical -> execute -> return physical
    -> close() -> LogicalConnection closed (no-op if idle)
```

This is the fast path for single queries -- borrow, execute, return -- with no explicit transaction overhead.

---

## 6. Prepared Statement Migration

### 6.1 The Problem

In session mode, prepared statements live for the duration of the physical connection and are cached in `PgConnection.statementCache`. When a logical connection switches physical backends between transactions, the new physical connection does not have the same prepared statements.

PgBouncer's transaction mode does not support prepared statements at all. This driver can do better.

### 6.2 Recommended Approach: Lazy Re-Preparation with Registry

Each `LogicalConnection` maintains a **statement registry** -- a mapping of SQL text to statement metadata:

```java
record StatementEntry(
    String sql,           // Original SQL text
    String statementName, // Server-side statement name (e.g., "s-42")
    Oid[] parameterTypes, // Parameter type OIDs
    int physicalId        // Identity of the physical connection where this statement was last prepared
)
```

The registry is stored in the `LogicalConnection`, NOT in the physical `PgConnection`.

### 6.3 Execution Flow with Statement Migration

```
1. LogicalConnection.query(sql, params):
2. Borrow physical connection (physicalId = System.identityHashCode(physical))
3. Check statementRegistry for sql:
   a. MISS (first time):
      - Generate new statement name via NameSequence
      - Send Parse to physical connection
      - Store StatementEntry in registry
      - Continue with Bind/Execute

   b. HIT, same physicalId:
      - Statement exists on this backend
      - Reuse directly (Bind/Execute with existing name)

   c. HIT, different physicalId:
      - Statement was prepared on a DIFFERENT physical connection
      - Generate new statement name (old name may collide on new backend)
      - Send Parse to physical connection with same SQL
      - Update StatementEntry with new name and new physicalId
      - Continue with Bind/Execute
4. Return physical connection on ReadyForQuery(Idle)
```

### 6.4 Statement Name Generation

Each `LogicalConnection` has its own `NameSequence` instance (like the existing one in `PgConnection`). Statement names are scoped to the logical connection's namespace.

Since physical connections are shared, statement names from different logical connections could collide on the same physical backend. To prevent this, statement names are prefixed with a unique logical connection identifier:

```
Format: "l<logicalId>-s-<counter>"
Example: "l7-s-1", "l7-s-2", "l12-s-1"
```

Where `logicalId` is a monotonically increasing integer assigned at `LogicalConnection` creation.

### 6.5 Statement Cleanup

When a logical connection is closed:

- **No cleanup messages are sent to physical connections.** The statements will be cleaned up when the physical connection is eventually closed, or they will be overwritten by future Parse messages with different names.
- [ASSUMPTION] PostgreSQL backend memory for orphaned prepared statements is acceptable given the naming scheme prevents collisions.
- The `LogicalConnection.statementRegistry` is garbage-collected with the logical connection.

Alternative considered and rejected: sending `Close` for each registered statement on `LogicalConnection.close()`. This would require borrowing a physical connection solely for cleanup, which defeats the purpose of lightweight logical connections.

### 6.6 Interaction with Physical Connection's Statement Cache

In transaction mode, the physical `PgConnection.statementCache` is **disabled** (set to `maxStatements = 0`). The logical connection's registry handles statement tracking instead. This avoids conflicts between two caching layers.

When creating physical connections for the transaction-mode pool, pass `maxStatements = 0` to `PgConnection` constructor.

### 6.7 Stale Statement Handling

The existing retry mechanism in `PgConnection.handleQueryFailure()` handles `42P05` (duplicate prepared statement) and `26000` (invalid statement name). In transaction mode:

- **42P05** (duplicate): A statement name already exists on the physical backend from a different logical connection. The `LogicalConnection` should evict the entry from its registry, generate a new name, and retry with `Parse`.
- **26000** (invalid name): The statement was deallocated (e.g., physical connection was reset). The `LogicalConnection` should evict and re-prepare.

The retry logic in `LogicalConnection` mirrors the existing pattern in `PgConnection.handleQueryFailure()` but operates on the logical registry.

### 6.8 Registry Size Limit

The statement registry uses the same LRU eviction strategy as the current `PgConnection.statementCache`:

- `LinkedHashMap` with access-order and `removeEldestEntry()`.
- Capacity: `maxStatements` from configuration.
- Evicted entries: no server-side cleanup needed (names are unique, won't collide).

---

## 7. LISTEN/NOTIFY Pinned Connections

### 7.1 The Problem

`LISTEN` registers interest in a notification channel on a specific PostgreSQL backend process. Notifications are delivered asynchronously on that connection. If the connection is returned to the pool and reused by another logical connection, notifications would be delivered to the wrong consumer.

### 7.2 Design: Connection Pinning

When `LogicalConnection.subscribe()` is called:

```
1. Borrow a physical connection from pool
2. Send "LISTEN <channel>" via simple query protocol
3. Register notification callback on the physical connection's ProtocolStream
4. Transition LogicalConnection to PINNED state
5. The physical connection is NOT returned to the pool
6. All notifications on this channel are routed to the callback
7. On unlisten():
   a. Send "UNLISTEN <channel>"
   b. Unregister callback from ProtocolStream
   c. Return physical connection to pool
   d. Transition LogicalConnection to IDLE
```

### 7.3 Pinned Connection Constraints

- A pinned `LogicalConnection` holds its physical connection for the duration of the subscription.
- Queries and transactions on a pinned `LogicalConnection` reuse the pinned physical connection (no additional borrow needed).
- Multiple subscriptions on the same logical connection share the same pinned physical connection.
- The pinned physical connection counts against `maxConnections`.

### 7.4 Multiple Subscriptions

If a `LogicalConnection` is already `PINNED` and `subscribe()` is called again:

- Reuse the existing pinned physical connection.
- Send `LISTEN` for the new channel.
- Track subscription count. Transition back to `IDLE` only when ALL subscriptions are `unlisten()`-ed.

---

## 8. Transaction Boundary Detection

### 8.1 Mechanism

Transaction boundaries are detected by observing the `ReadyForQuery` message from the PostgreSQL backend. The `ReadyForQuery` message includes a transaction status indicator byte:

| Byte | Meaning |
|------|---------|
| `I` | Idle (not in a transaction block) |
| `T` | In a transaction block |
| `E` | In a failed transaction block |

**Current state:** The existing `ReadyForQuery` record in the codebase is a simple empty record with no fields:

```java
public record ReadyForQuery() implements BackendMessage {
    public static final ReadyForQuery INSTANCE = new ReadyForQuery();
}
```

### 8.2 Required Change: Parse Transaction Status

**REQ-007:** Modify `ReadyForQuery` and `ReadyForQueryDecoder` to capture the transaction status byte.

```java
public record ReadyForQuery(TransactionStatus status) implements BackendMessage {

    public enum TransactionStatus {
        IDLE,           // 'I'
        IN_TRANSACTION, // 'T'
        FAILED          // 'E'
    }
}
```

The `PgProtocolStream.gotMessage()` handler for `ReadyForQuery` currently completes the head of the pipeline. In transaction mode, the pool must additionally check: if `status == IDLE`, the physical connection can be returned.

### 8.3 Integration Point

The `LogicalConnection` installs a callback on the physical connection's protocol stream to observe `ReadyForQuery` messages. When `ReadyForQuery(IDLE)` arrives:

- If state is `ACTIVE`: return physical to pool, transition to `IDLE`.
- If state is `IN_TX`: this means the transaction ended (COMMIT/ROLLBACK completed). Return physical, transition to `IDLE`.
- If state is `PINNED`: do not return. Subscription keeps the connection.

When `ReadyForQuery(IN_TRANSACTION)` or `ReadyForQuery(FAILED)` arrives:

- Do not return the physical connection. The transaction is still open.

---

## 9. Configuration

### 9.1 Pool Mode Selection

Add a `poolMode` field to `ConnectibleConfiguration`:

```java
public enum PoolMode {
    SESSION,      // Current behavior (default)
    TRANSACTION   // New transaction-mode pooling
}
```

Builder method:

```java
public ConnectibleBuilder poolMode(PoolMode poolMode) {
    properties.poolMode = poolMode;
    return this;
}
```

### 9.2 Configuration Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `poolMode` | `PoolMode` | `SESSION` | Pool operating mode |
| `maxConnections` | `int` | `20` | Maximum physical backend connections |
| `maxStatements` | `int` | `20` | Statement cache/registry size per connection (session) or per logical connection (transaction) |
| `validationQuery` | `String` | `null` | Query to validate connections before use |
| `hostname` | `String` | `localhost` | PostgreSQL host |
| `port` | `int` | `5432` | PostgreSQL port |
| `username` | `String` | - | Database user |
| `password` | `String` | - | Database password |
| `database` | `String` | - | Database name |
| `useSsl` | `boolean` | `false` | Enable SSL |
| `encoding` | `String` | `utf-8` | Character encoding |
| `ioThreads` | `int` | `max(availableProcessors, 8)` | Netty EventLoop threads |

### 9.3 Builder Integration

The `ConnectibleBuilder.pool()` method currently always creates `PgConnectionPool`. Change to:

```java
public Connectible pool() {
    return switch (properties.poolMode()) {
        case SESSION     -> new PgConnectionPool(properties, obtainStream());
        case TRANSACTION -> new PgTransactionPool(properties, obtainStream());
    };
}
```

### 9.4 Aether Integration

For `PgAsyncSqlConnector` or equivalent Aether integration points, the pool mode should be configurable via TOML or equivalent configuration mechanism. [TBD] Exact Aether config format.

---

## 10. Thread Safety Model

### 10.1 Logical Connection: Single-User

A `LogicalConnection` is designed for use by a single caller at a time, consistent with the existing `Connection` contract:

> "Concurrent using of implementations is impossible. Connection implementations are never thread-safe."

The `LogicalConnection` itself does NOT need internal synchronization. It is used within a single `Promise` chain.

### 10.2 Physical Connection Pool: Synchronized

`PgTransactionPool` uses the same `ReentrantLock` pattern as `PgConnectionPool`:

```java
private final Lock guard = new ReentrantLock();
private final Queue<Promise<PgConnection>> pending = new ArrayDeque<>();
private final Queue<PgConnection> idle = new ArrayDeque<>();
```

All access to `pending`, `idle`, and `size` is guarded by `lock`.

### 10.3 Physical Connection: Single-User (Enforced by Pool)

A `PgConnection` is used by at most one `LogicalConnection` at a time. The pool guarantees this by never lending the same physical connection to two borrowers simultaneously.

### 10.4 EventLoop Affinity

Physical connections are pinned to Netty `EventLoop` threads. The `LogicalConnection` does not have EventLoop affinity -- it delegates to whichever EventLoop the borrowed physical connection uses. Since `Promise` continuations run on the completing thread, the query execution path naturally stays on the physical connection's EventLoop.

---

## 11. Error Handling

### 11.1 Physical Connection Failure During Query

If the physical connection dies mid-query:

```
1. ProtocolStream.gotError() fires
2. Promise chain propagates SqlError to LogicalConnection's caller
3. Physical connection is NOT returned to pool (it's dead)
4. Pool decrements size
5. LogicalConnection transitions to IDLE (physical = null)
```

### 11.2 Physical Connection Failure During Transaction

```
1. Error propagates to PgConnectionTransaction.handleException()
2. Existing behavior: automatic ROLLBACK attempt
3. If ROLLBACK succeeds: return physical to pool, transition to IDLE
4. If ROLLBACK fails (connection dead): discard physical, decrement pool size
5. LogicalConnection transitions to IDLE
```

### 11.3 Pool Exhaustion

When all physical connections are in use and `pending` queue is non-empty:

- Callers block (via unresolved `Promise`) until a connection is returned.
- [TBD] Configurable timeout for borrow operations. After timeout, fail the Promise with a pool exhaustion error.

### 11.4 Stale Connection Detection

Before lending a physical connection, the pool checks `isConnected()` (via `NettyPgProtocolStream.isConnected()` which checks `ctx.channel().isOpen()`). Dead connections are discarded and `size` is decremented.

### 11.5 New Error Types

Add to `SqlError`:

```java
record PoolExhausted(String message) implements SqlError {}
record LogicalConnectionClosed(String message) implements SqlError {}
```

---

## 12. Scalability Advantages over PgBouncer

### 12.1 Comparison Matrix

| Aspect | PgBouncer | Pragmatica Transaction Pool |
|--------|-----------|---------------------------|
| **Threading** | Single-threaded event loop | Multi-threaded (Netty EventLoopGroup, configurable thread count) |
| **Network hops** | App -> PgBouncer -> PostgreSQL (2 hops, extra latency) | App -> PostgreSQL (1 hop, direct) |
| **Prepared statements** | Not supported in transaction mode | Supported via lazy re-preparation |
| **Deployment** | Separate process, separate config, separate monitoring | In-process, zero deployment overhead |
| **Memory overhead** | Separate process memory | Shared JVM heap, minimal per-logical-connection overhead |
| **Protocol parsing** | Parses PG wire protocol for multiplexing | Already parsing for the driver -- no additional overhead |
| **Configuration** | Separate `pgbouncer.ini` | Same config as the application |
| **TLS** | Must configure TLS on both PgBouncer links | Single TLS connection to PostgreSQL |
| **Observability** | Separate monitoring target | Same JVM metrics (Micrometer integration available) |

### 12.2 Performance Characteristics

**Single-threaded PgBouncer bottleneck:**
PgBouncer processes all client connections on a single thread. Under high concurrency (thousands of clients), the single thread becomes the bottleneck -- not PostgreSQL itself. The Pragmatica driver uses Netty's EventLoopGroup (default: `max(availableProcessors, 8)` threads), distributing connection handling across cores.

**Zero-copy advantage:**
When the driver directly manages multiplexing, query results flow from Netty's ByteBuf through the decoder directly to the caller. With PgBouncer, results are decoded by PgBouncer, copied to a client-side buffer, sent over a second TCP connection, and decoded again by the driver.

**Prepared statement support:**
PgBouncer cannot support prepared statements in transaction mode because it has no way to track which statements exist on which backend. The Pragmatica driver maintains per-logical-connection registries and transparently re-prepares when backends switch. This is the single largest advantage for applications that rely on prepared statements (which is most production applications).

---

## 13. Migration Path

### 13.1 Backward Compatibility

Session mode remains the default. No existing code changes required.

| Existing Usage | Effect |
|----------------|--------|
| `builder.pool()` | Creates `PgConnectionPool` (session mode) -- unchanged |
| `builder.plain()` | Creates `PgDatabase` (non-pooled) -- unchanged |
| `Connection` interface consumers | No API changes -- `LogicalConnection` implements `Connection` |
| Direct `PgConnection` usage | Still works in session mode. Not exposed in transaction mode. |

### 13.2 Opt-In Activation

```java
var pool = new NettyConnectibleBuilder()
    .hostname("localhost")
    .port(5432)
    .username("app")
    .password("secret")
    .database("mydb")
    .maxConnections(20)
    .poolMode(PoolMode.TRANSACTION)  // NEW -- opt-in
    .pool();
```

### 13.3 Behavioral Differences Callers Must Be Aware Of

| Behavior | Session Mode | Transaction Mode |
|----------|-------------|-----------------|
| `getConnection()` blocks on pool exhaustion | Yes (blocks until a connection is released) | No (returns immediately with LogicalConnection; blocking deferred to first query) |
| `prepareStatement()` lifetime | Connection session | Logical connection lifetime; re-prepared on backend switch |
| `subscribe()` behavior | Connection-scoped | Pins a physical connection for subscription duration |
| Connection-scoped SET commands | Persist for session | Persist only for current transaction. Lost on backend switch. |

### 13.4 SET Command Warning

[ASSUMPTION] Applications using connection-scoped `SET` commands (e.g., `SET search_path`, `SET timezone`) must execute them inside explicit transactions in transaction mode. Otherwise, the settings apply only until the physical connection is returned to the pool, and a subsequent borrow may yield a different physical connection with default settings.

This matches PgBouncer's documented limitation. Future enhancement: support "reset query" that runs on connection return (e.g., `DISCARD ALL` or `RESET ALL`).

---

## 14. Testing Strategy

### 14.1 Unit Tests

| Test Class | Coverage |
|-----------|---------|
| `LogicalConnectionTest` | State machine transitions: IDLE -> BORROWING -> ACTIVE -> IDLE, IDLE -> IN_TX -> IDLE, error paths, double-close, close-while-active |
| `LogicalConnectionStatementRegistryTest` | Registry hit/miss, LRU eviction, physicalId mismatch triggering re-prepare, name collision avoidance |
| `PgTransactionPoolTest` | Borrow/return lifecycle, pool exhaustion queueing, concurrent borrow, connection failure during borrow, pool close draining |

### 14.2 Integration Tests (Testcontainers + PostgreSQL)

| Test | Scenario |
|------|----------|
| `transactionModeBasicQuery_success` | Single query borrows and returns physical connection |
| `transactionModeTransaction_borrowHeldDuringTx` | Physical connection held across BEGIN...COMMIT |
| `transactionModeTransaction_returnedAfterCommit` | Physical connection returned after COMMIT |
| `transactionModeTransaction_returnedAfterRollback` | Physical connection returned after ROLLBACK |
| `transactionModePreparedStatement_rePreparedOnBackendSwitch` | Execute prepared statement, return connection, borrow different connection, verify re-preparation |
| `transactionModePreparedStatement_cachedOnSameBackend` | Borrow same physical twice, verify statement reused without re-parse |
| `transactionModeStaleStatement_retryOnDuplicateName` | Trigger 42P05, verify retry with new name |
| `transactionModeConcurrentLogicalConnections_multiplexed` | N logical connections share M physical connections (N >> M) |
| `transactionModeListenNotify_pinnedConnection` | Subscribe pins connection, notifications delivered, unlisten releases |
| `transactionModePoolExhaustion_pendingResolvedOnReturn` | Exhaust pool, verify pending borrows resolve when connections are returned |
| `transactionModeNestedTransaction_savepointOnSamePhysical` | Nested BEGIN/SAVEPOINT uses same physical |
| `transactionModeSetCommand_lostOnBackendSwitch` | SET inside tx persists; SET outside tx may not survive backend switch |

### 14.3 Stress Tests

| Test | Scenario |
|------|----------|
| `transactionModeHighConcurrency_1000LogicalOn20Physical` | 1000 concurrent queries through 20 physical connections, verify all complete without error |
| `transactionModeConnectionChurn_rapidBorrowReturn` | Rapid borrow/return cycles, verify no connection leaks |
| `transactionModeUnderLoad_preparedStatementStability` | High concurrency with prepared statements, verify no 42P05 storms |

### 14.4 Test Naming Convention

Per project convention: `methodName_scenario_expectation()`

---

## 15. Open Questions

| ID | Question | Impact |
|----|----------|--------|
| **OQ-001** | Should `LogicalConnection.getConnection()` return immediately or borrow eagerly? Current design: returns immediately. Alternative: borrow eagerly to fail fast on pool exhaustion. | API behavior |
| **OQ-002** | Should physical connections run `DISCARD ALL` or `RESET ALL` on return to pool? This prevents leaked session state (SET, temp tables). Cost: extra round trip per return. | Correctness vs. performance |
| **OQ-003** | Should there be a borrow timeout? If so, what default? PgBouncer default: 120 seconds (`server_connect_timeout`). | Error handling |
| **OQ-004** | Should the statement registry track `Columns` metadata (column types/names) to avoid re-describing on backend switch? This would save a Describe round trip. | Performance |
| **OQ-005** | Should `ReadyForQuery` status byte parsing be done unconditionally (even in session mode)? It adds minimal overhead and enables future diagnostics. | Scope of ReadyForQuery change |
| **OQ-006** | How should `PgAsyncSqlConnector` (Aether integration) expose pool mode configuration? Via TOML? Via builder API? | Aether integration |

---

## 16. References

### PostgreSQL Protocol Documentation

- [Frontend/Backend Protocol - Message Flow](https://www.postgresql.org/docs/current/protocol-flow.html) - ReadyForQuery transaction status indicator, extended query protocol lifecycle
- [Frontend/Backend Protocol - Message Formats](https://www.postgresql.org/docs/current/protocol-message-formats.html) - ReadyForQuery message format (includes transaction status byte)
- [PREPARE Statement](https://www.postgresql.org/docs/current/sql-prepare.html) - Server-side prepared statement semantics
- [LISTEN/NOTIFY](https://www.postgresql.org/docs/current/sql-listen.html) - Notification channel semantics and connection affinity

### PgBouncer Reference

- [PgBouncer Documentation](https://www.pgbouncer.org/config.html) - pool_mode settings (session, transaction, statement), server_reset_query, max_client_conn, default_pool_size
- [PgBouncer Features](https://www.pgbouncer.org/features.html) - Known limitations of transaction mode (no prepared statements, no LISTEN/NOTIFY, no SET persistence)

### Internal References

- `integrations/db/postgres-async/src/main/java/org/pragmatica/postgres/PgConnectionPool.java` - Current session-mode pool implementation
- `integrations/db/postgres-async/src/main/java/org/pragmatica/postgres/PgConnection.java` - Physical connection with statement cache, transaction support
- `integrations/db/postgres-async/src/main/java/org/pragmatica/postgres/PgProtocolStream.java` - Wire protocol handler, ReadyForQuery dispatch
- `integrations/db/postgres-async/src/main/java/org/pragmatica/postgres/net/netty/NettyPgProtocolStream.java` - Netty channel integration
- `integrations/db/postgres-async/src/main/java/org/pragmatica/postgres/net/ConnectibleBuilder.java` - Builder and ConnectibleConfiguration
- `integrations/db/postgres-async/src/main/java/org/pragmatica/postgres/SqlError.java` - Error type hierarchy

---

## Appendix A: Implementation Order

Recommended phased implementation:

### Phase 1: Foundation (ReadyForQuery + Pool Shell)

1. Modify `ReadyForQuery` to capture transaction status byte
2. Modify `ReadyForQueryDecoder` to parse the status byte
3. Create `PoolMode` enum
4. Create `PgTransactionPool` skeleton (extends `PgConnectible`)
5. Wire `PoolMode` into `ConnectibleBuilder` and `ConnectibleConfiguration`

### Phase 2: Logical Connection (Core Multiplexing)

1. Create `LogicalConnection` implementing `Connection`
2. Implement state machine (IDLE, BORROWING, ACTIVE, IN_TX, PINNED, CLOSED)
3. Implement `borrowConnection()` / `returnConnection()` in `PgTransactionPool`
4. Implement single-query flow: borrow -> execute -> return
5. Implement transaction flow: borrow on BEGIN -> hold -> return on COMMIT/ROLLBACK
6. Unit tests for state machine and pool operations

### Phase 3: Prepared Statement Migration

1. Create `StatementEntry` record and statement registry in `LogicalConnection`
2. Implement lazy re-preparation logic (physicalId check)
3. Implement unique statement name generation with logical connection prefix
4. Implement stale statement retry (42P05, 26000)
5. Disable physical connection's statement cache in transaction mode
6. Integration tests for prepared statement migration

### Phase 4: LISTEN/NOTIFY Pinning

1. Implement PINNED state and subscribe() flow
2. Implement multi-subscription tracking
3. Implement unlisten() and connection return
4. Integration tests for notification delivery and pinning

### Phase 5: Hardening

1. Stress tests (1000 logical on 20 physical)
2. Connection failure scenarios
3. Pool exhaustion behavior
4. Edge cases: close-while-borrowing, close-while-in-tx, double-close
