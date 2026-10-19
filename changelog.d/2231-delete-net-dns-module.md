### Removed (2026-10-18 — #2231: the unused `integrations/net/dns` module)
- **The `integrations/net/dns` module is deleted.** Nothing in the repository imported `DnsClient`, `DomainNameResolver` or the other five classes; the cluster transport and SWIM resolve names with Netty's `DnsNameResolver`. The module's `pom.xml`, `aether/pom.xml` and `integrations/net/pom.xml` entries and its 23 tests go with it.
- Docs: the module is dropped from `core/README.md` and `docs/release/maven-central-publish-set.md`, feature 167 is marked removed, and the notification resource spec now plans MX lookups on Netty's `DnsNameResolver`.
- `[mechanism: repo-wide search for net.dns, net/dns, DomainNameResolver and DnsClient finds no importer outside the module]`
