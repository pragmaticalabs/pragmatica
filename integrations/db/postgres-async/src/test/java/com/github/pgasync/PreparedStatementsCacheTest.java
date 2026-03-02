package com.github.pgasync;

import com.github.pgasync.net.Connectible;
import com.github.pgasync.net.Connection;
import com.github.pgasync.net.PreparedStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.pgasync.DatabaseExtension.block;

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
        block(pool.close());
    }

    @Test
    public void shouldEvictedStatementBeReallyClosed() {
        Connection conn = block(pool.getConnection());
        try {
            PreparedStatement evictor = block(conn.prepareStatement(SELECT_52));
            try {
                PreparedStatement evicted = block(conn.prepareStatement(SELECT_32));
                block(evicted.close());
            } finally {
                block(evictor.close());
            }
        } finally {
            block(conn.close());
        }
    }

    @Test
    public void shouldDuplicatedStatementBeReallyClosed() {
        Connection conn = block(pool.getConnection());
        try {
            PreparedStatement stmt = block(conn.prepareStatement(SELECT_52));
            try {
                PreparedStatement duplicated = block(conn.prepareStatement(SELECT_52));
                block(duplicated.close());
            } finally {
                block(stmt.close());
            }
        } finally {
            block(conn.close());
        }
    }

    @Test
    public void shouldDuplicatedAndEvictedStatementsBeReallyClosed() {
        Connection conn = block(pool.getConnection());
        try {
            PreparedStatement stmt = block(conn.prepareStatement(SELECT_52));
            try {
                PreparedStatement duplicated = block(conn.prepareStatement(SELECT_52));
                try {
                    PreparedStatement evicted = block(conn.prepareStatement(SELECT_32));
                    block(evicted.close());
                } finally {
                    block(duplicated.close());
                }
            } finally {
                block(stmt.close());
            }
        } finally {
            block(conn.close());
        }
    }

}
