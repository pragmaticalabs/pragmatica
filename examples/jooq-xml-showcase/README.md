# jOOQ XML Export Showcase

End-to-end demo of the **hermetic** jOOQ `XMLDatabase` schema export pipeline
introduced in `aether/pg-tools`. The pipeline turns a directory of
Flyway-style `V*.sql` migrations into a single tracked
`jooq-schema.xml` descriptor that can be fed to `jooq-codegen-maven`'s
`XMLDatabase` for offline code generation - no Docker, no JDBC driver,
no live PostgreSQL at build time.

See the full specification at
[`aether/pg-tools/docs/jooq-xml-export-spec.md`](../../aether/pg-tools/docs/jooq-xml-export-spec.md).

## Module layout

```
examples/jooq-xml-showcase/
├── pom.xml                    # binds export-jooq-xml + check-jooq-xml
├── README.md                  # this file
├── expected-jooq-schema.xml   # tracked golden — committed to VCS
└── schema/
    ├── V001__catalog.sql      # categories, products, enum type, sequence, GIN index
    └── V002__orders.sql       # orders, order_lines, composite PK, cascade FK
```

## How to run

```bash
# 1. Install the plugin to your local ~/.m2 (one-time)
mvn -pl aether/pg-tools/pg-maven-plugin -am install -DskipTests

# 2. Verify the showcase — runs export to target/ then drift check against expected-jooq-schema.xml
mvn -pl examples/jooq-xml-showcase verify
```

During `mvn verify`:

- `generate-sources` → `pg:export-jooq-xml` writes `target/jooq-schema.xml`
  from the migration set.
- `verify` → `pg:check-jooq-xml` compares the current schema against
  the tracked `expected-jooq-schema.xml` and fails the build if they
  diverge.

## Schema patterns demonstrated

| Pattern                            | Where                                                                             |
|------------------------------------|-----------------------------------------------------------------------------------|
| TEXT / INTEGER / BIGINT scalar     | `categories.name`, `products.tenant_id`, `products.id`                            |
| NUMERIC(p, s)                      | `products.price`, `order_lines.unit_price`, `orders.total`                        |
| BOOLEAN with DEFAULT               | `products.active`                                                                 |
| TIMESTAMPTZ with DEFAULT now()     | `products.created_at`, `orders.placed_at`                                         |
| DATE                               | `products.launch_date`                                                            |
| UUID with DEFAULT gen_random_uuid() | `products.external_id`                                                            |
| ENUM type + column use             | `order_status` type, `orders.status` column                                       |
| Composite PRIMARY KEY              | `order_lines PRIMARY KEY (order_id, line_no)`                                     |
| Single-column PRIMARY KEY          | `categories`, `products`, `orders`                                                |
| FOREIGN KEY ON DELETE CASCADE      | `order_lines.order_id` → `orders.id`                                              |
| FOREIGN KEY (default action)       | `products.category_id` → `categories.id`, `order_lines.product_id` → `products.id` |
| UNIQUE column constraint           | `uq_categories_slug`                                                              |
| UNIQUE multi-column constraint     | `uq_products_tenant_slug (tenant_id, slug)`                                       |
| CHECK constraint                   | `chk_products_price`, `chk_orders_total`, `chk_order_lines_qty`, `chk_name_not_blank` |
| ARRAY column (TEXT[])              | `products.tags`                                                                   |
| JSONB column with DEFAULT          | `products.metadata`                                                               |
| CREATE SEQUENCE                    | `product_code_seq`                                                                |
| NOT NULL / DEFAULT nextval(seq)    | `products.code`                                                                   |
| B-tree index                       | `idx_products_category`, `idx_order_lines_product`                                |
| GIN index on jsonb                 | `idx_products_metadata`                                                           |

## Updating the golden file

When a migration change is intentional, regenerate the tracked XML and
commit both files in the same commit:

```bash
mvn -pl examples/jooq-xml-showcase generate-sources
cp examples/jooq-xml-showcase/target/jooq-schema.xml \
   examples/jooq-xml-showcase/expected-jooq-schema.xml
git add examples/jooq-xml-showcase/schema/V00X__*.sql \
        examples/jooq-xml-showcase/expected-jooq-schema.xml
git commit -m "feat(showcase): describe the schema change"
```

CI will run `mvn verify`, which invokes `check-jooq-xml`. If a developer
forgets to regenerate, the check fails with a unified-diff of the first
20 differing lines and a message pointing to `mvn pg:export-jooq-xml`.

## Wiring a real consumer

Downstream slices wire the tracked XML into `jooq-codegen-maven` via
`XMLDatabase`. See §8.3 of the
[specification](../../aether/pg-tools/docs/jooq-xml-export-spec.md) for
the canonical snippet.

## Limitations exercised

Not every pattern from §13.2 of the spec is covered here. Omitted on
purpose because the current `pg-parser` / `pg-schema` pipeline does not
yet surface them as the semantic model elements the jOOQ exporter
reads:

- `CREATE DOMAIN` — parser accepts it but the resulting `domain` type
  is exported via the same fallback path as other user-defined types;
  not separately showcased here.
- Partial indexes (`WHERE ...`) and covering indexes (`INCLUDE (...)`) —
  parsed but predicate/include metadata is not round-tripped through
  the jOOQ XML yet.
- `GENERATED ALWAYS AS ... STORED` columns — parsed but not reflected in
  the exporter output yet.
- Non-public schemas — the exporter already supports them; this
  showcase stays in `public` for clarity.

These are tracked against the exporter, not the showcase; when they
land, expand the schema here and regenerate the golden file.
