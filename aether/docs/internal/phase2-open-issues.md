# Phase 2 Open Issues

Architectural gaps identified during Phase 2 spec review. Each issue needs a design decision before implementation.

## Issue 1: Endpoint Selection Is a Range Query (Critical)

**Problem:** `SliceInvoker` needs "all endpoints for slice X, method Y" to round-robin among them. Current `EndpointRegistry.findEndpoints(artifact, method)` returns a list. DHT is optimized for point lookups (`get(key) → value`), not range queries.

With `EndpointKey` format `endpoints/group:artifact:version/method:instance`, finding all endpoints for a slice+method requires scanning all instance keys across all nodes.

**Options:**

A. **Aggregated value per slice+method.** Key = `(artifact, method)`, value = `List<EndpointEntry>`. Single point lookup returns all endpoints. Downside: every node join/leave does read-modify-write on a shared key — contention at 10K nodes.

B. **Local index rebuilt from DHT subscriptions.** Each node subscribes to the `endpoints` map and builds a local `Map<(artifact, method), List<Endpoint>>` from events. Reads hit the local index (zero-hop). DHT is the source of truth; local index is a projection. Downside: index rebuild on node restart requires scanning or snapshot.

C. **Prefix query support in DHT.** Add `getByPrefix(keyPrefix)` to DHT. Downside: breaks the hash-based distribution model (prefix of a hash is meaningless).

D. **Hybrid: DHT for durability + local index for queries.** Same as B, but explicitly: DHT stores individual entries (fine-grained writes, no contention), and every node maintains a local query index rebuilt from subscription events + periodic anti-entropy sync. The local index replaces `EndpointRegistry`'s in-memory HashMap.

**Decision: Option D — DHT for durability + local index for queries.**

Each node writes individual entries to DHT (no contention). Every node subscribes to DHT events and maintains a local query index (`Map<(artifact, method), List<Endpoint>>` with round-robin counters) — same data structure as current `EndpointRegistry`. All read patterns (find-all, round-robin, exclude, affinity, version routing) hit the local index at zero hop. On restart, node subscribes + requests snapshot from DHT; anti-entropy fills gaps. This also resolves Issues 3 and 4.

---

## Issue 2: Stale Endpoint Cleanup (Critical)

**Problem:** Worker crashes without removing its DHT entries. Replicated entries persist on RF=3 nodes. Traffic routes to a dead worker → failed invocations.

Today, `WorkerGroupHealthReport` naturally replaces stale entries each health cycle. With direct writes, no automatic cleanup exists.

**Options:**

A. **TTL on DHT entries.** Entries auto-expire. Workers must periodically refresh (heartbeat write). Missed refresh = entry expires. Downside: adds write load (N workers × E entries × refresh rate).

B. **SWIM-triggered cleanup.** When SWIM detects a node as DEAD, a designated node (governor? core?) removes the dead node's entries from DHT. Downside: requires coordination on who does cleanup.

C. **Anti-entropy with SWIM membership.** DHT anti-entropy process compares endpoint entries against known-alive nodes (from SWIM). Orphaned entries (node not in any SWIM membership) are removed. Downside: delayed cleanup (anti-entropy interval).

D. **Lease-based.** Entries are valid only while the writer holds a lease. Lease expires on crash. Downside: adds lease management complexity.

E. **Combine B + C.** SWIM-triggered cleanup for fast response; anti-entropy as safety net for missed events.

**Decision: Option E — SWIM-triggered cleanup (governor) + anti-entropy safety net.**

Governor detects DEAD via SWIM → removes dead node's entries from DHT → propagates to all subscribers' local indices. Anti-entropy periodically reconciles DHT entries against SWIM-known-alive nodes, removes orphans. Same pattern as CDM + deployments. If governor dies, new governor reconciles membership against DHT on election.

---

## Issue 3: Rolling Update Routing (Important)

