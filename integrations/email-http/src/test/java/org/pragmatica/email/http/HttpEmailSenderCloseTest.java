package org.pragmatica.email.http;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.AsyncCloseable;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1097: the sender holds the `HttpOperations` its factory built, so it must close them. The pins
/// COUNT closes on the held operations; a promise that succeeds proves nothing.
class HttpEmailSenderCloseTest {
    private static final class RecordingOperations implements HttpOperations, AsyncCloseable {
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            return Promise.failure(Causes.cause("not under test"));
        }

        @Override
        public Promise<Unit> close() {
            closes.incrementAndGet();

            return Promise.unitPromise();
        }
    }

    @Test
    void knownVendor_closesOperationsItBuilt() {
        var ops = new RecordingOperations();
        var sender = HttpEmailSenderCore.create(HttpEmailConfig.httpEmailConfig("sendgrid", "key"), ops, true);

        assertThat(sender).isInstanceOf(AsyncCloseable.class);
        assertThat(((AsyncCloseable) sender).close().await(timeSpan(5).seconds()).isSuccess()).isTrue();
        assertThat(ops.closes.get()).isEqualTo(1);
    }

    @Test
    void unknownVendor_stillClosesOperationsItBuilt() {
        var ops = new RecordingOperations();
        var sender = HttpEmailSenderCore.create(HttpEmailConfig.httpEmailConfig("no-such-vendor", "key"), ops, true);

        assertThat(sender).isInstanceOf(AsyncCloseable.class);
        assertThat(((AsyncCloseable) sender).close().await(timeSpan(5).seconds()).isSuccess()).isTrue();
        assertThat(ops.closes.get()).isEqualTo(1);
    }

    /// Ownership ruling: the caller owns what it passes in. The public `httpEmailSender(config, ops)` must
    /// not close `ops`, for a known and for an unknown vendor.
    @Test
    void callerSuppliedOperations_areNeverClosed() {
        for (var vendor : new String[]{"sendgrid", "no-such-vendor"}) {
            var ops = new RecordingOperations();
            var sender = HttpEmailSender.httpEmailSender(HttpEmailConfig.httpEmailConfig(vendor, "key"), ops);

            assertThat(((AsyncCloseable) sender).close().await(timeSpan(5).seconds()).isSuccess()).isTrue();
            assertThat(ops.closes.get()).as("caller-supplied operations for %s", vendor).isZero();
        }
    }
}
