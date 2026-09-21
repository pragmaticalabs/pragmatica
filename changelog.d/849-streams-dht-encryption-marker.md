### Fixed (2026-09-21 — #849: the `streams` DHT namespace now carries the encryption marker)
- **Switching `streams_encrypted` off over encrypted stream segments in the DHT no longer boots.**
  The `stream-segments` DHT namespace had neither the marker write on first enable nor the
  reverse-direction check that every `<name>-blocks` namespace has had since #830/#858: its tier was
  built ungated and the `streams` setup carried no `DhtMarkerCheck`, so a node with
  `streams_encrypted = false` (or the keyring gone) over a namespace already holding encrypted
  segment blocks was admitted and reported ready. What it then served was not the ciphertext the
  ticket predicted — `StorageInstance.get` is content-addressed — but `StorageError.IntegrityError`
  on **every** segment read, a configuration error presenting as per-block data corruption with
  nothing naming the cause. The streams DHT tier now goes through the same `maybeEncryptDht` path as
  the per-instance tiers: on the post-formation admission `AetherNode.start()` already runs, an
  encrypted boot writes `stream-segments/.encryption-enabled` (the active key id), and a plain boot
  over a marked namespace refuses with `EncryptionError.EncryptedTierRequiresKeyring("streams", …)`
  before any read — the read gate resolves with that cause, so no segment read reaches the integrity
  check. An unmarked namespace booted plain is admitted as before; pre-GA, no migration path
  (2026-09-16 ruling).
- `StorageFactoryEncryptionTest` gains the reverse-direction reproduction (encrypted boot with the
  segment disk tier unavailable, so the DHT holds the only durable copy; then a plain reboot), the
  forward-direction marker write, and the unmarked-plain control. The `known-limitations.md` and
  `configuration.md` lines that declared the gap are updated.
