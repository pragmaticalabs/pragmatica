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
- **Guarantee, stated precisely:** two nodes whose clocks agree to within one day can always decrypt
  each other's gossip regardless of uptime, because each encrypts under its own current day's key and
  accepts the previous and next day's, and their current days differ by at most one. The clock-skew
  bound is unchanged: two days apart is still rejected.
  [verified: `DerivedGossipKeyDayRolloverTest.RealProvider.twoDayClockSkew_isStillRejected`]
- A KV gossip-key rotation (#683) still supersedes the derived scheme: the day-following encryptor is
  the delegate `RotatingGossipEncryptor.rotate` replaces, so a rotated node keeps the delivered key
  across day changes. [verified: `DerivedGossipKeyDayRolloverTest.RealProvider.kvRotation_isNotUndoneByDayRollover`]
- `SECURITY.md` names the mechanism behind "daily key rotation with overlap" instead of asserting it.
- [unverified: the day label is the **system default zone's** calendar day (`LocalDate.now()` before,
  `LocalDate.now(Clock.systemDefaultZone())` now), not UTC as the #256 comments say; two hosts in
  different zones can disagree on the day near midnight. Pre-existing, not changed here — the fix
  must not alter the derivation.]
