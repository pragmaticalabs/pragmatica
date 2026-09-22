### Fixed (2026-09-21 — #1164: SWIM gossip "daily" derived-key rotation happened at boot only, so a node up ≥2 days and a node booted ≥2 days later could not decrypt each other)
- **`SelfSignedCertificateProvider` derived the previous/current/next-day gossip keys once, at
  construction, and never again.** The accept window is one day either side, so a node up for two
  days still encrypted under its boot-day key while a node booted today derived a key two days on:
  neither held the other's key and SWIM silently partitioned them — a replacement or scale-up node
  in any cluster older than two days joined nothing, with the only symptom `Failed to decrypt gossip
  from <id>` on the seeds. [verified: `SelfSignedCertificateProviderTest.DayRollover`,
  `DerivedGossipKeyDayRolloverTest` — red on the base with `Unknown gossip key ID: <D+2 id>`]
- The provider now re-derives the three keys when the clock's day differs from the cached one
  (`keysForToday`, a `java.time.Clock` seam; the one-argument factory keeps `Clock.systemDefaultZone()`
  so the day label is unchanged), and `SwimGossipEncryptors` builds a `ProviderKeyedGossipEncryptor`
  that rebuilds its AES-GCM delegate whenever the provider's current key id changes — so both the
  sending key and the accept window follow the current day for the life of the process.
  [mechanism: `SelfSignedCertificateProvider.keysForToday`; `SwimGossipEncryptors.ProviderKeyedGossipEncryptor.current`]
- **Guarantee, stated precisely:** two nodes whose **host calendar days** differ by at most one can
  always decrypt each other's gossip regardless of uptime, because each encrypts under its own current
  day's key and accepts the previous and next day's. The window survives a rollover as well as a boot:
  the delegate rebuilt on a day change carries both neighbours too, so a node up for weeks has the same
  window as one booted a minute ago.
  [verified: `DerivedGossipKeyDayRolloverTest.RealProvider.oneDayApart_decryptEachOther`,
  `.nodeUpTwoDays_andNodeBootedTwoDaysLater_decryptEachOther`,
  `.rolledOverNode_stillAcceptsPreviousAndNextDay`]
- The skew bound is unchanged: two days apart is still rejected.
  [verified: `DerivedGossipKeyDayRolloverTest.RealProvider.twoDayClockSkew_isStillRejected`]
- A KV gossip-key rotation (#683) still supersedes the derived scheme: the day-following encryptor is
  the delegate `RotatingGossipEncryptor.rotate` replaces, so a rotated node keeps the delivered key
  across day changes. [verified: `DerivedGossipKeyDayRolloverTest.RealProvider.kvRotation_isNotUndoneByDayRollover`]
- `SECURITY.md` names the mechanism behind "daily key rotation with overlap" instead of asserting it.
- **The guarantee above is about host calendar days, not UTC clocks — that gap is #1415.** The day
  label is the **system default zone's** calendar day (`LocalDate.now()` before,
  `LocalDate.now(Clock.systemDefaultZone())` now), not UTC as the #256 comments say. The zone
  disagreement is *not* confined to midnight and is *not* covered by the one-day window: two nodes at
  the **same `Instant`** whose hosts sit in far-apart zones are **two** calendar days apart and fail to
  decrypt in both directions. Pre-existing and deliberately unchanged here — this fix must not alter
  the derivation.
  [unverified: measured in-process through the production factory (same `Instant`, far-apart zones,
  both directions, against a same-harness positive control that decrypts), never on a real
  multi-zone cluster.]
