### Fixed (2026-09-28 — #1630: streams age learning re-read an undecryptable or corrupt block on every pass)
- **A segment whose block failed its content check or could not be decrypted was re-read on every retention
  pass, forever, and logged only at DEBUG.** Only a missing block and a corrupt record were treated as permanent,
  so every other failure counted as transient, a missing encryption key included. The rule is now inverted: only
  a timeout or an I/O error is retried at the next pass, and any other read failure is remembered for the process
  lifetime. A remembered segment is withheld from age-based retention (never aged out wrongly) and reported in
  the one WARN. Recovery: restore the key or the block, then restart the node, which forgets the remembered set.
  Pinned by `RetentionAgeLearningBoundsTest`: an integrity failure and a decryption failure are each read once,
  and an I/O error and a timeout are each retried.
