package org.pragmatica.aether.forge;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Embedded H2 database server for Forge.
/// Provides an in-memory or persistent H2 database that slices can connect to.
@SuppressWarnings({"JBCT-RET-03", "JBCT-EX-01"})
public final class ForgeH2Server {
    private static final Logger log = LoggerFactory.getLogger(ForgeH2Server.class);

    private final ForgeH2Config config;
    private volatile Server tcpServer;

    private ForgeH2Server(ForgeH2Config config) {
        this.config = config;
    }

    /// Create a new H2 server instance.
    public static ForgeH2Server forgeH2Server(ForgeH2Config config) {
        return new ForgeH2Server(config);
    }

    /// Start the H2 TCP server.
    public Promise<Unit> start() {
        if (!config.enabled()) {
            log.debug("H2 server disabled, skipping start");
            return Promise.success(Unit.unit());
        }
        return Promise.lift(H2Error.StartFailed::new, this::startServer)
                      .flatMap(this::initializeDatabase)
                      .onSuccess(_ -> log.info("H2 server started on port {} ({})",
                                               config.port(),
                                               config.persistent()
                                               ? "persistent"
                                               : "in-memory"));
    }

    private Server startServer() throws SQLException {
        var args = new String[]{"-tcp",
        "-tcpPort", String.valueOf(config.port()),
        "-tcpAllowOthers",
        "-ifNotExists"};
        tcpServer = Server.createTcpServer(args)
                          .start();
        return tcpServer;
    }

    private Promise<Unit> initializeDatabase(Server server) {
        return config.initScript()
                     .map(this::runInitScript)
                     .or(() -> {
                             log.debug("No init script configured, skipping database initialization");
                             return Promise.success(Unit.unit());
                         });
    }

    private Promise<Unit> runInitScript(String scriptPath) {
        log.info("Running H2 init script: {}", scriptPath);
        return Promise.lift(H2Error.InitScriptFailed::new,
                            () -> {
                                try (Connection conn = DriverManager.getConnection(jdbcUrl(),
                                                                                   "sa",
                                                                                   "")) {
                                    var statement = conn.createStatement();
                                    statement.execute("RUNSCRIPT FROM '" + scriptPath + "'");
                                }
                            })
                      .mapToUnit()
                      .onSuccess(_ -> log.info("H2 init script completed"))
                      .onFailure(cause -> log.error("H2 init script failed: {}",
                                                    cause.message()));
    }

    /// Stop the H2 TCP server.
    public Promise<Unit> stop() {
        if (tcpServer == null) {
            return Promise.success(Unit.unit());
        }
        return Promise.lift(H2Error.StopFailed::new,
                            () -> {
                                tcpServer.stop();
                                tcpServer = null;
                                log.info("H2 server stopped");
                            })
                      .mapToUnit();
    }

    /// Get the JDBC URL for connecting to this H2 server.
    public String jdbcUrl() {
        var dbPath = config.persistent()
                     ? "tcp://localhost:" + config.port() + "/./" + config.name()
                     : "tcp://localhost:" + config.port() + "/mem:" + config.name() + ";DB_CLOSE_DELAY=-1";
        return "jdbc:h2:" + dbPath;
    }

    /// Check if the server is running.
    public boolean isRunning() {
        return tcpServer != null && tcpServer.isRunning(false);
    }

    /// Get the server port.
    public int port() {
        return config.port();
    }

    /// H2 server errors.
    public sealed interface H2Error extends Cause {
        record StartFailed(Throwable cause) implements H2Error {
            @Override
            public String message() {
                return "Failed to start H2 server: " + cause.getMessage();
            }
        }

        record StopFailed(Throwable cause) implements H2Error {
            @Override
            public String message() {
                return "Failed to stop H2 server: " + cause.getMessage();
            }
        }

        record InitScriptFailed(Throwable cause) implements H2Error {
            @Override
            public String message() {
                return "Failed to run H2 init script: " + cause.getMessage();
            }
        }
    }
}
