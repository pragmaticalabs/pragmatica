# Metrics distribution and calculation

Status: implementation specification for the hierarchical clustering batch against `rc4`.
Fresh clusters only. The producer-envelope ping/pong schema replaces the earlier wire layout;
there is no migration, rolling-upgrade negotiation, or compatibility decoder.

## 1. Scope and owners

Metrics are control-plane observations consumed by core nodes. Workers produce local measurements;
they do not need a complete replica of cluster metrics. Any authenticated cluster member may ping
another member and receive a pong. Neither request acceptance nor response generation depends on
leadership. This does not authorize the sender to command the receiver.

The cluster transport authenticates peer membership. Observation exchange is not a Byzantine
protocol: an authenticated member is assumed not to forge another producer's envelope. Extending
that threat model requires signed producer envelopes and is outside this change.

| Operation | Trigger/input | Output | Failure/recovery |
|---|---|---|---|
| Observe local metrics | Collection tick or ping response | Immutable producer envelope | Missing measurements remain absent |
| Receive observations | Ping batch or pong | New producer versions recorded once | Duplicate, older, expired, or excessively future samples ignored |
| Respond to ping | Authenticated inbound ping | Own observation and existing ancillary pong signals | Transport refusal is retried by the next periodic exchange |
| Apply piggybacked control | Authority-bearing ping from identified current core leader | Fenced epoch/readiness/provision/drain effects | Unauthorized or old-term effects ignored; pong still sent |
| Distribute to cores | Existing leader scheduler tick | Complete available collection in bounded chunks | Dropped chunks create missing coverage, never zero demand |

## 2. Identity, time, and calculation

`MetricObservation` contains `incarnation`, monotonic producer `sequence`, original UTC
`observedAtMs`, and an immutable metric-value map. The ping map key or pong sender identifies the
producer. Forwarders preserve the complete envelope unchanged. A receiver accepts a version only
if its incarnation is newer or its sequence is greater within the same incarnation. Membership
removal retires the retained producer watermark and history. Worker identities are not reused.
Both raw and typed ingestion require the producer to be known and eligible in authoritative
membership. This predicate defaults to deny until assembly wires it. Current views and relay
caches recheck eligibility, so an already-cached source disappears immediately after removal
even before its timestamp expires. Unknown or removed senders still receive ordinary pong
responses; a response is not acceptance of their metrics.

The collector accepts observations no older than 30 seconds and no more than 5 seconds in the
future relative to its clock. Deployment clocks must stay within that skew allowance. Rejected
samples do not refresh existing values. This freshness window is an operational bound, not an
ownership lease or proof of exclusive execution. Historical samples use original observation time,
not arrival time. Repeated delivery through a different core adds no new historical sample.

Counters remain cumulative within an incarnation. Rates require differences of successive samples
from the same incarnation divided by their observation-time difference; resets start a new baseline.
Negative differences must not be treated as negative demand. Ratios carry explicit units; a ratio
of 0.2 means 20 percent. Weighted means merge sums and counts. Percentiles require merging compatible
histograms; averaging per-node percentiles or taking percentiles of per-node means is invalid.

Raw node observations and aggregate summaries are different inputs, not additive collections.
The current `CommunityMetricsSnapshot` name is historical: `WorkerMetricsAggregator` publishes
one producer's own per-slice metrics with `memberCount = 1`; `governorId` identifies that producer.
Each report carries producer incarnation and sequence; the control loop rejects duplicate, older,
expired, and excessively future reports. It accepts only `memberCount = 1`, keys by producer, and
requires fresh reports from every remotely ACTIVE placement before permitting a scaling decision.
Missing coverage produces `METRICS_INCOMPLETE`, rather than a zero-load scaling decision.
A true community aggregate must not be
introduced into that same additive path without explicit contributor coverage, window, ownership
version, and replacement semantics. In particular, a handoff must not count old-governor and
new-governor aggregates as two disjoint communities.

### 2.1 Comprehensive metrics and minute rollups

Per-method completion recording and snapshot/reset share a short monitor-protected section.
A snapshot therefore observes matching counts, outcomes, duration totals and histogram entries,
rather than combining independently sampled atomic fields. This serializes simultaneous
completions of the same method; throughput validation must include that contention.

The comprehensive collector normalizes per-method cumulative counters into monotonic process-local
totals. A newly appearing or reappearing method establishes a baseline; unknown earlier activity
is not attributed to the current interval. A method disappearing never subtracts its prior work.
A decrease in its counters establishes a reset baseline without resetting the shared invocation
collector. Derived and minute calculations difference consecutive samples, reject non-increasing
sample timestamps, and retain the baseline across minute transitions and explicit flushes.

Minute totals conserve observed increments. An interval crossing a minute boundary belongs to
its closing observation's minute; the system does not invent intra-interval timing. The failure
ratio is summed failures divided by summed calls, not a mean of per-sample ratios. GC rates and
pause totals likewise use cumulative differences. Zero activity yields zero rates.

`intervalMeanLatencyP50`, `intervalMeanLatencyP95`, and `intervalMeanLatencyP99` explicitly describe
the distribution of observed interval means. They are not request-level percentiles. Management
responses, TTM feature names, training inputs, and threshold consumers use the explicit names.
Per-method request-latency estimation remains a separate measurement. Unequal-volume intervals
must distinguish these meanings in tests; no multiplication of an average can reconstruct a tail.

