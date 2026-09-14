package org.pragmatica.dht;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #420 — a refused anti-entropy send is logged at WARN, never dropped silently: the transport's
/// `WriteOutcome` (BackpressureRefused / ConnectionDead / NoPeerState) used to vanish into the
/// fire-and-forget `send`. The next scheduled round is the retry; the WARN says so.
class DHTAntiEntropySendOutcomeTest {
    private static final String LOGGER_NAME = DHTAntiEntropy.class.getName();
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("AntiEntropySendOutcomeCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.WARN, null);
        loggerConfig.setLevel(Level.WARN);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    @Test
    void refusedDigestSend_isLoggedAtWarn_namingPeerAndOutcome() {
        var self = new NodeId("node-0");
        var peer = new NodeId("node-1");
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        ring.addNode(self);
        ring.addNode(peer);
        var node = dhtNode(self, memoryStorageEngine(), ring, CONFIG);
        var sends = new AtomicInteger();
        var network = new DHTNetwork() {
            @Override
            public void send(NodeId target, ProtocolMessage message) {
                sends.incrementAndGet();
            }

            @Override
            public Promise<WriteOutcome> sendOutcome(NodeId target, ProtocolMessage message) {
                sends.incrementAndGet();
                return Promise.success(new WriteOutcome.BackpressureRefused(target));
            }
        };

        dhtAntiEntropy(node, network, CONFIG).synchronizeNow();

        assertThat(sends.get()).as("control: the round did send digests").isPositive();
        assertThat(appender.warns()).as("every refused send is reported")
                                    .hasSize(sends.get())
                                    .allMatch(m -> m.contains("node-1") && m.contains("BackpressureRefused") && m.contains("next round"));
    }

    @Test
    void acceptedSend_logsNothing() {
        var self = new NodeId("node-0");
        var peer = new NodeId("node-1");
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        ring.addNode(self);
        ring.addNode(peer);
        var node = dhtNode(self, memoryStorageEngine(), ring, CONFIG);
        DHTNetwork network = (target, message) -> {};

        dhtAntiEntropy(node, network, CONFIG).synchronizeNow();

        assertThat(appender.warns()).isEmpty();
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER_NAME);
        if (LOGGER_NAME.equals(existing.getName())) {
            return existing;
        }
        var fresh = new LoggerConfig(LOGGER_NAME, Level.WARN, false);
        configuration.addLogger(LOGGER_NAME, fresh);
        return fresh;
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> warns() {
            return List.copyOf(messages);
        }
    }
}
