package org.pragmatica.aether.e2e;

import java.time.Duration;

public final class TestEnvironment {
    private static final boolean IS_CI = System.getenv("CI") != null
        || System.getenv("GITHUB_ACTIONS") != null;

    private static final double TIMEOUT_MULTIPLIER = IS_CI ? 2.0 : 1.0;

    private TestEnvironment() {}

    public static Duration adapt(Duration base) {
        return Duration.ofMillis((long)(base.toMillis() * TIMEOUT_MULTIPLIER));
    }

    public static boolean isCi() {
        return IS_CI;
    }
}