## 3. Distribution

The existing routine scheduler remains leader-driven; permission for arbitrary members to exchange
pings does not imply routine all-to-all scheduling. Every connected peer remains eligible for a
liveness exchange. Recipient role determines metric payload:

- Core recipients receive all currently fresh producer observations.
- Worker recipients receive the sender's own observation only.
- The spokesman-to-governor path likewise sends only the spokesman's own observation.
- Pongs contain only responder-owned metrics; ancillary peer health/connectivity signals retain
  their existing meanings.

Typed per-slice reports follow the same core-only distribution boundary through
`SourceMetricsBatch`. A core receiving a direct source report stores it and, if it is not leader,
forwards that report once to the current leader. A received batch is stored without forwarding.
The leader periodically publishes its fresh report cache in batches of at most 128 sources to
other cores. This covers worker-to-nonleader-core uplinks without a rebroadcast loop, preserves
source versions during handoff, and makes the typed scaling feed available after leader failover.

Local sampling is cached for one second per incarnation; receiving many chunks does not multiply
local collection work or historical sample count. Each collection cycle captures one immutable observation map. It partitions that map into messages
of at most 128 producers and sends every batch to each core recipient within that cycle. It must
not rotate one batch per second: at 10K producers that would create about 79 seconds of coverage
lag. The 128 limit bounds producer entries, not arbitrary metric-label byte size. Label cardinality
and encoded byte size remain workload-dependent capacity inputs that must be measured.

Only the first core batch carries authority metadata. Other batches set `carriesAuthority=false`
and omit drain/eviction/readiness/provisioning fields. The worker exchange carries its existing
control hints but omits the global readiness and provisioning rosters. All production batches set
`completeMetricsRoster=false`: omission from a chunk is never evidence of producer removal.
Membership verdicts remove producers; freshness removes expired samples from current views.

For N producers and K core consumers, detailed dissemination is O(K*N), not O(N*N). No claim of
10K throughput follows from this complexity bound alone. Core CPU, payload cardinality, transport
bandwidth, snapshot processing, and failure bursts require measurement.

The metrics lane currently refuses writes under transport backpressure rather than creating the
consensus retry queue. A subsequent cycle republishes current observations; it does not enqueue
an application-level backlog of missed historical cycles. Missing coverage is a degradation signal.
The metrics protocol cannot guarantee heartbeat delivery during sustained metrics-lane saturation;
capacity testing must establish headroom and a future priority transport split must preserve these
contracts if introduced.

## 4. Authority is independent of observation transport

Assembly injects authoritative core-role and current-leader predicates. Defaults deny authority and
full-metrics recipient eligibility. A worker's high claimed term must not advance observed Rabia
term, mutate epoch, execute drain/eviction, or replace provisioning state. A stale leader still
receives its pong, but its stale control effects are ignored. Core-absence observation refreshes on ping or pong from an identified core sender, independently
of leadership and observed term; authority checks remain separate.

The authority predicate governs effects, not envelope acceptance. Fencing versions do not order
metric samples; producer incarnation and sequence do. There is no permission for a ping to change
node role or grant disconnected autonomous operation.

## 5. Coverage, predictions, and retained history

A missing, rejected, or expired source is unknown, not an idle node. Consumers compare fresh
producer identities against their expected committed placement; they must not derive zero load
from absent samples. The in-memory operational history keeps at most 120 point samples per producer across the
configured retention window (one minute resolution for the default two hours). The history API
publishes `resolutionMs`. Point sampling does not claim to preserve every spike or aggregate a
histogram; current observations and control calculations retain their independent one-second
sampling. This avoids 72 million raw snapshots per core at 10K producers. Metric cardinality still
determines the byte cost of the retained 1.2 million maximum point samples. This operational
history is not a seasonal forecasting archive.
Yearly patterns require durable time rollups keyed by region/market and explicit campaign inputs,
as defined by the cluster supervision specification. Those forecasts must retain coverage quality
and must not merge calendar periods across markets indiscriminately.

## 6. Validation and acceptance

1. Non-leader and worker pings receive pongs without acquiring authority.
2. A stale authority term still receives a response and cannot repeat drain effects.
3. Producer duplicates and reordered messages create one original-time history entry.
4. Old incarnations, expired samples, and excessively future samples cannot replace current data.
5. Partial batches cannot prune unrelated producers; ancillary effects occur only on their marked batch.
6. A collection larger than 128 producers reaches cores completely through bounded batches; workers
   receive only the sender sample and no global readiness/provisioning roster.
7. Generated production codecs round-trip observation identity and batch authority flags.
8. Existing lifecycle and membership signal tests remain green after the explicit authority wiring.
9. Scale validation measures encoded bytes and tail collection age at 1K/10K producers, multiple
   core counts, reconnect bursts, and slow core receivers. It reports missing coverage explicitly.

The historical contributor document `../contributors/metrics-control.md` describes earlier
leader-broadcast assumptions. This specification takes precedence for ping/pong distribution and
sample identity; it does not reinstate the old LLM actuation model.
