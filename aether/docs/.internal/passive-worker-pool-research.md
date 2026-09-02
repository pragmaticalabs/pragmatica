# Passive Worker Pool Architecture — Research Findings

Research compiled for Aether's "Cluster Scalability via Passive Worker Pools" initiative.

**Context:** Aether currently limits clusters to 5-7-9-11 consensus nodes (Rabia, leaderless, all-to-all broadcast). The goal is to scale to tens/hundreds/thousands of worker nodes that run application slices but don't participate in consensus. A `PassiveNode<K,V>` abstraction and `AetherPassiveLB` already exist as proof-of-concept.

---

## Table of Contents

1. [Kubernetes](#1-kubernetes)
2. [HashiCorp Nomad](#2-hashicorp-nomad)
3. [Apache Mesos](#3-apache-mesos)
4. [CockroachDB / TiKV / FoundationDB](#4-cockroachdb--tikv--foundationdb)
5. [Vitess](#5-vitess)
6. [Akka Cluster](#6-akka-cluster)
7. [Hazelcast](#7-hazelcast)
8. [Orleans](#8-orleans)
9. [Ray](#9-ray)
10. [Consul](#10-consul)
11. [Cross-Cutting: Gossip Protocols (SWIM / Serf)](#11-gossip-protocols-swim--serf)
12. [Cross-Cutting: Proxy/Gateway Patterns](#12-proxygateways-for-mutation-forwarding)
13. [Cross-Cutting: Read Replica Patterns](#13-read-replica-patterns)
14. [Comparison Matrix](#14-comparison-matrix)
15. [Key Takeaways for Aether](#15-key-takeaways-for-aether)

---

## 1. Kubernetes

### Control/Worker Separation

- **Control plane** (master): API server, etcd (Raft consensus), scheduler, controller manager
- **Worker nodes**: kubelet agent, kube-proxy, container runtime
- etcd cluster is typically 3-5 nodes; worker nodes scale independently to thousands
- Complete separation: workers never participate in etcd consensus

### Worker Discovery & Communication

- Workers connect to the API server (single endpoint, often load-balanced)
- Kubelet uses **watch** (long-poll HTTP/2 streams) on the API server — not polling
- Workers are provisioned with cluster CA certificate and client credentials (client certificate or bootstrap token)
- API server is the sole gateway to etcd; workers never touch etcd directly

### Work Assignment

- Scheduler watches for unscheduled pods, selects a node based on resource requests, affinity, taints/tolerations
- Writes a `spec.nodeName` binding to the API server
- Kubelet watches for pods bound to its node, pulls specs, starts containers
- **Pull model**: kubelet watches API server; scheduler just updates state

### Worker Failure Handling

- Kubelet sends heartbeats via **Lease objects** (in `kube-node-lease` namespace) every 10 seconds
- Node controller checks lease freshness every 5 seconds (`--node-monitor-period`)
- After 40s without heartbeat, node marked `Unknown`; after additional grace period (default ~5 min), pods evicted and rescheduled
- Taints applied: `node.kubernetes.io/unreachable` or `node.kubernetes.io/not-ready`

### Control Plane Failure from Worker Perspective

- Workers continue running existing pods — **no disruption to running workloads**
- Kubelet cannot receive new work assignments or update status
- No new pods scheduled, no rescheduling of failed pods
- Workers buffer status updates and replay when API server recovers

### Pros
- Clean separation — etcd/control plane scaled independently from workers
- Declarative desired-state model — convergence after partitions
- Watch-based communication — efficient, real-time
- Proven at massive scale (thousands of nodes)

### Cons
- API server is a bottleneck/single point (mitigated by HA + load balancing)
- etcd limited to ~3-5 nodes (more hurts performance)
- Heartbeat-based failure detection is slow (minutes to reschedule)
- No gossip layer for worker-to-worker awareness

### Scale Characteristics
- etcd: 3-5 nodes max for performance
- API server: horizontally scalable (stateless, backed by etcd)
- Workers: tested to 5,000+ nodes per cluster
- Bottleneck: etcd write throughput, API server watch fanout

---

## 2. HashiCorp Nomad

### Control/Worker Separation

- **Servers** (3-5 recommended): accept jobs, run scheduler, maintain state via Raft consensus
- **Clients** (unlimited): register with servers, run workloads, report status
- Servers handle all state mutations; clients are pure executors

### Worker Discovery & Communication

- Clients configured with server addresses (or use Consul for discovery)
- Communication via **RPC** (port 8300)
- Clients register on startup, send periodic heartbeats
- **Gossip** (Serf, SWIM-based) used for server-to-server WAN federation, NOT for client-server communication

### Work Assignment

- User submits job to any server; leader runs scheduler
- Scheduler uses **bin packing** to find optimal node placement
- "Optimistically concurrent": all servers participate in scheduling evaluation, leader serializes the plan
- Creates **allocations** that map tasks to clients
- Clients poll for new allocations and execute them

### Worker Failure Handling

- Heartbeat-based: client sends heartbeats to assigned server
- On heartbeat timeout, server marks client as `down`
- Allocations on failed client rescheduled to other healthy clients
- Server-driven: clients don't detect each other's failures

### Control Plane Failure from Worker Perspective

- Running allocations continue executing (no disruption)
- Clients cannot receive new work or send status updates
- Clients retry RPC connections to servers with backoff
- On leader election, clients reconnect to new leader automatically

### Pros
- Simple, clean server/client model
- Gossip for WAN federation without consensus overhead
- Single binary for both server and client modes
- Efficient bin-packing scheduler

### Cons
- No gossip between clients (server is sole source of truth for client state)
- Client failure detection entirely server-driven (heartbeat timeouts)
- WAN gossip only between servers, not clients

### Scale Characteristics
- Servers: 3-5 per region
- Clients: 10,000+ tested per region
- Gossip: used for server federation across regions
- Bottleneck: Raft write throughput on leader server

---

## 3. Apache Mesos

### Control/Worker Separation

- **Master daemon**: manages resources, makes allocation offers
- **Agent daemons**: run on each worker, report available resources
- **Frameworks**: two-level — scheduler (registers with master) + executor (runs on agent)
- Master uses ZooKeeper for leader election (not for general consensus)

### Worker Discovery & Communication

- Agents configured with master address(es)
- Agents register with master, report CPU/memory/disk/ports
- Framework schedulers also register with master

### Work Assignment — Two-Level Scheduling

- **Level 1 (Mesos master)**: offers available resources to frameworks based on fair-share/priority policy
- **Level 2 (Framework scheduler)**: selects which offers to accept and maps tasks to them
- Agent receives task launch command from master after framework accepts offer
- **Push model**: master pushes resource offers to frameworks

### Worker Failure Handling

- Agent sends heartbeats to master
- On agent failure, tasks are reported as LOST
- Framework scheduler decides whether to reschedule
- Master doesn't automatically reschedule — framework responsibility

### Control Plane Failure from Worker Perspective

- Standby masters promoted via ZooKeeper
- Agents re-register with new master after leader change
- Running tasks continue executing during master failover
- New master reconstructs state from agent re-registration

### Pros
- Two-level scheduling allows framework-specific logic without modifying Mesos
- Resource isolation via cgroups/namespaces
- Clean separation of resource management from task scheduling

### Cons
- Complex architecture (ZooKeeper dependency)
- Resource offers can be inefficient (offer/reject cycles)
- Framework must handle its own rescheduling on agent failure
- Largely deprecated in favor of Kubernetes

### Scale Characteristics
- Master: single leader with standby (ZooKeeper quorum)
- Agents: 10,000+ (Twitter ran 100K+ agents)
- Bottleneck: single master for resource offers

---

## 4. CockroachDB / TiKV / FoundationDB

### 4a. CockroachDB

**Consensus/Storage Separation:**
- Data divided into **Ranges** (~512MB each), each range is an independent Raft consensus group (3 replicas)
- Uses **MultiRaft**: multiplexes many Raft groups over per-node connections
- Each pair of nodes exchanges heartbeats once per tick regardless of shared ranges
- Only 3 goroutines manage all ranges (not one per range)

**Work Assignment:**
- Leaseholder (one replica per range) serves reads and coordinates writes
- Lease assignment automatic; leaseholder is typically the Raft leader
- Load-based lease/replica rebalancing across nodes

**Read Path (no consensus):**
- Leaseholder serves reads directly — no Raft round required
- Reasoning: writes already achieved consensus before reaching leaseholder
- **Follower reads**: replicas serve stale reads for lower latency (trades recency for latency)
- Bounded staleness: "give me data at most 10s old" reads from any replica

**Scaling:**
- Every node participates in both consensus and storage (no separate control/data plane)
- Scales by adding more ranges and rebalancing
- No dedicated consensus nodes — all nodes are peers

### 4b. TiKV / TiDB

**Consensus/Storage Separation:**
- **Placement Driver (PD)**: control plane (3-5 nodes), stores metadata, manages scheduling
- PD embeds etcd for its own fault tolerance
- **TiKV nodes**: storage + multi-raft (data partitioned into Regions)
- Each Region replicated across TiKV nodes via Raft

**How PD Manages Workers:**
- TiKV nodes report heartbeats and load to PD
- PD makes region scheduling decisions: split, merge, transfer leader, add/remove replicas
- PD sends scheduling commands back to TiKV nodes

**Separation Quality:**
- Clean: PD is pure control plane (metadata + scheduling)
- TiKV nodes are pure data plane (storage + consensus for data regions)
- PD consensus (etcd/Raft) separate from data consensus (per-region Raft)

### 4c. FoundationDB

**Role Separation (most extreme decomposition):**
- **Coordinators**: Paxos group for cluster controller election ONLY
- **Cluster Controller**: manages process roles
- **Master/Sequencer**: assigns commit versions
- **Commit Proxies**: accept client writes, forward to transaction logs
- **GRV Proxies**: provide read versions (lightweight, no quorum needed)
- **Resolvers**: detect write-write conflicts
- **Transaction Log Servers**: durably persist mutations (WAL)
- **Storage Servers**: serve reads, apply mutations from logs

**Read Path:**
- Reads bypass consensus entirely
- GRV proxy provides a read version by consulting the sequencer
- Client reads from storage servers at that version
- No quorum, no Raft — just version check

**Write Path:**
- Client -> Commit Proxy -> Resolver (conflict check) -> Transaction Logs (durable) -> Storage Servers (eventually)
- Sequencer provides monotonic commit versions
- Transaction logs are the consensus/durability boundary

**Key Insight — "Unbundled":**
- FoundationDB separates every concern into a distinct process role
- Coordinators (tiny Paxos group) only elect the cluster controller
- All other roles are assigned by the controller and can be relocated/scaled independently
- Storage servers are completely decoupled from consensus

### Pros (all three)
- CockroachDB: per-range consensus allows massive parallelism; MultiRaft minimizes overhead
- TiKV: clean PD/TiKV separation enables independent scaling
- FoundationDB: extreme role separation; reads never touch consensus

### Cons
- CockroachDB: no separate control plane — every node is a peer (simpler but less flexible)
- TiKV: PD is a SPOF cluster (3-5 nodes); PD failure blocks scheduling
- FoundationDB: many moving parts, complex operational model

### Scale Characteristics
- CockroachDB: uniform nodes, scales linearly with nodes (100s of nodes, millions of ranges)
- TiKV: PD 3-5 nodes, TiKV nodes unlimited
- FoundationDB: coordinator Paxos group (3-5), all other roles scale independently

---

## 5. Vitess

### Routing/Storage Separation

- **VTGate**: stateless query router (any number of instances)
- **VTTablet**: per-MySQL sidecar managing a single MySQL instance
- **Topology Service**: etcd/ZooKeeper/Consul for metadata (shard map, tablet types)

### Routing Architecture

- Client connects to any VTGate
- VTGate parses SQL, determines relevant shards via cached shard map
- Forwards sub-queries to appropriate VTTablets
- Aggregates results and returns to client
- **Topology service never in the hot path** — VTGate caches shard routing info

### Work Assignment

- VTTablet registers itself with topology service
- VTGate discovers tablets via topology service (cached)
- Primary/replica/rdonly tablet types determine which serves reads vs writes

### Failure Handling

- VTGate monitors VTTablet health via streaming health checks
- On tablet failure, VTGate routes to replicas
- Reparenting (primary failover) managed by orchestration layer
- VTGate is stateless — any VTGate failure is invisible to others

### Relevance to Aether
- **Pattern**: stateless routing layer + stateful storage layer + external metadata store
- Very similar to Aether's existing PassiveLB pattern — PassiveLB is essentially a VTGate equivalent
- Topology caching approach relevant for worker pool route discovery

### Scale Characteristics
- VTGate: unlimited (stateless, horizontally scalable)
- VTTablet: one per MySQL instance (scales with shards)
- Topology service: 3-5 nodes
- YouTube runs Vitess at massive scale

---

## 6. Akka Cluster

### Seed Nodes vs Regular Nodes

- **Seed nodes**: well-known bootstrap addresses; any node can BE a seed node
- Seed nodes are NOT special once cluster is formed — purely for initial discovery
- No separate control plane — all nodes are peers in the cluster
- Cluster membership via gossip protocol (not consensus)

### Cluster Sharding

- **ShardCoordinator**: cluster singleton (runs on oldest node)
- Decides which ShardRegion (node) owns which shard
- State replicated via Distributed Data (CRDTs, WriteMajority/ReadMajority)
- NOT Raft/Paxos — uses CRDT convergence

### Shard Allocation

- When entity first accessed, ShardRegion asks ShardCoordinator for location
- Coordinator picks region with fewest shards (LeastShardAllocationStrategy)
- Location cached — subsequent messages go directly to owning region

### Node Failure

- Gossip-based failure detection (phi accrual detector)
- Failed node's shards reallocated by coordinator
- **Handoff protocol**: buffer messages -> stop entities -> acknowledge -> reallocate
- Entity state NOT transferred — must use akka-persistence for state recovery

### Control Plane Failure

- If coordinator node fails, new singleton promoted on next-oldest node
- Coordinator state recovered from Distributed Data (CRDT)
- During recovery, shard location requests buffered (up to configurable limit)

### Relevance to Aether
- **Pattern**: coordinator singleton for shard assignment, gossip for membership
- No separate control/worker plane — all nodes potentially host shards
- The ShardCoordinator is conceptually similar to Aether's CDM (ClusterDeploymentManager)
- CRDT-based state replication interesting alternative to consensus for shard maps

### Scale Characteristics
- All nodes are peers (no separate control plane)
- Coordinator is a bottleneck for new shard assignments (but caching mitigates)
- Tested to hundreds of nodes
- Phi accrual detector more responsive than heartbeat-timeout approaches

---

## 7. Hazelcast

### Lite Members vs Data Members

- **Data members**: own partitions, store data, participate in partition rebalancing
- **Lite members**: join cluster, execute tasks, register listeners, but **own no partitions**
- Lite member join/leave does NOT trigger repartitioning (key benefit)
- Lite members access remote partitions transparently via cluster protocol

### How Lite Members Participate

- Full cluster membership (same gossip/heartbeat)
- Can execute distributed tasks (EntryProcessor, etc.)
- Can register event listeners
- Can read/write data (routed to partition owner)
- Cannot be partition owners or backup holders

### Failure Handling

- Lite member failure: no data impact, no repartitioning
- Data member failure: partitions rebalanced to surviving data members
- Lite members continue functioning during data member failures (if backup exists)

### Relevance to Aether
- **Directly applicable pattern**: Aether passive workers = Hazelcast lite members
- Key insight: lite member churn doesn't disturb data distribution
- Lite members are first-class cluster citizens for compute, just not for data ownership
- **This is the closest existing model to what Aether needs**

### Pros
- Clean compute/storage separation within same cluster
- No repartitioning on lite member changes
- Same protocol for both member types

### Cons
- All members still in same gossip group (scalability limit ~1000)
- No hierarchical membership (flat cluster)
- Lite members still contribute to gossip traffic

### Scale Characteristics
- Gossip-based membership limits practical cluster size
- Data members: partition count (default 271) limits effective parallelism
- Lite members: scale better than data members due to no partition overhead

---

## 8. Orleans

### Silo Architecture

- Every silo is a peer — no separate control plane
- Each silo hosts grains (virtual actors)
- Cluster membership via **MembershipTable** (external store: Azure Table, SQL, ZooKeeper, etc.)

### Grain Placement

- When grain activated, placement strategy picks a silo
- Default: resource-optimized (CPU + memory aware, since Orleans 9.2)
- Options: random, prefer-local, hash-based, custom
- **Grain directory**: one-hop DHT partitioned across all silos via consistent hashing
- 30 virtual nodes per silo on the hash ring (similar to Dynamo/Cassandra)

### Cluster Membership Protocol

- Silos probe each other directly ("are you alive?" pings)
- Probes sent over same TCP connections as regular messages (correlates with actual network health)
- Probing targets selected via consistent hash ring of silo identities
- MembershipTable acts as rendezvous + conflict resolution
- View-based: monotonically increasing version numbers

### Failure Handling

- Silo failure detected via direct probes + MembershipTable update
- Grains on failed silo reactivated lazily on next access (virtual actor model)
- No proactive migration — grains rematerialize on demand
- Directory entries for dead silo cleaned up

### Relevance to Aether
- **DHT-based directory** very relevant — Orleans' one-hop DHT maps grain ID to silo
- Consistent hashing with virtual nodes matches Aether's DHT design
- Lazy activation model interesting for worker pools — don't proactively move slices
- MembershipTable as external rendezvous similar to Aether's KV-store for node state

### Scale Characteristics
- All silos are peers (no tiering)
- Direct probing limits: O(N) probes per silo in worst case
- Tested to hundreds of silos (Halo, various Microsoft services)
- Grain directory scales with number of silos (partitioned DHT)

---

## 9. Ray

### Head Node vs Worker Nodes

- **Head node**: runs GCS (Global Control Store), scheduler, dashboard
- **Worker nodes**: execute tasks and actors, contribute to distributed object store
- Head node is SPOF by default (single GCS instance)

### GCS (Global Control Store)

- Centralized metadata: actor registry, placement groups, node info, resource state
- By default, in-memory only (no persistence)
- Fault tolerance requires external HA Redis (GCS writes to Redis, recovers from it)
- Worker nodes communicate with GCS via gRPC

### Work Assignment

- Distributed scheduler: head node and worker nodes both schedule tasks
- Head node schedules cluster-level decisions (placement groups, actor creation)
- Worker nodes schedule local tasks directly
- Resource tracking: dynamic, per-node availability reported to GCS

### Worker Failure Handling

- Raylet on each node monitors local workers
- GCS monitors node-level health
- Task retry: configurable `max_retries` per task
- Actor fault tolerance: checkpoint-based recovery

### Head Node Failure (with GCS FT enabled)

- Worker nodes attempt reconnection with configurable timeout
- **Running tasks and actors continue executing** during GCS recovery
- Existing objects remain available
- **Unavailable during recovery**: actor creation/deletion, placement group management, new worker registration, new worker process creation
- Worker pod timeout must exceed head pod restart time

### Relevance to Aether
- **Pattern**: centralized metadata store (GCS) + distributed execution (workers)
- GCS FT via Redis mirrors the concept of consensus core + external state
- "Workers keep running during head failure" is critical requirement for Aether
- Distributed scheduling (workers schedule locally) reduces head node bottleneck

### Pros
- Simple model: one head, many workers
- Workers continue during head failure (with FT enabled)
- Distributed object store avoids centralizing data

### Cons
- Head node is SPOF (even with FT, recovery has downtime for new work)
- GCS in-memory by default (data loss risk)
- gRPC to GCS can bottleneck at scale

### Scale Characteristics
- Head node: 1 (SPOF mitigated by Redis-backed FT)
- Worker nodes: tested to 1000s
- GCS becomes bottleneck at very large scale
- Anyscale (Ray commercial) distributes GCS across multiple nodes

---

## 10. Consul

### Server vs Client Agents

- **Server agents** (3-5): maintain state via Raft, replicate data, elect leader
- **Client agents** (one per compute node): lightweight, forward RPCs to servers
- Client agents are local proxies — every service talks to local client agent, which forwards to servers

### Communication Architecture

- **LAN gossip** (Serf/SWIM): all agents (servers + clients) within a datacenter
- **WAN gossip**: servers only, across datacenters
- **RPC**: client-to-server for state queries/mutations (port 8300)
- **Forwarding chain**: service -> local client agent -> (any) server -> leader (if write)

### Work Assignment

- Not a scheduler — Consul is service discovery + configuration
- Services register with local client agent
- Client agent syncs registration to servers via anti-entropy
- Other services discover via DNS or HTTP API (through their local client agent)

### Client Failure Handling

- LAN gossip detects client agent failure
- Health checks associated with that agent marked critical
- Services on that node marked unhealthy in catalog
- Fast detection via SWIM protocol (seconds, not minutes)

### Server Failure Handling from Client Perspective

- Client agents discover servers via LAN gossip (not static config)
- On leader failure, Raft elects new leader; clients retry RPCs
- Non-leader server failure: clients route to remaining servers
- Gossip continues functioning even during Raft leader election

### Anti-Entropy

- Client agents periodically sync their full state with servers
- Handles any inconsistencies from missed RPCs or network partitions
- Bidirectional: both agent and server can detect drift

### Relevance to Aether
- **Pattern**: gossip for membership/health + consensus for state mutations
- Client-agent-per-node is elegant — local fast path + server forwarding
- Anti-entropy mechanism relevant for worker pool state consistency
- Two gossip pools (LAN/WAN) interesting for multi-region worker pools

### Scale Characteristics
- Servers: 3-5 per datacenter
- Client agents: 10K+ per datacenter (gossip handles this well with SWIM)
- LAN gossip: tested to 10K+ members
- WAN gossip: server-only, scales to many datacenters

---

## 11. Gossip Protocols (SWIM / Serf)

### SWIM Protocol

**Design:**
- Separates failure detection from membership update dissemination
- Each node periodically pings a randomly chosen peer
- If no ack, sends **indirect ping** via K other nodes (outsourced heartbeat)
- If still no response, marks node as **suspect** (not immediately dead)
- After suspect timeout, declares node as failed

**Scalability Properties:**
- Failure detection time, false positive rate, and message load per process are **independent of group size**
- O(1) message overhead per member per protocol period
- Contrast with heartbeat protocols: O(N^2) messages for N nodes

**Dissemination:**
- Piggybacked on ping/ack messages (no separate multicast)
- Membership changes propagate in O(log N) protocol rounds (epidemic/gossip spread)
- No dedicated gossip messages — reuses failure detection traffic

### Serf (HashiCorp's SWIM Implementation)

**Enhancements over SWIM:**
- **Lifeguard**: reduces false positives by 50x+ (detects slow processing vs actual failure)
- **Memberlist library**: embeddable Go library implementing SWIM
- **Event broadcasting**: not just membership — arbitrary events piggybacked on gossip
- Tested at 10K+ nodes per gossip pool; Consul runs millions of machines with gossip

**Relevance to Aether:**
- Gossip is the natural membership layer for passive worker pools
- Workers should NOT be in the consensus group but SHOULD be in a gossip pool
- Gossip handles: worker discovery, health monitoring, lightweight event dissemination
- Consensus core handles: state mutations, scheduling decisions, authoritative state
- **Recommended two-tier model**: gossip for membership (fast, scalable) + consensus for state (consistent, small)

---

## 12. Proxy/Gateways for Mutation Forwarding

### Patterns Observed

**Consul forwarding chain:**
- Client agent -> any server -> leader
- Client doesn't need to know who the leader is
- Any server forwards writes to current leader
- Transparent to client — same API regardless

**Kubernetes API server:**
- Workers and clients talk to API server (load-balanced)
- API server reads/writes to etcd
- Complete abstraction — nobody touches etcd directly

**CockroachDB gateway nodes:**
- Any node can receive SQL queries
- Node routes to leaseholder for relevant range
- Client doesn't need to know data distribution

**FoundationDB commit proxies:**
- Multiple stateless commit proxies accept writes
- Forward to transaction logs for durability
- Client connects to any proxy

### Common Pattern: **Smart Proxy with Leader Forwarding**
1. Client sends mutation to any "proxy" node
2. Proxy determines current leader/owner
3. Proxy forwards mutation, waits for response
4. Returns result to client
5. Proxy caches leader identity to minimize redirection

### Relevance to Aether
- Passive workers need a way to submit mutations (KV-store writes, slice lifecycle events)
- **Pattern**: worker sends mutation to any consensus core node, which forwards to leader
- OR: worker maintains connection to leader, reconnects on leader change
- Aether already has this partially: `AetherPassiveLB` connects to cluster but doesn't submit mutations
- **Extension needed**: mutation proxy on passive workers that routes to consensus core

---

## 13. Read Replica Patterns

### Approaches Without Consensus

**Leaseholder reads (CockroachDB):**
- Leaseholder serves reads without Raft — data already reached consensus to get there
- Single node serves both reads and writes for a range
- Fastest consistent reads, but leaseholder is a hotspot

**Follower reads (CockroachDB, TiKV):**
- Any replica serves reads at a slightly stale timestamp
- Bounded staleness: "data at most T seconds old"
- Eliminates cross-region latency for reads
- Trade: recency for availability/latency

**Read-your-writes consistency (Shopify, Box):**
- Track replication position (GTID) at write time
- Route subsequent reads to replicas that have caught up to that position
- User-level consistency without global consensus

**Monotonic read consistency:**
- Route successive reads from same client to same replica
- Ensures reads never "go back in time"
- Simple session affinity

**User pinning:**
- After write, pin user to primary for short window
- Simple, works for most interactive use cases
- Falls back to replica reads after window expires

### Relevance to Aether

Passive workers will have a local KV-store replica (via Decision stream). This is inherently an **eventually consistent read replica**. Key questions:

1. **How stale can reads be?** Decision propagation latency = staleness window
2. **Read-your-writes**: if a worker submits a mutation via proxy, it won't see it locally until the Decision arrives
3. **Mitigation options**:
   - Track pending mutations locally (optimistic reads)
   - Wait for Decision containing submitted mutation before returning
   - Accept staleness for most reads, proxy to core for consistency-critical reads

---

## 14. Comparison Matrix

### Control Plane / Worker Plane Separation

| System | Control Plane | Worker Plane | Membership | Assignment Model |
|--------|--------------|-------------|-----------|-----------------|
| **Kubernetes** | etcd (3-5) + API server | kubelet agents | Watch/heartbeat | Pull (kubelet watches) |
| **Nomad** | Servers (3-5, Raft) | Clients (RPC) | Heartbeat | Push (server allocates) |
| **Mesos** | Master + ZK | Agents | Heartbeat | Push (resource offers) |
| **CockroachDB** | None (peer) | None (peer) | Gossip + liveness | Per-range Raft |
| **TiKV** | PD (3-5, etcd) | TiKV nodes | Heartbeat to PD | PD scheduling commands |
| **FoundationDB** | Coordinators (Paxos) | Storage/Log servers | Controller-assigned | Controller role assignment |
| **Vitess** | Topo service (etcd/ZK) | VTTablet/VTGate | Registration + health | Topo-based discovery |
| **Akka** | ShardCoordinator (singleton) | All nodes (peers) | Gossip (phi accrual) | Coordinator assigns shards |
| **Hazelcast** | None (peer, data members) | Lite members | Gossip/heartbeat | Client routes to partition owner |
| **Orleans** | None (peer, all silos) | None (peer) | MembershipTable + probes | DHT-based grain directory |
| **Ray** | Head node (GCS) | Worker nodes | gRPC to GCS | Distributed scheduler |
| **Consul** | Servers (3-5, Raft) | Client agents | Gossip (SWIM) | Service registration |

### Worker Failure Detection

| System | Mechanism | Detection Time | False Positive Handling |
|--------|-----------|---------------|----------------------|
| **Kubernetes** | Lease heartbeat | ~50s to mark unknown | Grace period before eviction |
| **Nomad** | RPC heartbeat | Configurable | Server-side timeout |
| **Consul** | SWIM gossip | Seconds | Lifeguard (suspect state) |
| **Akka** | Phi accrual detector | Adaptive | Probability-based threshold |
| **Hazelcast** | Heartbeat | Configurable | Suspect + dead states |
| **Orleans** | Direct probes | Configurable | Probe on same TCP as app traffic |
| **Ray** | gRPC health | Configurable | Timeout-based |

### Control Plane Failure Impact on Workers

| System | Running Work | New Work | Recovery |
|--------|-------------|----------|---------|
| **Kubernetes** | Continues | Blocked | API server restart, etcd recovers |
| **Nomad** | Continues | Blocked | Raft leader election |
| **Ray** | Continues (with FT) | Blocked | GCS restart from Redis |
| **Consul** | Services run | Registration blocked | Raft leader election |
| **TiKV** | Data serves | Scheduling blocked | PD leader election |
| **FoundationDB** | Reads continue | Writes blocked | Coordinator re-election |

### Scale Limits

| System | Control Nodes | Worker Nodes (tested) | Bottleneck |
|--------|--------------|----------------------|-----------|
| **Kubernetes** | 3-5 (etcd) | 5,000+ | etcd writes, API server watches |
| **Nomad** | 3-5 | 10,000+ | Raft throughput |
| **Mesos** | 1 active + standbys | 100,000+ (Twitter) | Single master offers |
| **Consul** | 3-5 | 10,000+ per DC | LAN gossip pool size |
| **Ray** | 1 head | 1,000+ | GCS metadata throughput |
| **Hazelcast** | N/A (peer) | ~1,000 (gossip limit) | Gossip protocol |
| **Orleans** | N/A (peer) | Hundreds | Direct probe overhead |

---

## 15. Key Takeaways for Aether

### Most Relevant Patterns

1. **Hazelcast Lite Members** — closest analog. Passive workers = lite members that join the cluster, execute work, but don't own data/participate in consensus. Key benefit: worker churn doesn't trigger rebalancing.

2. **Consul's Two-Layer Architecture** — gossip (SWIM) for membership + Raft for state. Workers in gossip pool for discovery/health; consensus core for authoritative state. Forwarding chain for mutations: worker -> any core node -> leader.

3. **Kubernetes Watch Model** — workers watch the control plane for state changes rather than polling. Aether already does this via Decision stream to PassiveNode.

4. **FoundationDB's Read/Write Path Separation** — reads never touch consensus. Workers serve reads from local KV-store replica; mutations forwarded to consensus core.

5. **TiKV's PD Pattern** — dedicated control plane (small consensus group) that manages a large fleet of data/compute nodes. PD sends scheduling commands; workers execute.

### Architecture Recommendations

**Membership Layer:**
- SWIM/gossip for worker pool membership (scales to 10K+, O(1) overhead per member)
- Consensus core remains as-is (5-7-9 Rabia nodes)
- Workers join gossip pool on startup, discovered by core via gossip events
- Core nodes are also gossip members (dual role: gossip + consensus)

**State Distribution:**
- Workers receive committed Decisions via existing Decision stream (already implemented in PassiveNode)
- Workers maintain local KV-store replica (read-only, eventually consistent)
- This IS the read-replica pattern — no additional mechanism needed

**Mutation Forwarding:**
- Worker submits mutations via RPC to any consensus core node
- Core node forwards to leader if necessary
- Response returned to worker after consensus commit
- Worker's local KV-store updated when Decision arrives (eventual)

**Work Assignment:**
- CDM (ClusterDeploymentManager) on leader makes placement decisions
- CDM writes slice assignments to KV-store
- Workers watch for assignments targeting them (via Decision stream)
- Workers activate/deactivate slices based on assignments
- Similar to K8s scheduler writing pod assignments, kubelet watching

**Worker Failure:**
- SWIM gossip detects worker failure (seconds, not minutes)
- Core nodes receive gossip notification
- CDM reschedules slices from failed worker to other healthy workers
- No consensus disruption — worker pool changes are lightweight

**Core Failure from Worker Perspective:**
- Workers continue running existing slices
- Decision stream interrupted — local KV-store becomes stale (bounded)
- Mutations from workers fail (retry with backoff)
- On core recovery, workers reconnect and receive missed Decisions
- **Critical**: workers must tolerate temporary inability to submit mutations

### Open Questions for Spec

1. **Gossip implementation**: embed Serf/memberlist (Go) or implement SWIM in Java? Existing topology manager uses TCP — can it scale?
2. **Worker identity**: how do workers identify themselves? Static config or dynamic registration?
3. **Slice assignment granularity**: per-worker explicit assignment or hash-based automatic placement?
4. **DHT participation**: do workers own DHT partitions or only serve as DHT clients?
5. **Worker-to-worker communication**: do workers route through core or communicate directly?
6. **Multi-pool management**: how do main pool vs spot pool differ in assignment/eviction policy?
7. **Consensus core scaling**: can the core dynamically resize (e.g., 5 -> 7) without cluster restart?
8. **Decision stream catch-up**: how does a new worker get current state? Full KV snapshot + subsequent Decisions?

---

## Sources

### Kubernetes
- [Communication between Nodes and the Control Plane](https://kubernetes.io/docs/concepts/architecture/control-plane-node-communication/)
- [Cluster Architecture](https://kubernetes.io/docs/concepts/architecture/)
- [Node Status](https://kubernetes.io/docs/reference/node/node-status/)
- [How does Kubernetes handle node failures?](https://falconcloud.ae/about/blog/how-does-kubernetes-handle-node-failures/)

### Nomad
- [Architecture](https://developer.hashicorp.com/nomad/docs/architecture)
- [Gossip Protocol](https://developer.hashicorp.com/nomad/docs/architecture/security/gossip)
- [Reference Architecture](https://developer.hashicorp.com/nomad/docs/deploy/production/reference-architecture)

### Mesos
- [Architecture](https://mesos.apache.org/documentation/latest/architecture/)
- [High-Availability Mode](https://mesos.apache.org/documentation/latest/high-availability/)

### CockroachDB
- [Scaling Raft](https://www.cockroachlabs.com/blog/scaling-raft/)
- [Replication Layer](https://www.cockroachlabs.com/docs/stable/architecture/replication-layer)
- [An epic read on follower reads](https://www.cockroachlabs.com/blog/follower-reads-stale-data/)
- [Reads and Writes Overview](https://www.cockroachlabs.com/docs/stable/architecture/reads-and-writes-overview.html)

### TiKV / TiDB
- [TiKV Architecture](https://tikv.org/docs/3.0/concepts/architecture/)
- [TiDB Architecture](https://docs.pingcap.com/tidb/stable/tidb-architecture/)
- [Placement Driver (GitHub)](https://github.com/tikv/pd)

### FoundationDB
- [KV Architecture](https://apple.github.io/foundationdb/kv-architecture.html)
- [Architecture Overview](https://apple.github.io/foundationdb/architecture.html)
- [FoundationDB Paper (SIGMOD)](https://www.foundationdb.org/files/fdb-paper.pdf)

### Vitess
- [VTGate Concepts](https://vitess.io/docs/22.0/concepts/vtgate/)
- [Topology Service](https://vitess.io/docs/23.0/reference/features/topology-service/)
- [Understanding Vitess Architecture](https://andrewjdawson2016.medium.com/understanding-the-architecture-of-vitess-5f3c042c4cdd)

### Akka Cluster
- [Cluster Sharding](https://doc.akka.io/libraries/akka-core/current/typed/cluster-sharding.html)
- [Cluster Sharding Concepts](https://doc.akka.io/libraries/akka-core/current/typed/cluster-sharding-concepts.html)

### Hazelcast
- [Enabling Lite Members](https://docs.hazelcast.com/hazelcast/5.6/maintain-cluster/lite-members)
- [Data Partitioning](https://docs.hazelcast.com/hazelcast/5.6/architecture/data-partitioning)
- [Architecture](https://docs.hazelcast.com/hazelcast/5.5/architecture/architecture)

### Orleans
- [Grain Placement](https://learn.microsoft.com/en-us/dotnet/orleans/grains/grain-placement)
- [Cluster Management](https://learn.microsoft.com/en-us/dotnet/orleans/implementation/cluster-management)
- [Grain Directory Implementation](https://learn.microsoft.com/en-us/dotnet/orleans/implementation/grain-directory)

### Ray
- [GCS Fault Tolerance](https://docs.ray.io/en/latest/ray-core/fault_tolerance/gcs.html)
- [Node Fault Tolerance](https://docs.ray.io/en/latest/ray-core/fault_tolerance/nodes.html)
- [Redis in Ray: Past and Future](https://www.anyscale.com/blog/redis-in-ray-past-and-future)
- [Ray Core System Design Deep Dive](https://nkkarpov.github.io/blog/ray-core-system-design/)

### Consul
- [Architecture](https://developer.hashicorp.com/consul/docs/architecture)
- [Control Plane Architecture](https://developer.hashicorp.com/consul/docs/architecture/control-plane)
- [Gossip](https://developer.hashicorp.com/consul/docs/concept/gossip)

### Gossip Protocols
- [SWIM Paper (Cornell)](https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf)
- [Making Gossip More Robust with Lifeguard](https://www.hashicorp.com/en/blog/making-gossip-more-robust-with-lifeguard)
- [SWIM: The scalable membership protocol](https://www.brianstorti.com/swim/)
- [Everybody Talks: Gossip, Serf, memberlist, Raft, and SWIM](https://www.hashicorp.com/en/resources/everybody-talks-gossip-serf-memberlist-raft-swim-hashicorp-consul)

### Read Replica Patterns
- [Read Consistency with Database Replicas (Shopify)](https://shopify.engineering/read-consistency-database-replicas)
- [How We Learned to Stop Worrying and Read from Replicas (Box)](https://medium.com/box-tech-blog/how-we-learned-to-stop-worrying-and-read-from-replicas-58cc43973638)
- [Sequential consistency without borders (Cloudflare D1)](https://blog.cloudflare.com/d1-read-replication-beta/)
