package com.github.pgasync;

import com.github.pgasync.net.Connectible;
import com.github.pgasync.net.Connection;
import com.github.pgasync.net.PreparedStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * @author Marat Gainullin
 */
@Tag("Slow")
public class PreparedStatementsCacheTest {

    private static final String SELECT_52 = "select 52";
    private static final String SELECT_32 = "select 32";

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.withMaxConnections(5);

    private Connectible pool;

    @BeforeEach
    public void setup() {
        pool = dbr.builder
                .maxConnections(1)
                .maxStatements(1)
                .pool();
    }

    @AfterEach
    public void shutdown() {
        pool.close().await();
    }

    @Test
    public void shouldEvictedStatementBeReallyClosed() {
        Connection conn = pool.getConnection().await();
        try {
            PreparedStatement evictor = conn.prepareStatement(SELECT_52).await();
            try {
                PreparedStatement evicted = conn.prepareStatement(SELECT_32).await();
                evicted.close().await();
            } finally {
                evictor.close().await();
            }
        } finally {
            conn.close().await();
        }
    }

    @Test
    public void shouldDuplicatedStatementBeReallyClosed() {
        Connection conn = pool.getConnection().await();
        try {
            PreparedStatement stmt = conn.prepareStatement(SELECT_52).await();
            try {
                PreparedStatement duplicated = conn.prepareStatement(SELECT_52).await();
                duplicated.close().await();
            } finally {
                stmt.close().await();
            }
        } finally {
            conn.close().await();
        }
    }

    @Test
    public void shouldDuplicatedAndEvictedStatementsBeReallyClosed() {
        Connection conn = pool.getConnection().await();
        try {
            PreparedStatement stmt = conn.prepareStatement(SELECT_52).await();
            try {
                PreparedStatement duplicated = conn.prepareStatement(SELECT_52).await();
                try {
                    PreparedStatement evicted = conn.prepareStatement(SELECT_32).await();
                    evicted.close().await();
                } finally {
                    duplicated.close().await();
                }
            } finally {
                stmt.close().await();
            }
        } finally {
            conn.close().await();
        }
    }

}