**Problem:** `SliceInvoker.selectEndpointWithFailover()` uses `rollingUpdateManager.getActiveUpdate()` then `endpointRegistry.selectEndpointWithRouting()` for weighted version routing (e.g., 90% v1, 10% v2). Routing config stays in consensus (`VersionRoutingValue`), but endpoint data moves to DHT. The weighted selection logic must work against the new storage model.

**Options:**

A. **Rolling update logic operates on the local index.** If Issue 1 is resolved via local index (Option B/D), rolling update routing queries the same local index, filtering/weighting by version. No change to the routing algorithm — only the data source changes.

B. **Separate version-tagged endpoint maps.** One map per version during rolling updates. Routing selects which map to query. Downside: adds map lifecycle management.

**Decision: Option A — resolved by Issue 1.** Local index serves all query patterns including version-weighted routing. Algorithm unchanged, data source changes from consensus to DHT-fed local index.

---

## Issue 4: Affinity Routing (Important)

**Problem:** `selectEndpointWithAffinity()` routes to a specific node based on request content. Currently: `endpointRegistry.selectEndpointByAffinity(slice, method, node)` — "find the endpoint for slice X on node Y." This is a compound query, not a simple point lookup.

**Options:**

A. **Local index resolves it.** If Issue 1 is resolved via local index, affinity routing filters the index by target node. Same as today.

B. **Composite DHT key.** Key includes nodeId: `(artifact, method, nodeId)`. Point lookup for affinity. Downside: different key structure than non-affinity lookups.

**Decision: Option A — resolved by Issue 1.** Local index filtered by target node. Identical to current `EndpointRegistry.selectEndpointByAffinity()`.

---

## Issue 5: DHT Network Transport for Workers (Infrastructure)

**Problem:** Workers don't participate in consensus. The existing Artifact DHT runs over the consensus `ClusterNetwork`. Workers lack consensus network access. DHT operations (put/get/replicate) need a network transport that workers can use.

**Options:**

A. **Reuse WorkerNetwork.** Extend `WorkerNetwork` (TCP, already exists) with DHT message types. Workers already use it for Decision relay and mutation forwarding.

B. **DHT gets its own transport.** Separate TCP connections for DHT traffic. Clean isolation. Downside: another port, more connections.

C. **Shared transport layer.** Abstract the network so both consensus and DHT messages route over the same TCP connections, distinguished by message type. Workers connect to core nodes and other workers; message routing handles the rest.

D. **Governor-to-governor mesh for DHT.** Governors establish connections to all other governors. DHT cross-community traffic routes: worker → governor → target governor → target worker. Eliminates core as relay for DHT traffic. Intra-community DHT is direct worker-to-worker. Governors already act as routing hubs (Decision relay, mutation forwarding). DD-04 (cross-group through core) applies to slice invocation routing, not infrastructure DHT traffic.

**Decision: Option D — governor-to-governor mesh for DHT traffic.**

Governors discover each other via core (community membership reports). ~100 connections per governor at 100 communities — trivial. DHT routing: intra-community = direct; cross-community = worker → governor → target governor → target worker (2 hops max, no core involvement). DD-04 remains for application-level invocation routing; DHT is infrastructure traffic with its own routing.

---

## Issue 6: Core Node Home Replica (Minor)

**Problem:** Core nodes aren't in a worker community. The home-replica rule can't place a community-local replica for core endpoints. Per the spec's edge case, core endpoints get 3 ring replicas (no home).

Other core nodes reading these endpoints require a DHT hop on first access, then cache. Subsequent reads are local (cached).

**Impact:** First invocation after cache miss/expiry adds one network hop. For frequently-invoked slices, LRU cache absorbs this. For rarely-invoked slices, one extra hop is acceptable.

**Options:**

A. **Accept it.** Cache handles it. No special treatment for core.

B. **Core nodes form their own "community."** Treat `core` as a community identifier. Home replica lands on a core peer. Downside: special-casing.

**Decision: Option B — core nodes form community `core`.**

Core is just another community in the home-replica algorithm. One line in `HomeReplicaResolver`. Ensures at least one replica of core-originated entries lands on a core peer. Core-to-core reads are always local via home replica. No special-casing — unified model where every node belongs to a community.
