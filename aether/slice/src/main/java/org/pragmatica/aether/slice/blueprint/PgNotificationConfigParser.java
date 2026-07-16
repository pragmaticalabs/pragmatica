// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.slice.PgNotificationConfig;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-ZONE-03"})
public interface PgNotificationConfigParser {
    String PREFIX = "pg-notifications.";

    static Result<Map<String, PgNotificationConfig>> parse(String toml) {
        if (toml == null || toml.isBlank()) {
            return success(Map.of());
        }

        return TomlParser.parse(toml)
                         .mapError(err -> cause("PG notification config parse error: " + err.message()))
                         .map(doc -> {
                                  var result = new LinkedHashMap<String, PgNotificationConfig>();

                                  for (var sectionName : doc.sectionNames()) {
                                  if (isPgNotificationSection(sectionName)) {
                                  var name = sectionName.substring(PREFIX.length());

                                  if (!name.contains(".")) {
                                  var datasource = doc.getString(sectionName, "datasource")
                                                      .or("database");
                                  var channels = doc.getStringList(sectionName, "channels")
                                                    .or(List.of());

                                  result.put(name,
                                             PgNotificationConfig.pgNotificationConfig(datasource, channels));
                              }
                              }
                              }

                                  return Map.copyOf(result);
                              });
    }

    private static boolean isPgNotificationSection(String sectionName) {
        return sectionName.startsWith(PREFIX) && sectionName.length() > PREFIX.length();
    }
}
