# Terra executable applications

Add `org.pragmatica-lite:terra-launcher` and your Terra-compiled slice/resource JARs as runtime dependencies. Bind `org.pragmatica-lite:terra-maven-plugin:assemble` to `package` (the goal's default phase). See [the example POM](../examples/pom.xml). Maven resolves the candidate JARs; the blueprint selects the slices. Use a separate compilation for Terra and Aether.

Place application files under `src/main/terra/`:

```text
blueprint.toml
terra.toml                 # optional HTTP/auth settings
resources.toml             # optional deployment resource settings
slices/<encoded-artifact>.toml  # optional per-slice overrides
schema/*.sql               # optional database migrations
schema/<datasource>/*.sql
```

`terra:assemble` creates `target/<artifactId>-terra/` and attaches `<finalName>-terra.zip`. It preserves dependency JARs, checks duplicate classes and slice selection, and runs `--check` against the staged classpath. The archive includes library SHA-256 checksums. Use Java 25:

```sh
export JAVA_HOME=/path/to/jdk-25
sh bin/terra --check
sh bin/terra
```

On Windows use `bin\terra.cmd`. Run from any directory. Termination closes admission, drains accepted HTTP work and pub-sub, and releases resources; a handler that never finishes prevents graceful shutdown.

## Configuration

Slice configuration precedence, highest first: `-Dterra.<key>` system properties, `TERRA_` environment, per-slice file, deployment `resources.toml`, the owning slice JAR's `META-INF/resources.toml` (or root `resources.toml`). Per-slice filenames are UTF-8 URL-encoded coordinates, e.g. `com.example%3Aorders%3A1.0.0.toml`. Intrinsic files from unrelated JARs are never combined. Migration configuration uses dynamic settings over deployment resources; keep it consistent with the slice database settings.

Environment path segments use **double underscores**; single underscores are literal. For example, `TERRA_DATABASE__JDBC_URL` supplies `database.jdbc_url`, and `TERRA_HTTP__PORT=8081` overrides the listener port. Supply secrets directly through environment/properties; unresolved secret placeholders are refused.

Host settings live in `terra.toml`, overridden by the same dynamic sources:

```toml
[http]
port = 8080
max_content_length = 10485760
security_mode = "api-key"  # default; also jwt or explicit none
version_mode = "path"     # or header
version_header = "API-Version"
# tls_certificate = "server.crt"  # relative to application directory
# tls_key = "server.key"          # both required for TLS

[api_keys.client]
key = "replace-through-environment"
roles = ["service"]
authorization_role = "VIEWER"  # VIEWER, OPERATOR, ADMIN
```

For example, override that credential with `TERRA_API_KEYS__CLIENT__KEY`. JWT mode uses `[jwt]` with required `jwks_url`, optional `issuer`, `audience`, `role_claim` (default `role`), `cache_ttl_seconds` (3600), and `clock_skew_seconds` (30). Unknown host keys fail. `none` makes undeclared routes public but does not bypass explicitly protected route policies.

`--check` parses the graph/configuration and migration inputs without creating resources or listeners. It cannot establish database connectivity, validate every resource factory's settings, or prove TLS key validity. Those checks happen during startup; a startup error exits nonzero. Health probes are `/__terra/health/live` and `/__terra/health/ready`.

The example archive runs Catalog and composition/cache/pub-sub fixtures without a database. The embeddable PostgreSQL proof is described in [Terra's guide](../README.md).
