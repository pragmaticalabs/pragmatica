### Fixed
- Preserve PostgreSQL wire format in generic typed and untyped row reads, including timestamps, numeric values, booleans, and byte arrays. Previously binary values could be passed to text parsers.
- A `String` read of a binary-format column decodes the value by the column's type and then formats it (`9000000000`, `true`, a canonical UUID, an ISO date or instant, `\x` hex for bytea); it used to return the raw wire bytes as garbage text.
- PostgreSQL `infinity` and `-infinity` for `date`, `timestamp` and `timestamptz` read in binary now fail by name (`no LocalDate/Instant representation`) in both the typed and the generic accessors, instead of decoding to a wrong date millions of years away. The latest valid timestamps (year 294276) no longer wrap on a microsecond overflow.
