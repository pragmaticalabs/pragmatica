// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class EmberInstance {
    private static final Logger log = LoggerFactory.getLogger(EmberInstance.class);

    private final EmberConfig config;
    private final EmberCluster cluster;
    private volatile Option<EmberH2Server> h2Server = Option.empty();

    private EmberInstance(EmberConfig config) {
        this.config = config;
        this.cluster = EmberCluster.emberCluster(config.nodes(),
                                                 EmberCluster.DEFAULT_BASE_PORT,
                                                 config.managementPort(),
                                                 config.appHttpPort(),
                                                 "node",
                                                 Option.empty(),
                                                 config.observability(),
                                                 config.coreMax());
    }

    static EmberInstance emberInstance(EmberConfig config) {
        var instance = new EmberInstance(config);

        instance.startH2();
        instance.cluster.start().await(TimeSpan.timeSpan(60).seconds()).onFailure(cause -> log.error("Failed to start cluster: {}",
                                                                                                     cause.message()));

        return instance;
    }

    private void startH2() {
        if (!config.h2Config().enabled()) {
            return;
        }

        var server = EmberH2Server.emberH2Server(config.h2Config());

        server.start().await(TimeSpan.timeSpan(10).seconds()).onSuccess(_ -> {
            h2Server = Option.some(server);
            log.info("H2 database available at: {}", server.jdbcUrl());
        }).onFailure(cause -> log.error("Failed to start H2 server: {}", cause.message()));
    }

    public EmberCluster cluster() {
        return cluster;
    }

    public EmberConfig config() {
        return config;
    }

    public Option<String> h2JdbcUrl() {
        return h2Server.filter(EmberH2Server::isRunning)
                       .map(EmberH2Server::jdbcUrl);
    }

    public Promise<Unit> stop() {
        return cluster.stop()
                      .flatMap(_ -> stopH2());
    }

    private Promise<Unit> stopH2() {
        return h2Server.map(EmberH2Server::stop)
                       .or(Promise.success(Unit.unit()));
    }
}
