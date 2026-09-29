### Fixed (2026-09-28 — #1630: streams age learning re-read an undecryptable or corrupt block on every pass)
- **A segment whose block failed its content check or could not be decrypted was re-read on every retention
  pass, forever, and logged only at DEBUG.** Only a missing block and a corrupt record were treated as permanent,
  so every other failure counted as transient, a missing encryption key included. The rule is now inverted. Only
  what a retry can change is retried at the next pass: a timeout, an I/O error reading a block file (the disk
  tier reports it as `FileError.ReadFailed`), a tier not yet admitted, or an exhausted VM. Any other read failure
  is remembered for the process lifetime, including a missing block file, which the tier reports as absent. A remembered segment is withheld from age-based retention (never aged out wrongly) and reported in
  the one WARN. Recovery: restore the key or the block, then restart the node, which forgets the remembered set.
  Pinned by `RetentionAgeDiskReadTest`, on the real disk tier: a block file unreadable for one pass has its age
  learned on a later pass, and a missing block file stays remembered. Pinned also by
  `RetentionAgeLearningBoundsTest`: an integrity failure and a decryption failure are each read once, while a
  timeout, an admission wait and an exhausted VM are each retried.
