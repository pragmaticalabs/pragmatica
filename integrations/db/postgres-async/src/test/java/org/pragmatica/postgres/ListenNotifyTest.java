package org.pragmatica.postgres;

import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.pragmatica.postgres.DatabaseExtension.block;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author Antti Laisi
 */
@Tag("Slow")
public class ListenNotifyTest {

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.withMaxConnections(5);

    private Connectible pool;

    @BeforeEach
    public void setup() {
        pool = dbr.pool();
    }

    @AfterEach
    public void shutdown() {
        block(pool.close());
    }

    @Test
    public void shouldReceiveNotificationsOnListenedChannel() throws InterruptedException {
        BlockingQueue<String> result = new LinkedBlockingQueue<>(5);

        Connection conn = block(pool.getConnection());
        try {
            var subscription = block(conn.subscribe("example", (String payload) -> result.offer(payload)));
            try {
                TimeUnit.SECONDS.sleep(2);

                block(pool.completeScript("notify example, 'msg-1'"));
                block(pool.completeScript("notify example, 'msg-2'"));
                block(pool.completeScript("notify example, 'msg-3'"));

                assertEquals("msg-1", result.poll(2, TimeUnit.SECONDS));
                assertEquals("msg-2", result.poll(2, TimeUnit.SECONDS));
                assertEquals("msg-3", result.poll(2, TimeUnit.SECONDS));
            } finally {
                block(subscription.unlisten());
            }
        } finally {
            block(conn.close());
        }

        block(pool.completeQuery("notify example, 'msg'"));
        assertNull(result.poll(2, TimeUnit.SECONDS));
    }

}
