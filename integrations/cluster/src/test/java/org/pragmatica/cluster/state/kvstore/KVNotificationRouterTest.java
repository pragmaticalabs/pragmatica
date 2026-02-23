package org.pragmatica.cluster.state.kvstore;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KVNotificationRouterTest {
    /// Test key hierarchy
    sealed interface TestKey extends StructuredKey permits AlphaKey, BetaKey {}

    record AlphaKey(String id) implements TestKey {}

    record BetaKey(String id) implements TestKey {}

    /// Separate key hierarchy for filter testing
    record GammaKey(String id) implements StructuredKey {}

    @Nested
    class PutDispatch {
        @Test
        void handlePut_dispatches_to_correct_keyType_handler() {
            var received = new ArrayList<String>();
            var router = KVNotificationRouter.<TestKey, String>builder(TestKey.class)
                .onPut(AlphaKey.class, n -> received.add("alpha:" + ((AlphaKey) n.cause().key()).id()))
                .onPut(BetaKey.class, n -> received.add("beta:" + ((BetaKey) n.cause().key()).id()))
                .build();

            router.handlePut(putNotification(new AlphaKey("a1"), "val1"));

            assertThat(received).containsExactly("alpha:a1");
        }

        @Test
        void handlePut_ignores_nonMatching_keyTypes() {
            var received = new ArrayList<String>();
            var router = KVNotificationRouter.<TestKey, String>builder(TestKey.class)
                .onPut(AlphaKey.class, n -> received.add("alpha"))
                .build();

            router.handlePut(putNotification(new BetaKey("b1"), "val"));

            assertThat(received).isEmpty();
        }

        @Test
        void handlePut_calls_multiple_handlers_in_registration_order() {
            var received = new ArrayList<String>();
            var router = KVNotificationRouter.<TestKey, String>builder(TestKey.class)
                .onPut(AlphaKey.class, n -> received.add("first"))
                .onPut(AlphaKey.class, n -> received.add("second"))
                .onPut(AlphaKey.class, n -> received.add("third"))
                .build();

            router.handlePut(putNotification(new AlphaKey("a1"), "val"));

            assertThat(received).containsExactly("first", "second", "third");
        }
    }

    @Nested
    class RemoveDispatch {
        @Test
        void handleRemove_dispatches_to_correct_keyType_handler() {
            var received = new ArrayList<String>();
            var router = KVNotificationRouter.<TestKey, String>builder(TestKey.class)
                .onRemove(BetaKey.class, n -> received.add("beta:" + ((BetaKey) n.cause().key()).id()))
                .build();

            router.handleRemove(removeNotification(new BetaKey("b1")));

            assertThat(received).containsExactly("beta:b1");
        }

        @Test
        void handleRemove_ignores_nonMatching_keyTypes() {
            var received = new ArrayList<String>();
            var router = KVNotificationRouter.<TestKey, String>builder(TestKey.class)
                .onRemove(AlphaKey.class, n -> received.add("alpha"))
                .build();

            router.handleRemove(removeNotification(new BetaKey("b1")));

            assertThat(received).isEmpty();
        }

        @Test
        void handleRemove_calls_multiple_handlers_in_registration_order() {
            var received = new ArrayList<String>();
            var router = KVNotificationRouter.<TestKey, String>builder(TestKey.class)
                .onRemove(BetaKey.class, n -> received.add("first"))
                .onRemove(BetaKey.class, n -> received.add("second"))
                .build();

            router.handleRemove(removeNotification(new BetaKey("b1")));

            assertThat(received).containsExactly("first", "second");
        }
    }

    @Nested
    class KeyTypeFilter {
        @Test
        void handlePut_rejects_keys_outside_keyTypeFilter() {
            var received = new ArrayList<String>();
            var router = KVNotificationRouter.<TestKey, String>builder(TestKey.class)
                .onPut(AlphaKey.class, n -> received.add("alpha"))
                .build();

            // GammaKey is not a TestKey, should be filtered out
            router.handlePut(putNotification(new GammaKey("g1"), "val"));

            assertThat(received).isEmpty();
        }

        @Test
        void handleRemove_rejects_keys_outside_keyTypeFilter() {
            var received = new ArrayList<String>();
            var router = KVNotificationRouter.<TestKey, String>builder(TestKey.class)
                .onRemove(BetaKey.class, n -> received.add("beta"))
                .build();

            router.handleRemove(removeNotification(new GammaKey("g1")));

            assertThat(received).isEmpty();
        }
    }

    @Nested
    class ErrorIsolation {
        @Test
        void handlePut_exception_in_first_handler_prevents_subsequent_handlers() {
            // Known limitation: handlers use List.forEach which propagates exceptions,
            // so a throwing handler prevents remaining handlers for the same key type from running.
            var received = new ArrayList<String>();
            var router = KVNotificationRouter.<TestKey, String>builder(TestKey.class)
                .onPut(AlphaKey.class, n -> { throw new RuntimeException("handler failure"); })
                .onPut(AlphaKey.class, n -> received.add("second"))
                .build();

            try {
                router.handlePut(putNotification(new AlphaKey("a1"), "val"));
            } catch (RuntimeException e) {
                // Expected: exception propagates out of handlePut
            }

            // Known limitation: the second handler does not run because the first handler's
            // exception propagates through List.forEach, aborting iteration.
            assertThat(received).isEmpty();
        }

        @Test
        void handlePut_exception_in_handler_does_not_affect_different_keyType_handlers() {
            var received = new ArrayList<String>();
            var router = KVNotificationRouter.<TestKey, String>builder(TestKey.class)
                .onPut(AlphaKey.class, n -> { throw new RuntimeException("handler failure"); })
                .onPut(BetaKey.class, n -> received.add("beta"))
                .build();

            try {
                router.handlePut(putNotification(new AlphaKey("a1"), "val"));
            } catch (RuntimeException e) {
                // Expected: alpha handler throws
            }

            // Beta handler is for a different key type, so dispatching a BetaKey works independently
            router.handlePut(putNotification(new BetaKey("b1"), "val"));

            assertThat(received).containsExactly("beta");
        }
    }

    @Nested
    class RouteEntries {
        @Test
        void asRouteEntries_returns_two_entries() {
            var router = KVNotificationRouter.<TestKey, String>builder(TestKey.class)
                .onPut(AlphaKey.class, n -> {})
                .onRemove(BetaKey.class, n -> {})
                .build();

            List<MessageRouter.Entry<?>> entries = router.asRouteEntries();

            assertThat(entries).hasSize(2);
        }
    }

    // --- Helper methods ---

    @SuppressWarnings("unchecked")
    private static <K extends StructuredKey, V> KVStoreNotification.ValuePut<K, V> putNotification(K key, V value) {
        return new KVStoreNotification.ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    @SuppressWarnings("unchecked")
    private static <K extends StructuredKey> KVStoreNotification.ValueRemove<K, ?> removeNotification(K key) {
        return new KVStoreNotification.ValueRemove<>(new KVCommand.Remove<>(key), Option.none());
    }
}
