### Fixed
- Keep shared HTTP authentication internal to the runtime bundles and record all Terra/shared modules in the Maven Central publication decision table, preserving the existing published dependency closure.
- Terra (`terra/**`) and the modules it shares with Aether (`aether/db-migrations`, `aether/http-security`) are Apache-2.0: the root `LICENSE` makes Apache-2.0 the default and lists the BSL modules, and none of these is one; the BSL `terra/LICENSE` is removed and the moved files carry the Apache header.
