---
RFC: 0020
Title: Streams and durable entities in Terra
Status: Draft (partly superseded by #2099 and #2100)
Superseded-By: (partly: #2099 Kafka resource for streams, #2100 Postgres durable-entity provider; both open, neither shipped)
Author: Sergiy Yevtushenko
Created: 2026-10-18
Updated: 2026-11-24
Affects: [terra, aether-stream]
---

# RFC-0020: Streams and durable entities in Terra

Moved from `terra/STREAMS-AND-ENTITIES.md` (#2801). The text below the status table is the original document, unchanged except for the heading level of its title; the table and the banners say which parts a later direction has replaced.

## Status of each part

Product direction of 2026-11-06: Terra ships SPI resource clients that work from both runtimes, so the runtime (Terra or Aether) and each resource's backend are chosen independently, and state lives in external standard infrastructure. Streams and durable entities follow that direction through two tickets, both **open**: the Kafka-backed provider for the slice stream resources (#2099) and the database-backed durable-entity provider (#2100). Until they ship, the Terra compiler still refuses stream and durable-entity dependencies; nothing in this RFC is implemented.

| Part of this RFC | State | Why |
| --- | --- | --- |
| Reusable code already present (table) | **Still accurate as a survey**, no longer a plan | It lists what exists in `aether-stream` and `integrations/storage`. A Kafka or Postgres resource client does not reuse it. |
| Contract 1-2: one process owns a data directory, exclusive OS lock, factors fixed at one | **Superseded** by #2099 and #2100 | The embedded local-WAL route is replaced by external infrastructure that holds the state, so a Terra instance owns no data directory and a second instance is the normal case (the two-instance standard), not a refused one. |
| Contract 3: partitions, offsets, cursors advance after handling, redelivery after a crash, no exactly-once claim | **Superseded as the mechanism, kept as the delivery semantics to be earned** | Consumer progress maps onto the backend's own consumer-group commit (#2099 lists it among what maps directly). The "advance after successful handling, never skip silently, no exactly-once external side-effect" statement is a requirement the provider must meet, not something this RFC provides. |
| Contract 4: separate stream declarations from external references | **Open** | Not addressed by either ticket; it concerns the compiler and the deployment configuration, not the backend. |
| Contract 5: per-key serialisation, durable timers, atomic fire/cancel/delete | **Superseded as the mechanism, kept as the behaviour to be earned** | State and timers move to the database under #2100; the ordering and atomicity requirements remain. |
| Contract 6: recovery before readiness, shutdown order, no queue advance on a caller timeout | **Still applicable** | Lifecycle rules for any Terra resource client; they do not depend on the storage choice. |
| Contract 7: reporting offsets, lag, timers, storage failures | **Still applicable** as a requirement on each provider | #2099 and #2100 each state their guarantees per operation. The surface the numbers reach is the observability work (#2796); this RFC defines none. |
| Suggested implementation order | **Superseded** | It sequences a local-storage build that is no longer planned. |
| "Do not promote `InMemoryDurableEntity` from test sources into production" | **Still applicable** | It cannot meet acknowledged-write or timer-restart behaviour on any route. |
| Executed feasibility checks (2026-10-08) | **Historical** | Counts for the Aether suites on that date; not re-run for this move. |

## Original document

This is an implementation investigation and proposed next contract, **not shipped Terra support**. The compiler still refuses stream and durable-entity dependencies. Ephemeral pub-sub remains a distinct, implemented capability.

### Reusable code already present

| Concern | Existing implementation | Terra implication |
| --- | --- | --- |
| Local partition log | `aether/aether-stream/StreamPartitionManager`, `integrations/storage/AppendLog` | The manager has a public constructor accepting a WAL directory without a cluster. Reuse its append/fsync/recovery/retention machinery. The constructor without a WAL is unsuitable for a durable target. |
| Publishing and reading | `DefaultStreamPublisher`, `PartitionedStreamAccess`, `StreamPublisherFactory`, `StreamAccessFactory` | Keep application interfaces. Replace runtime collaborator assembly and address resolution with explicit single-process ownership; do not simulate a running cluster. |
| Consumer progress | `StreamConsumerRuntime`, `ConsumerCursorStore` | Persist progress and define replay/commit behavior. A callback list alone would lose this contract. A PostgreSQL cursor store is possible when a database dependency is acceptable (none is wired today). |
| Entity state machine | `PartitionFencedDurableEntity`, `EntityFold`, `PerKeySerialExecutor` | Preserve create/update/delete ordering and replay. The public factory currently requires ownership, epoch, replication, and serializer collaborators. Extract a shared state-machine/storage boundary instead of passing dummy distributed collaborators. |
| Entity persistence | `EntityLogSubstrate`, `EntityLogRecord`, `EntityFoldSnapshot` | Preserve the log/checkpoint format and refuse replay gaps. Checkpoint coverage must precede retention reclamation. A local substrate can replace replication with durable local commit, subject to explicit single-copy configuration. |
| Timers/checkpoints | `EntityTimerDriver`, `EntityCheckpointDriver` | Pending timers are durable commands, not merely scheduled JVM tasks. The driver pair needs owned startup, recovery, shutdown, and status reporting. Their registration methods currently expose the concrete fenced implementation, so extraction is needed. |
| Serialization | Slice codec generation and `Serializer`/`Deserializer` extensions | Terra currently omits Aether dispatch codecs. Persistent resources still require codecs for payloads, keys, state, and commands; generate/register those independently of RPC adapters. Persisted type/version compatibility needs a contract. |

The current local WAL constructor demonstrates that a cluster is not intrinsically required for storage. It does **not** by itself provide a Terra resource provider, consumer activation, durable cursor/checkpoint lifecycle, or process exclusivity.

### Proposed single-process contract

> Per-item status is in the table at the top: items 1-2, 3 (mechanism) and 5 (mechanism) are superseded by #2099 and #2100; 6 and 7 still apply.

1. One process owns an application data directory and acquires an exclusive operating-system lock before opening logs. Refuse a second owner. Store format/version and application identity; refuse incompatible data rather than overwriting it. Recovery is to stop the other owner or restore compatible data/build artifacts.
2. Resolve omitted replication/confirmation factors to one for this target; reject explicit factors above one. This offers local disk durability, not replicated availability. Lost/corrupt storage requires backup recovery. Do not silently reinterpret Aether quorum declarations.
3. Streams retain partitions, partition-key extraction, offsets, size/retention limits, and qualified identities. Publishers acknowledge durable append independently of subscriber completion. Consumers advance persistent cursors only after successful handling; restart may redeliver an effect completed before cursor commit. No exactly-once external side-effect claim. Failed consumers expose position/cause and require retry or an explicit operator action; never silently skip events.
4. Separate stream declarations from external references. Only references resolved to an included local declaration are allowed. The notification example's original external producer address must be mapped deliberately through deployment configuration; bare aliases must not accidentally merge unrelated streams.
5. Entities serialize operations per key, retain create/update/delete behavior, and serve both read consistencies from the single authoritative local state. Acknowledged writes survive process restart. Persist timer commands and deadlines; fire/cancel/delete must atomically update pending timers with state. Preserve the existing behavior for an undecodable or failing timer command and expose its failure.
6. Recover logs, state, cursors, timers, and checkpoints before HTTP readiness. Shutdown stops ingress, settles accepted work, stops consumer/timer drivers, commits progress, then closes logs. A caller timeout must not advance a storage queue or release the data-directory lock while actual I/O continues.
7. Report per-partition offsets, consumer lag/failure, pending timers, checkpoint coverage, and storage failures. Retention gaps or incompatible codecs stop the affected resource; operators restore data or deploy compatible code rather than accepting fabricated state.

These are design proposals; they are not yet failure-mode guarantees for Terra.

### Suggested implementation order

> Superseded: it sequences a local-storage build that #2099 and #2100 replace.

First isolate the local storage/codec lifecycle and prove append/restart/corruption/exclusive-owner behavior. Then implement stream publisher/access and one durable consumer group, and run notification-service plus analytics unchanged. Notification-emailer additionally needs a controlled SMTP sink and tests of delivery failure/replay. Finally extract the entity state machine from owner admission, wire its timers/checkpoints, and prove entity/timer restart and deletion/cancellation races. This order lets entities use a storage layer already tested through application code.

Do not promote `InMemoryDurableEntity` from test sources into production. It cannot meet acknowledged-write or timer-restart behavior.

### Executed feasibility checks

On 2026-10-08, the existing Aether suites passed: **28** tests across `StreamPartitionManagerWalTest`, `StreamPartitionManagerRecoveryTest`, and `StreamPartitionManagerWalTruncateTest`; **41** across `EntityFoldTest`, `EntityTimerDriverTest`, and `PerKeySerialExecutorTest`. Stream tests use local temporary WAL files; entity tests use controlled substrates. These support code reuse, and are **not** an end-to-end Terra stream/entity proof or a multi-node availability test.
