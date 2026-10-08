### Fixed
- Preserve PostgreSQL wire format in generic typed and untyped row reads, including timestamps, numeric values, booleans, and byte arrays. Previously binary values could be passed to text parsers.
