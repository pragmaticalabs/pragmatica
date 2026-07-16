// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;


public sealed interface InputValidators {
    Pattern CIDR_PATTERN = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})$");
    Pattern HOSTNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9]([-a-zA-Z0-9.]*[a-zA-Z0-9])?$");
    Pattern IPV4_PATTERN = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    Pattern ENV_VAR_PATTERN = Pattern.compile("^[A-Z_][A-Z0-9_]*$");
    Pattern CLUSTER_NAME_PATTERN = Pattern.compile("^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$");

    static Result<String> validateCidr(String raw) {
        var value = raw == null
                    ? ""
                    : raw.trim();
        var matcher = CIDR_PATTERN.matcher(value);

        if (!matcher.matches()) {
            return new ClusterInitError.InvalidValue("CIDR", value, "expected a.b.c.d/N (e.g. 10.0.0.0/8)").result();
        }

        for (int i = 1; i <= 4; i++) {
            var octet = Integer.parseInt(matcher.group(i));

            if (octet > 255) {
                return new ClusterInitError.InvalidValue("CIDR", value, "octet " + i + " out of range (0-255)").result();
            }
        }

        var prefix = Integer.parseInt(matcher.group(5));

        if (prefix > 32) {
            return new ClusterInitError.InvalidValue("CIDR", value, "prefix /" + prefix + " out of range (0-32)").result();
        }

        return Result.success(value);
    }

    static Result<String> validateHostnameOrIp(String raw) {
        var value = raw == null
                    ? ""
                    : raw.trim();

        if (value.isEmpty()) {
            return new ClusterInitError.InvalidValue("host", "", "must not be empty").result();
        }

        if (IPV4_PATTERN.matcher(value).matches() || HOSTNAME_PATTERN.matcher(value).matches()) {
            return Result.success(value);
        }

        return new ClusterInitError.InvalidValue("host", value, "expected hostname or IPv4 address").result();
    }

    static Result<Integer> validatePort(String raw) {
        var value = raw == null
                    ? ""
                    : raw.trim();

        try {
            var port = Integer.parseInt(value);

            if (port < 1 || port > 65535) {
                return new ClusterInitError.InvalidValue("port", value, "must be 1-65535").result();
            }

            return Result.success(port);
        } catch (@SuppressWarnings("JBCT-EX-01") NumberFormatException e) {
            return new ClusterInitError.InvalidValue("port", value, "expected integer 1-65535").result();
        }
    }

    static Result<String> validateEnvVarName(String raw) {
        var value = raw == null
                    ? ""
                    : raw.trim();

        if (!ENV_VAR_PATTERN.matcher(value).matches()) {
            return new ClusterInitError.InvalidValue("env var name", value, "must match [A-Z_][A-Z0-9_]*").result();
        }

        return Result.success(value);
    }

    static Result<String> validateClusterName(String raw) {
        var value = raw == null
                    ? ""
                    : raw.trim();

        if (!CLUSTER_NAME_PATTERN.matcher(value).matches()) {
            return new ClusterInitError.InvalidValue("cluster name",
                                                     value,
                                                     "must match [a-z]([a-z0-9-]{0,61}[a-z0-9])? (1-63 chars, e.g. a or prod-eu)").result();
        }

        return Result.success(value);
    }

    record unused() implements InputValidators {}
}
