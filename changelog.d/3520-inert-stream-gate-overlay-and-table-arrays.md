### Fixed (2026-11-24 — #3520: the inert `[retention]`/`[hot]` gate did not see every key the parser reads)
- **A `[retention]` or `[hot]` key supplied by an environment variable, system property or KV overlay was not refused.** The
  gate walked `StreamSection.keys()`, which the provider view fills from the file-backed layers only, while the parser reads
  the same key through `StreamSection.string`. The gate now asks the parser's own key list
  (`StreamConfigParser.DECLARATION_SECTION_KEYS`) through `string`, so a key is refused whichever layer or spelling supplies it.
- **A table array under a stream section (`[[streams.x.retention]]`) was accepted and ignored.** The parser never reads table
  arrays; deploy validation now refuses it as a malformed stream resource (`stream-resource-invalid`) and names the array.
  Activation cannot see table arrays (the configuration provider flattens them away), so this check is deploy-only.
- Values equal to the default stay refused, as before.
