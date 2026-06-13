package org.pragmatica.aether.example.composition;

import org.pragmatica.aether.example.composition.shared.Money;
import org.pragmatica.aether.example.composition.shared.OrderId;
import org.pragmatica.aether.resource.db.Sql;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;


/// Persistence step — requires @Sql SqlConnector.
///
/// This step demonstrates TRANSITIVE RESOURCE PROVISIONING:
///
/// 1. PersistOrder is a plain interface (not @Slice) — it is discovered
///    as a dependency of OrderProcessor by scanning the slice factory params.
///
/// 2. The `persistOrder(@Sql SqlConnector db)` factory has a parameter
///    annotated with @Sql, which is a @ResourceQualifier.
///
/// 3. The annotation processor detects this and generates:
///    `ctx.resources().provide(SqlConnector.class, "database")`
///    in the OrderProcessor's generated wiring code.
///
/// 4. The provisioned SqlConnector is passed to `persistOrder(db)` when
///    constructing the step instance.
///
/// This is "transitive" because the @Sql annotation is on the step's factory,
/// not on the slice's factory. The processor resolves it automatically by
/// following the dependency chain: OrderProcessor -> PersistOrder -> @Sql.
public interface PersistOrder {

    Promise<Order> apply(ValidateOrder.ValidOrder order);

    /// Persisted order record — the output of successful persistence.
    record Order(OrderId orderId, String customerId, String productId, int quantity, Money amount) {}

    String INSERT_ORDER = """
        INSERT INTO orders (id, customer_id, product_id, quantity, amount)
        VALUES (?, ?, ?, ?, ?)""";

    /// Factory takes @Sql SqlConnector — triggers transitive resource provisioning.
    /// The annotation processor detects @Sql on the parameter and provisions
    /// a SqlConnector for the parent slice (OrderProcessor).
    static PersistOrder persistOrder(@Sql SqlConnector db) {
        return order -> db.update(INSERT_ORDER,
                                  order.orderId().value(),
                                  order.customerId(),
                                  order.productId(),
                                  order.quantity(),
                                  order.amount().value())
                          .map(_ -> new Order(order.orderId(),
                                              order.customerId(),
                                              order.productId(),
                                              order.quantity(),
                                              order.amount()));
    }
}
