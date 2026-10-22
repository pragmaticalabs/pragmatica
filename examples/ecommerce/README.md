# Ecommerce — multi-slice order domain

Multi-module example showing the `@Slice` pattern with inter-slice dependencies, value objects, error handling and JBCT patterns, in an order-placement domain. `place-order` orchestrates four other slices; three of them persist to PostgreSQL.

## Modules

| Module | Role |
|---|---|
| `shared` | Value objects used by all slices (`Money`, `Address`, `Email`, `OrderId`, `CustomerId`, `ProductId`, `Quantity`, `LineItem`). Not a slice. |
| `inventory` | `InventoryService`: stock tracking and reservations. Has `resources.toml` and a `schema/` migration. |
| `pricing` | `PricingService`: price calculation, discounts and taxes. |
| `payment` | `PaymentService`: credit card payment processing. Has `resources.toml` and a `schema/` migration. |
| `fulfillment` | `FulfillmentService`: shipping and tracking. Has `resources.toml` and a `schema/` migration. |
| `place-order` | `PlaceOrder`: coordinates inventory, pricing, payment and fulfillment. Exposes `POST /api/v1/orders/` (see its `routes.toml`) and produces the deployable blueprint. |

Each slice module has `slices/<Name>.toml` and a `routes.toml` under `src/main/resources`. Framework artifacts (`slice-api`, `slice-annotations`, `http-routing-adapter`, `resource-api`, `core`) are `provided`; the Aether node supplies them at runtime. The parent `pom.xml` binds the `jbct-maven-plugin` goals `package-slices`, `generate-blueprint` and `install-slices`.

## Configuration

- `aether.toml`: runtime configuration, including the `[database]` section (PostgreSQL `localhost:5432`, database/user/password `forge`).
- `forge.toml`: Forge cluster configuration (`nodes = 7`, management port `5150`, Forge API port `8888` (`forge_port` in the file), app HTTP port `8070`).
- `src/main/resources/schema/` (`V001__create_tables.sql`, `V002__seed_data.sql`): schema and seed data; per-slice migrations live under each module's `src/main/resources/schema/`.

## Build

The example builds on its own once the platform artifacts (`1.0.0-rc5`) are resolvable: from Maven Central after rc5 is published, otherwise after `mvn install` from the repository root.

```bash
mvn -f examples/ecommerce/pom.xml install -P '!test-plan-gate'
```

`-P '!test-plan-gate'` disables the root pom's `test-plan-gate` profile, which needs `test-plan-witness`; that artifact is not on Maven Central.

## Run on Forge

1. Start PostgreSQL (docker or podman, image `postgres:18-alpine`, container `forge-postgres`, port 5432):

   ```bash
   ./start-postgres.sh            # start and wait until pg_isready answers
   ./start-postgres.sh --reset    # drop the container and volume, recreate
   ./stop-postgres.sh             # stop, data persists in the forge-pgdata volume
   ./stop-postgres.sh --purge     # stop and delete the data volume
   ```

   Schema is applied by Aether's migration engine when the blueprint is deployed, not by the script.

2. Build and start the Forge cluster with the `place-order` blueprint (`org.pragmatica-lite.aether.example:place-order:<version>:blueprint`):

   ```bash
   ./run-forge.sh                       # build + start
   ./run-forge.sh --skip-build          # start without rebuilding
   ./run-forge.sh --forge-from-source   # contributor path: build Forge and the platform from this checkout
   ```

   Forge comes from the installer (see the [Quick Start](../../README.md#quick-start)). The script prints the endpoints: Forge API `http://localhost:8888`, app HTTP `http://localhost:8070` (nodes 8070-8076), management `http://localhost:5150`.

   `run-forge.sh` also accepts `--with-load`, which passes `load-config.toml`; that file is not present in this directory.

3. Place an order:

   ```bash
   curl -fsS -X POST http://localhost:8070/api/v1/orders/ \
     -H "Content-Type: application/json" \
     -d '{"customerId":"CUST-001","items":[{"productId":"LAPTOP-PRO","quantity":1}],
          "shippingAddress":{"street":"123 Main St","city":"San Francisco","state":"CA","postalCode":"94105","country":"US"},
          "paymentMethod":{"cardNumber":"4532015112830366","expiryMonth":"12","expiryYear":"2027","cvv":"123","cardholderName":"John Doe"},
          "shippingOption":"STANDARD"}'
   ```

## Load tests (k6)

Requires [k6](https://k6.io) and a running Forge cluster. `k6/env.sh` reads node count and base port from `forge.toml`; the load balancer port defaults to 8080 (`FORGE_LB_PORT`).

```bash
./k6/run-steady.sh [rate] [duration] [warmup]   # constant rate via the LB (default 200 req/s, 2m, 30s warmup)
./k6/run-ramp.sh                                # ramp-up to find the saturation point
./k6/run-spike.sh                               # sudden bursts to test recovery
./k6/run-per-node.sh [rate-per-node] [duration] # direct to each node, bypassing the LB
```

The k6 scripts are `k6/load-test.js`, `ramp-up.js`, `spike.js` and `per-node.js`.
