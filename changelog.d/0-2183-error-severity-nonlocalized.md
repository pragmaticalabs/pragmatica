### Fixed
- The postgres-async error and notice decoders read the non-localized severity (`V`) when the server sends it, so `level()` is `ERROR` whatever the server's `lc_messages`; `S` stays the fallback for servers older than 9.6 (#2183).
