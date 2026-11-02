### Fixed
- The jOOQ connectors can select `uuid` and array columns (`UUID`, `String[]`, `Integer[]`, and untyped fields as the driver's native value): the driver no longer refuses the `Object` conversion target jOOQ asks for these types, and converts the value by its column type instead (#2481).
- jsonb operators with a `?` (`?`, `?|`, `?&`) work through the R2DBC adapter when written with jOOQ's `??` escape: the adapter turns `??` into `?` outside quoted text and comments.
