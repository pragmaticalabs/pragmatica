### Fixed (2026-09-11 — #718: an unbounded per-attempt retry WARN was self-terminating Forge, and the lazy lane re-open was exhausting the QUIC stream credit)
- **`Retry` logged once PER ATTEMPT at WARN, and on the QUIC consensus send wrapper that is a 200x
  multiplier on a hot path.** A stale `forge-data` directory triggers a DHT backpressure burst at
  cluster formation; each backpressured send is wrapped in `200 attempts x 25ms`
  (`QuicTransportTuning`), and every attempt emitted a WARN through the packaged `log4j2.xml`'s
  single **synchronous** Console appender — on the very Netty event-loop threads that have to drain
  the QUIC write buffers for the backpressure to clear. Measured: ~4.2 million log lines in 72
  seconds, the lane mesh stuck at 1 of 10, the 64-stream credit exhausted, `cluster.start()` missing
  its fixed 60s budget at `ForgeServer:401`, and the resulting `System.exit(1)` running the shutdown
  hook — which is the `EmberCluster.stop()` line that made this look like a self-fence. Interleaved
  arms: stale data + stock logging **3/3 fatal** (72s/73s/72s); clean data **2/2 healthy** (4.6s);
  stale data with only this one logger silenced **4/4 healthy** (8.4s), with **84,166 backpressure
  events still occurring and clearing in 3.5s**. The logging was the loop's gain, not its trigger.
- **The per-attempt line is now DEBUG; giving up is WARN and carries the attempt count.** The
  resulting bound is structural rather than measured: **at most one WARN per `Retry.execute()` call,
  independent of `maxAttempts`**. Raise `org.pragmatica.lang.utils.Retry` to DEBUG to get every
  attempt back. Two things make this more signal rather than less:
  - The **spent-budget path logged nothing at all** before this change — `Retry` failed its promise
    silently after burning its whole budget. It now emits exactly one WARN naming the count, which is
    the aggregate the per-attempt lines were being read for.
  - The busiest caller **already documents DEBUG as the level for per-retry lines**
    (`QuicClusterNetwork#retryBackpressuredWrite` logs its own retry and give-up lines at `debug`,
    and `writeIfWritable`'s comment reads "per-retry logging stays at debug below"). The WARN in
    `Retry` was overriding a level the call site had chosen.
  The unretryable-cause WARN is unchanged. `Retry` is a core utility, so this is a repo-wide
  observability change; **no code, test, script or doc in the repository matched the per-attempt
  message text** (searched across all tracked files with `git grep`), so nothing was keying on it.
- **What still reports an 84k-event backpressure burst:** the transport's own
  `Backpressure on peer {} lane {}` WARN at `QuicClusterNetwork#writeIfWritable` — untouched, already
  separate, and **one line per backpressure entry rather than per attempt**. The discriminating
  experiment left that logger fully enabled and recorded all 84,166 of its lines while the cluster
  formed in 8.4s, so the burst remains discoverable with the multiplier gone.
- **Separately, the lazy lane re-open fired per outbound message with no in-flight dedup**, and each
  firing created a stream against `initialMaxStreamsBidirectional(64)`. The coupling was measured as
  **equal counts, not a correlation**: failed lazy re-opens == `STREAM_LIMIT_ERROR`s, **857==857** in
  one run and **639==639** in another. There is now **one in-flight open per (peer, lane)**; messages
  arriving inside the open window are queued onto it (bounded at 64, drop-oldest, mirroring the
  offline buffer's policy) and written when it completes. New counters
  `quic_stream_zombie_lazy_open_coalesced_total` and `quic_stream_zombie_lazy_open_drops_total` make
  the yield and any overflow visible; the drop is counted, not warned, deliberately.
  **The BACKSTOP eviction stays reachable** — a coalesced message is a distinct admission outcome
  from a failed open, so deduplication never reports "unhealable" and a genuinely dead lane is still
  evicted for a clean re-dial.
- **`registerStream` overwrote a live lane stream without closing it**, leaving it open but
  unreachable (the lane resolves to the replacement) and holding one of the connection's 64 stream
  credits for the life of the connection. The displaced stream is now closed. Bound stated honestly:
  writes already buffered on it at the instant of replacement are failed by the close — they were
  already unreachable, and recovery is the retransmit's job, which is the convention this send path
  already documents for the outcomes it reports as optimistically `Sent`.
- **#718's own earlier measurements are no longer comparable across this change.** Any count derived
  from `Operation failed (attempt N/M)` lines — including the `6,525` / `10,630` figures in the
  ticket — measured a line that no longer appears at default levels. Re-measure against the
  coalesced/drop counters and the transport's backpressure WARN instead.
- **Not addressed here, and both are real:** what a stale `forge-data` should mean at boot (an
  unreadable snapshot set currently boots silently and the runbook never mentions the directory), and
  Forge's fixed 60s formation budget sitting at exactly the same value as the 60,000ms higher-id
  force-dial grace in `QuicClusterNetwork` with nothing relating the two. Neither is ruled; neither is
  bundled into this fix.
  [mechanism: level demotion plus a count-carrying terminal WARN in `Retry`; a per-(peer, lane)
  in-flight marker whose lifetime is the `QuicPeerConnection` itself, so an eviction and re-dial
  clears a leaked marker instead of suppressing that peer's healing permanently —
  `RetryLoggingBoundTest` (`core`), `QuicPeerConnectionLaneOpenDedupTest` and
  `QuicClusterNetworkStreamZombieTest$LazyOpenDedup` (`integrations/consensus`)]
  **What is NOT verified here:** the 60s-timeout outcome itself. That is a timing and volume claim
  about a live five-node Forge cluster, and Forge's QUIC base port is a hard-coded constant
  (`ForgeServer:243`) so two concurrent local runs always collide silently — no Forge run was made
  for this fix. The pins assert what is deterministic: that the per-attempt line is no longer emitted
  per attempt, that the detail survives at DEBUG, that the give-up line reports the count, that a
  burst opens one stream and loses nothing, and that the BACKSTOP still evicts.
