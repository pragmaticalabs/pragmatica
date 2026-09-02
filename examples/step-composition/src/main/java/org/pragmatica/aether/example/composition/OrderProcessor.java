package org.pragmatica.aether.example.composition;

import org.pragmatica.aether.example.composition.shared.OrderId;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


/// Step Composition Example — Slice -> Step -> Leaf
///
/// Does NOT demonstrate: failure handling, compensation, or tests. Every step is written on the
/// happy path and the module has no test sources — it exists to pin the annotation processor's
/// transitive-provisioning output, not to model an order domain.
///
/// This @Slice demonstrates the JBCT composition pattern where:
/// - The slice orchestrates steps (Sequencer pattern)
/// - Steps are plain interfaces with factory methods (not @Slice)
/// - Steps get their resources via transitive provisioning
/// - Steps can have method-level annotations (subscriptions) detected transitively
///
/// ## How the annotation processor handles this
///
/// 1. Scans `orderProcessor(ValidateOrder, PersistOrder, OrderEventListener)` parameters
///
/// 2. For each parameter, classifies it:
///    - ValidateOrder: plain interface, factory `validateOrder()` has zero params -> direct construction
///    - PersistOrder: plain interface, factory `persistOrder(@Sql SqlConnector)` -> transitive @Sql resource
///    - OrderEventListener: plain interface, factory `orderEventListener(@Sql SqlConnector)` -> transitive @Sql
///      AND method `onOrderPlaced()` has @OnOrderEvent -> transitive subscription
///
/// 3. Generates resource provisioning code:
///    `SqlConnector db = ctx.resources().provide(SqlConnector.class, "database")`
///
/// 4. Generates step construction:
///    ```
///    ValidateOrder validate = ValidateOrder.validateOrder();
///    PersistOrder persist = PersistOrder.persistOrder(db);
///    OrderEventListener listener = OrderEventListener.orderEventListener(db);
///    ```
///
/// 5. Generates subscription manifest entry for `listener::onOrderPlaced`
///    with qualified name `listenerOnOrderPlaced`
///
/// 6. Generates slice instantiation:
///    `OrderProcessor.orderProcessor(validate, persist, listener)`
///
/// ## Sequencer pattern
///
/// The `processOrder` method implements a Sequencer: a chain of 2-5 dependent steps
/// where each step receives the output of the previous one. The chain is:
/// validate -> persist -> build confirmation.
@Slice
public interface OrderProcessor {

    record OrderRequest(String customerId, String productId, int quantity, String amount) {}

    record OrderConfirmation(OrderId orderId, String status) {}

    Promise<OrderConfirmation> processOrder(OrderRequest request);

    /// Factory takes step interfaces as dependencies.
    ///
    /// The annotation processor detects these as plain interfaces (not @Slice,
    /// not @ResourceQualifier) and finds their static factory methods for
    /// transitive dependency resolution.
    ///
    /// The `listener` parameter is injected for its subscription side-effect —
    /// its @OnOrderEvent method is registered transitively in the manifest.
    /// It does not participate in the request processing chain directly.
    static OrderProcessor orderProcessor(ValidateOrder validate,
                                         PersistOrder persist,
                                         OrderEventListener listener) {
        // listener is injected for its subscription side-effect —
        // its @OnOrderEvent method is registered transitively
        return request -> validate.apply(request)
                                  .flatMap(persist::apply)
                                  .map(order -> new OrderConfirmation(order.orderId(), "CONFIRMED"));
    }
}
