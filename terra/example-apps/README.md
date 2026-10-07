# Existing applications on Terra

These modules compile the original `examples/` sources in separate outputs and preserve their logical artifact coordinates. They do not copy or fork business code. Each slice module retains its own resource defaults; application modules copy the original schema scripts during packaging.

After the repository's normal bootstrap (Java 25, isolated Maven repository), from the repository root:

```sh
mvn -pl terra/example-apps/tests -am install -DskipTests
mvn -f terra/example-apps/pom.xml clean install
```

The second command builds all application archives. Database tests explicitly skip unless `terra.test.jdbcUrl` names a local PostgreSQL administration database with a `user` URL parameter and permission to create test databases:

```sh
mvn -f terra/example-apps/pom.xml clean install \
  -Dterra.test.jdbcUrl='jdbc:postgresql://127.0.0.1:5432/postgres?user=test_user'
```

Tests create and drop only their uniquely named databases, including a separate analytics database. They launch extracted ZIPs outside the checkout for HTTP/process proofs and use the public embedding API for applications without declared HTTP routes.

| Application | Original slices | Executed proof |
| --- | --- | --- |
| Ecommerce | InventoryService, PricingService, PaymentService, FulfillmentService, PlaceOrder | Extracted process: full HTTP order, stock/payment/shipment writes, restart/history reuse. The demo's intentional 5% declines are accepted only with successful stock compensation. |
| URL shortener | UrlShortener, Analytics | Extracted process: API-key enforcement, shorten/resolve, click pub-sub, database persistence and restart. |
| URL shortener v2 | Same interfaces, original version `1.0.1` | Same process proof, with typed topic constants. This example has no original route files; its Terra build reuses v1 route configuration while preserving v2 source/response behavior. |
| Pricing engine | CatalogSlice, DiscountSlice, TaxSlice, AnalyticsSlice | Extracted process: pricing across dependencies and high-value event persistence. |
| Step composition | OrderProcessor and its original steps | Real database: order persistence and pub-sub delivery to the exact injected OrderEventListener instance. |
| Banking | AccountService, ExchangeRateService, FraudDetectionService, TransferService | Real database: account creation, credit, transfer, and LOCAL cache invalidation across keyed multi-parameter calls. Transfer history remains the example's in-memory history; this is not a durable banking saga. |
| PG showcase | OrderProcessing | Real database: generated persistence creates and reads an order. No original HTTP routes are declared. |
| Comprehensive persistence | OrderSlice | Two databases: migration, customer/order joins, arrays, aggregates, and analytics reads. **Partial original example:** nullable `deleted_at` and empty aggregate results map to non-optional fields and fail explicitly. Tests pin these failures and successful populated queries; no synthetic null values are introduced. |

Together with Catalog in `terra/examples`, this is 21 original slice compilations (including the two URL-shortener versions), plus the small Terra fixtures. Notification hub remains unsupported because it requires streams; see [the stream/entity investigation](../STREAMS-AND-ENTITIES.md). `jooq-xml-showcase` is a schema-export POM and has no slice sources to target.

## Run a bundle

Extract `<module>/target/terra-<module>-1.0.0-rc4-terra.zip`, set `JAVA_HOME` to Java 25, configure the database, then run `sh bin/terra`. Both JDBC and native PostgreSQL URLs must point at the intended database when the owning JAR defaults contain both transports. For example:

```sh
export TERRA_DATABASE__JDBC_URL='jdbc:postgresql://127.0.0.1:5432/app?user=app'
export TERRA_DATABASE__ASYNC_URL='postgresql://127.0.0.1:5432/app'
export TERRA_DATABASE__USERNAME=app
export TERRA_DATABASE__PASSWORD='your-password'
export TERRA_HTTP__PORT=8080
sh bin/terra --check
sh bin/terra
```

For URL shortener, set `TERRA_API_KEYS__CLIENT__KEY` and send `X-API-Key` for protected routes. Comprehensive persistence additionally needs `database.analytics` configuration, using the same double-underscore environment naming. Supply credentials outside committed files. These examples retain their original deployment defaults until overridden.

Applications without routes can use `TerraLaunchPlan.load(directory).flatMap(TerraLaunchPlan::startApplication)` and typed `application.slice(...)`; the embedding caller must await `application.close()`.
