### Fixed
- Preserve PostgreSQL wire format in generic typed and untyped row reads, including timestamps, numeric values, booleans, and byte arrays. Previously binary values could be passed to text parsers.
- Binary-format NUMERIC handed to the converter as a `Double`, `Float` or `BigDecimal` is decoded from PostgreSQL's base-10000 wire layout (NaN and the infinities map to the matching `double`; a `BigDecimal` request for them is refused by name) instead of being parsed as text. The driver itself requests NUMERIC as text, so this protects direct callers of `DataConverter`.
