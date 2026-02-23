package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CronExpressionTest {

    @Nested
    class Parsing {
        @Test
        void parse_everyMinute_success() {
            assertThat(CronExpression.parse("* * * * *").isSuccess()).isTrue();
        }

        @Test
        void parse_specificValues_success() {
            assertThat(CronExpression.parse("30 2 15 6 3").isSuccess()).isTrue();
        }

        @Test
        void parse_rangesAndSteps_success() {
            assertThat(CronExpression.parse("*/5 9-17 * * 1-5").isSuccess()).isTrue();
        }

        @Test
        void parse_lists_success() {
            assertThat(CronExpression.parse("0,15,30,45 * * * *").isSuccess()).isTrue();
        }

        @Test
        void parse_invalidFieldCount_failure() {
            var result = CronExpression.parse("* * * * * *");
            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("exactly 5 fields"));
        }

        @Test
        void parse_outOfRange_failure() {
            var result = CronExpression.parse("60 * * * *");
            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("minute"));
        }

        @Test
        void parse_emptyString_failure() {
            assertThat(CronExpression.parse("").isFailure()).isTrue();
        }
    }

    @Nested
    class NextFireTime {
        @Test
        void nextFireTime_everyFiveMinutes_correct() {
            var cron = CronExpression.parse("*/5 * * * *").unwrap();
            var now = Instant.parse("2025-01-15T10:32:00Z");

            var next = cron.nextFireTime(now).unwrap();

            assertThat(next).isEqualTo(Instant.parse("2025-01-15T10:35:00Z"));
        }

        @Test
        void nextFireTime_dailyAtMidnight_correct() {
            var cron = CronExpression.parse("0 0 * * *").unwrap();
            var now = Instant.parse("2025-01-15T10:30:00Z");

            var next = cron.nextFireTime(now).unwrap();

            assertThat(next).isEqualTo(Instant.parse("2025-01-16T00:00:00Z"));
        }

        @Test
        void nextFireTime_specificDayAndTime_correct() {
            // Every Wednesday (3) at 14:30
            var cron = CronExpression.parse("30 14 * * 3").unwrap();
            // 2025-01-15 is a Wednesday
            var now = Instant.parse("2025-01-15T14:30:00Z");

            var next = cron.nextFireTime(now).unwrap();

            // Next Wednesday is 2025-01-22
            assertThat(next).isEqualTo(Instant.parse("2025-01-22T14:30:00Z"));
        }
    }

    @Nested
    class DelayUntilNext {
        @Test
        void delayUntilNext_returnsPositiveDuration() {
            var cron = CronExpression.parse("* * * * *").unwrap();
            var now = Instant.parse("2025-01-15T10:30:00Z");

            var delay = cron.delayUntilNext(now).unwrap();

            assertThat(delay.millis()).isGreaterThan(0);
        }
    }
}
