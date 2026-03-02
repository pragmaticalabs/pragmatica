package com.github.pgasync;

import com.github.pgasync.net.Connectible;
import com.github.pgasync.net.Connection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
        pool.close().await();
    }

    @Test
    public void shouldReceiveNotificationsOnListenedChannel() throws InterruptedException {
        BlockingQueue<String> result = new LinkedBlockingQueue<>(5);

        Connection conn = pool.getConnection().await();
        try {
            var subscription = conn.subscribe("example", result::offer)
                                   .await();
            try {
                TimeUnit.SECONDS.sleep(2);

                pool.completeScript("notify example, 'msg-1'").await();
                pool.completeScript("notify example, 'msg-2'").await();
                pool.completeScript("notify example, 'msg-3'").await();

                assertEquals("msg-1", result.poll(2, TimeUnit.SECONDS));
                assertEquals("msg-2", result.poll(2, TimeUnit.SECONDS));
                assertEquals("msg-3", result.poll(2, TimeUnit.SECONDS));
            } finally {
                subscription.unlisten().await();
            }
        } finally {
            conn.close().await();
        }

        pool.completeQuery("notify example, 'msg'").await();
        assertNull(result.poll(2, TimeUnit.SECONDS));
    }

}
