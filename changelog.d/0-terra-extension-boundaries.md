### Fixed
- Reject null slice instances and null factory/provider completion values at Terra extension boundaries, with diagnostics identifying the slice and operation. Construction failure still releases every scope in reverse order even when one cleanup implementation returns null.
