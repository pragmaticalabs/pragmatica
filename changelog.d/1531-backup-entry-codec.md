### Changed (2026-09-26 — #1531: binary per-entry KV backup format)
- **New `BackupEntryCodec` replaces the hand-written `KVStoreSerializer` pipe grammar** (which had no
  production caller). A backup document is a four-line header (format version, revision, a cluster
  incarnation placeholder, entry count) followed by one line per entry: base64 of the value's generated
  wire codec, then the key's canonical string. Encoding keeps backed-up entries only and refuses keys
  that would not parse back to themselves; decoding refuses runtime keys, non-canonical keys, and values
  that do not re-encode to the stored bytes, and reports every bad line with its number as a `Result`
  failure, never an exception. Every backed-up key type must have a byte-exact round-trip fixture
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/kvstore/BackupEntryCodecTest.java`] —
  unit-level only; no restore path uses it yet [design intent — unverified].
  **Known gap:** an operator's scheduled-task pause lives in the runtime `ScheduledTaskValue`, so a
  cold restart loses it.
