### Fixed (2026-09-29 — #1415: gossip day-key derivation used the system default zone, not UTC as documented)
- **The `cluster_secret`-derived SWIM gossip day key was labelled with the host zone's calendar day**, while the
  accept-window reasoning (#256) and every comment describing it assume UTC. Two nodes whose hosts sit in different
  zones were a full day apart for `|offset|` hours around every local midnight, which spends the whole ±1-day accept
  window on the zone offset before any clock skew; any real skew on top meant `UnknownKeyId` and a SWIM partition.
  The day label is now the UTC day of the clock's instant, whatever the host's or the clock's zone
  (`SelfSignedCertificateProvider.utcEpochDay`), and the one-argument factory uses `Clock.systemUTC()`.
  [mechanism: the label is `LocalDate.ofInstant(clock.instant(), UTC)`, so it depends on the instant alone; pinned by
  `SelfSignedCertificateProviderTest.DayRollover.hostsInDifferentZones_deriveTheSameDayKeys`, where providers at one
  instant with `+14:00`, `-12:00` and UTC clocks derive the same key ids, red on the pre-fix code]
- **Upgrade note (pre-GA, no migration path):** on a host whose default zone is not UTC the day label changes. A rolling
  upgrade that mixes old and new nodes on such a host is one day apart until the last node restarts, which is inside the
  ±1-day accept window.
