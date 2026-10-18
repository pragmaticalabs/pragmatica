### Fixed
- Keep shared HTTP authentication internal to the runtime bundles and record all Terra/shared modules in the Maven Central publication decision table, preserving the existing published dependency closure.
- Terra (`terra/**`) and the modules it shares with Aether (`aether/db-migrations`, `aether/http-security`) are Apache-2.0 under the rc4 licence boundary (ADR 0014); the BSL `terra/LICENSE` is removed and the moved files carry the Apache header.
