### Fixed (2026-09-30 — #1767: 02y publish_marker retries a before-write 503, never a 500)
- **The 02y stream-crash suite treated 500, 503 and a dead pin alike** and re-picked another endpoint once, immediately, so a
  ~18s before-write refusal (a zombie link to a partition holder, #1762) cost 3 of 40 acks. `publish_marker` now retries an answered
  503 before-write refusal on the same endpoint (250ms to 2s backoff, 30s budget), re-picks only on 000, and never retries a 500
  (outcome unknown, a resend can duplicate the event). Each absorbed outage is logged and summarised as `02y max-503-wait=Ns retries=M`.
- New stub suite `test-02y-publish-retry.sh` pins the four behaviours.
