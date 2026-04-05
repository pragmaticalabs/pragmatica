package org.pragmatica.aether.example.composition;

import org.pragmatica.aether.resource.db.Sql;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Event listener step — has a method-level @OnOrderEvent annotation.
///
/// This step demonstrates TRANSITIVE METHOD-LEVEL ANNOTATION detection:
///
/// The annotation processor performs the following chain of discovery:
///
/// 1. OrderEventListener is a plain interface dependency of OrderProcessor
///    (appears as a parameter in the slice's factory method).
///
/// 2. `orderEventListener(@Sql SqlConnector db)` has a @ResourceQualifier
///    parameter — triggering transitive resource provisioning (same as PersistOrder).
///
/// 3. `onOrderPlaced()` has an @OnOrderEvent annotation on the method itself.
///    @OnOrderEvent is a @ResourceQualifier(type = Subscriber.class, config = "messaging.order-events").
///
/// 4. The processor generates a qualified method name by combining the step
///    parameter name from the slice factory and the method name:
///    `listener` + capitalize(`onOrderPlaced`) = `listenerOnOrderPlaced`
///
/// 5. A subscription entry is written to the slice manifest:
///    `topic.subscription.N.method = listenerOnOrderPlaced`
///
/// 6. The method reference `listener::onOrderPlaced` is included in the
///    generated Slice.methods() list so Aether can dispatch events to it.
///
/// At runtime, when an OrderPlacedEvent arrives on the "messaging.order-events"
/// topic, Aether invokes `listenerOnOrderPlaced`, which writes an audit record.
public interface OrderEventListener {

    @OnOrderEvent
    Promise<Unit> onOrderPlaced(OrderPlacedEvent event);

    String INSERT_AUDIT = """
        INSERT INTO order_audit (order_id, event_type, timestamp)
        VALUES (?, ?, ?)""";

    /// Factory takes @Sql SqlConnector — same transitive resource provisioning as PersistOrder.
    /// The listener writes audit records to the database when order events arrive.
    static OrderEventListener orderEventListener(@Sql SqlConnector db) {
        return event -> db.update(INSERT_AUDIT,
                                  event.orderId(),
                                  "ORDER_PLACED",
                                  event.timestamp().toString())
                          .mapToUnit();
    }
}
