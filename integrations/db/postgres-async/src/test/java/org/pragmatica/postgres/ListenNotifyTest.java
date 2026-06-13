package org.pragmatica.postgres;

import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.Listening;
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
        // Build a fresh pool per test so the shared static container survives across tests.
        pool = dbr.builder.maxConnections(5).pool();
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

    @Test
    public void subscribeInsideTxCommit_handlerStaysActiveAfterCommit() throws InterruptedException {
        BlockingQueue<String> result = new LinkedBlockingQueue<>(5);
        Connection conn = block(pool.getConnection());
        Listening subscription = null;
        try {
            var tx = block(conn.begin());
            subscription = block(tx.getConnection().subscribe("ch_tx_commit", (String payload) -> result.offer(payload)));
            block(tx.commit());

            TimeUnit.SECONDS.sleep(1);
            block(pool.completeScript("notify ch_tx_commit, 'after-commit'"));

            assertEquals("after-commit", result.poll(2, TimeUnit.SECONDS));
        } finally {
            if (subscription != null) {
                block(subscription.unlisten());
            }
            block(conn.close());
        }
    }

    @Test
    public void subscribeInsideTxRollback_handlerRemovedAfterRollback() throws InterruptedException {
        BlockingQueue<String> result = new LinkedBlockingQueue<>(5);
        Connection conn = block(pool.getConnection());
        try {
            var tx = block(conn.begin());
            // Construct the subscription inside the transaction. ROLLBACK must drop
            // the local handler too — server-side LISTEN is also nullified.
            block(tx.getConnection().subscribe("ch_tx_rollback", (String payload) -> result.offer(payload)));
            block(tx.rollback());

            TimeUnit.SECONDS.sleep(1);
            block(pool.completeScript("notify ch_tx_rollback, 'should-not-fire'"));

            // Handler was removed by the rollback hook — no notification should arrive.
            assertNull(result.poll(1, TimeUnit.SECONDS));
        } finally {
            block(conn.close());
        }
    }

    @Test
    public void unlistenInsideTxCommit_handlerRemovedAfterCommit() throws InterruptedException {
        BlockingQueue<String> result = new LinkedBlockingQueue<>(5);
        Connection conn = block(pool.getConnection());
        try {
            var subscription = block(conn.subscribe("ch_unlisten_commit", (String payload) -> result.offer(payload)));
            TimeUnit.SECONDS.sleep(1);

            // Sanity check — subscription works before the transaction.
            block(pool.completeScript("notify ch_unlisten_commit, 'baseline'"));
            assertEquals("baseline", result.poll(2, TimeUnit.SECONDS));

            var tx = block(conn.begin());
            block(subscription.unlisten());
            block(tx.commit());

            TimeUnit.SECONDS.sleep(1);
            block(pool.completeScript("notify ch_unlisten_commit, 'post-commit'"));

            // After COMMIT both UNLISTEN and the deferred handler removal are applied.
            assertNull(result.poll(1, TimeUnit.SECONDS));
        } finally {
            block(conn.close());
        }
    }

    @Test
    public void unlistenInsideTxRollback_handlerStillFiresAfterRollback() throws InterruptedException {
        BlockingQueue<String> result = new LinkedBlockingQueue<>(5);
        Connection conn = block(pool.getConnection());
        Listening subscription = null;
        try {
            subscription = block(conn.subscribe("ch_unlisten_rollback", (String payload) -> result.offer(payload)));
            TimeUnit.SECONDS.sleep(1);

            var tx = block(conn.begin());
            block(subscription.unlisten());
            block(tx.rollback());

            // ROLLBACK nullified UNLISTEN on the server, so server-side LISTEN remains.
            // The fix keeps the client-side handler in place too — notifications must still flow.
            TimeUnit.SECONDS.sleep(1);
            block(pool.completeScript("notify ch_unlisten_rollback, 'after-rollback'"));

            assertEquals("after-rollback", result.poll(2, TimeUnit.SECONDS));
        } finally {
            if (subscription != null) {
                block(subscription.unlisten());
            }
            block(conn.close());
        }
    }
}
