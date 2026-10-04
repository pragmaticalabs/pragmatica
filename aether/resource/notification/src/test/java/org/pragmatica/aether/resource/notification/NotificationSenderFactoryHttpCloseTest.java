package org.pragmatica.aether.resource.notification;

import org.junit.jupiter.api.Test;
import org.pragmatica.email.http.HttpEmailConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1097: provisioning the HTTP notification backend builds a JDK `HttpClient` with a selector
/// thread; releasing the sender through the factory must end it. Counted by thread, not by the
/// promise `close` returns.
class NotificationSenderFactoryHttpCloseTest {
    private static long selectorThreads() {
        return Thread.getAllStackTraces()
                     .keySet()
                     .stream()
                     .filter(t -> t.getName().startsWith("HttpClient-") && t.getName().endsWith("-SelectorManager"))
                     .filter(Thread::isAlive)
                     .count();
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    @Test
    void close_httpSender_endsTheHttpClientSelectorThread() throws InterruptedException {
        var factory = new NotificationSenderFactory();
        var baseline = selectorThreads();
        var sender = factory.provision(NotificationConfig.httpNotificationConfig(HttpEmailConfig.httpEmailConfig("sendgrid",
                                                                                                                  "key")))
                            .await(timeSpan(10).seconds())
                            .unwrap();

        assertThat(eventually(() -> selectorThreads() > baseline)).as("provisioning started a selector thread").isTrue();
        assertThat(factory.close(sender).await(timeSpan(10).seconds()).isSuccess()).isTrue();
        assertThat(eventually(() -> selectorThreads() <= baseline)).as("selector thread gone; baseline %d now %d",
                                                                      baseline,
                                                                      selectorThreads()).isTrue();
    }
}
