### Fixed (2026-11-26 — #3805: flaky EmberStoragePrefetchOverSwimTest, the peer's fetch count was 0 after a 160 s wait)
- **The test reported a bare `0L` when the hint-to-prefetch chain stalled, with no way to tell which link failed.** It now
  keeps the reader reading until the peer's prefetch completes (the peer's `warmed` counter), the observed end of the chain,
  and then asserts each link in causal order: hint produced (`hintsSent`), SWIM datagram sent (`swim.sent`), delivered to the
  peer's channel (`swim.received`), hint received (`hintsReceived`), prefetch started (`started`), prefetch completed
  (`warmed`). A stall fails on the first missing link and prints both nodes' prefetch and SWIM counters.
  [verified: aether/ember EmberStoragePrefetchOverSwimTest, with SWIM delivery delayed 170 s the old test failed with `0L`
  and the new one fails naming link 3 with `swim.received=0` and the peer's `dropped=74`]
- The product is unchanged. Hints stay best effort (one datagram to one random peer per probe round, no ack); the delivery
  guarantee the test asserts is eventual prefetch while the block keeps being read, because each further 10 reads re-arm a
  hint. [mechanism: DefaultPrefetchManager.collectHints resets a hinted block's observation count]
- The cause of the one CI stall is not isolated: 40 of 40 local runs passed (20 idle, 20 under a concurrent full `aether/ember`
  build). The next occurrence names the stalled link.
