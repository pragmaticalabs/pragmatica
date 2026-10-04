### Fixed (2026-10-04 — #784: database URL-derived values were read in the reverse of the transport priority)
- **`DatabaseConnectorConfig` derived the effective host, port, database and credentials from the URL kinds in the
  order jdbc, r2dbc, async**, the reverse of the order the transport is selected in (async, r2dbc, jdbc). A config
  setting `jdbc_url` to host A and `async_url` to host B selected the async transport and then handed it host A. They
  are now read async, then r2dbc, then jdbc, so the connector gets the values its own URL encodes; a URL that cannot
  supply a value falls through to the next kind in the same order. `DatabaseType.fromAnyUrl` follows the same order.
  Single-URL configurations (every shipped one) are unaffected. The `resource-reference.md` caveat that two URL kinds
  were "unsupported" is replaced by the rule.
