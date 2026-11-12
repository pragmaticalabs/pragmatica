package org.pragmatica.postgres;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.PoolMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.pragmatica.postgres.DatabaseExtension.block;

/**
 * #2948: every connectible flavour (session pool, transaction pool, plain) sends the configured
 * {@code application_name} in its startup message.
 */
@Tag("Slow")
public class ApplicationNameTest {
    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    private Connectible opened;

    @AfterEach
    public void close() {
        dbr.builder.poolMode(PoolMode.SESSION);
        block(opened.close());
    }

    private static String applicationNameOf(Connectible connectible) {
        return block(connectible.completeQuery("SHOW application_name")).index(0).getString(0);
    }

    @Test
    public void sessionPool_sendsApplicationName() {
        opened = dbr.builder.applicationName("drv-session").pool();

        assertEquals("drv-session", applicationNameOf(opened));
    }

    @Test
    public void transactionPool_sendsApplicationName() {
        opened = dbr.builder.applicationName("drv-transaction").poolMode(PoolMode.TRANSACTION).pool();

        assertEquals("drv-transaction", applicationNameOf(opened));
    }

    @Test
    public void plain_sendsApplicationName() {
        opened = dbr.builder.applicationName("drv-plain").plain();

        assertEquals("drv-plain", applicationNameOf(opened));
    }

    @Test
    public void unset_leavesServerDefault() {
        opened = dbr.builder.applicationName(null).pool();

        assertEquals("", applicationNameOf(opened));
    }
}
